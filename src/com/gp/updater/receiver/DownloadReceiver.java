package com.gp.updater.receiver;

import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.gp.updater.OTA;
import com.gp.updater.R;
import com.gp.updater.UpdateApplication;
import com.gp.updater.Updater;
import com.gp.updater.misc.Constants;
import com.gp.updater.misc.UpdateInfo;
import com.gp.updater.utils.MD5;
import com.gp.updater.utils.Utils;

import java.io.File;

public class DownloadReceiver extends BroadcastReceiver{
    private static final String TAG = "DownloadReceiver";

    public static final String ACTION_START_DOWNLOAD = "com.gp.updater.action.START_DOWNLOAD";
    public static final String EXTRA_UPDATE_INFO = "update_info";

    public static final String ACTION_DOWNLOAD_STARTED = "com.gp.updater.action.DOWNLOAD_STARTED";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        
        if (ACTION_START_DOWNLOAD.equals(action)) {          
        	UpdateInfo ui = (UpdateInfo) intent.getParcelableExtra(EXTRA_UPDATE_INFO);
            handleStartDownload(context, prefs, ui);
        } else if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            handleDownloadComplete(context, prefs, id);
        }
    }

    private void handleStartDownload(Context context, SharedPreferences prefs, UpdateInfo ui) {
        // If directory doesn't exist, create it
        File directory = Utils.cleanUpdateFolder();
        if (!directory.exists()) {
            directory.mkdirs();
            Log.d(TAG, "UpdateFolder created");
        }

        // Build the name of the file to download, adding .partial at the end.  It will get
        // stripped off when the download completes
        String fullFilePath = "file://" + directory.getAbsolutePath() + "/" + ui.getFileName() + ".partial";

        Request request = new Request(Uri.parse(ui.getDownloadUrl()));
        String userAgent = Utils.getUserAgentString(context);
        if (userAgent != null) {
            request.addRequestHeader("User-Agent", userAgent);
        }
        request.addRequestHeader("Cache-Control", "no-cache");

        request.setTitle(ui.getName());
        request.setDestinationUri(Uri.parse(fullFilePath));
        request.setAllowedOverRoaming(false);
        request.setVisibleInDownloadsUi(false);

        // TODO: this could/should be made configurable
        request.setAllowedOverMetered(true);

        // Start the download
        final DownloadManager dm =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long downloadId = dm.enqueue(request);

        // Store in shared preferences
        prefs.edit()
                .putLong(Constants.DOWNLOAD_ID, downloadId)
                .putString(Constants.DOWNLOAD_MD5, ui.getMD5Sum())
                .putInt(Constants.UI_TYPE, ui.getType().ordinal())
                .apply();

        Utils.cancelNotification(context);

        Intent intent = new Intent(ACTION_DOWNLOAD_STARTED);
        intent.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId);
        intent.putExtra(DownloadReceiver.EXTRA_UPDATE_INFO, (Parcelable) ui);
        context.sendBroadcast(intent);
    }

    private void handleDownloadComplete(Context context, SharedPreferences prefs, long id) {
        long enqueued = prefs.getLong(Constants.DOWNLOAD_ID, -1);

        if (enqueued < 0 || id < 0 || id != enqueued) {
            return;
        }

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Query query = new Query();
        query.setFilterById(id);

        Cursor c = dm.query(query);
        if (c == null) {
            return;
        }

        if (!c.moveToFirst()) {
            c.close();
            return;
        }

        final int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
        int failureMessageResId = -1;
        File updateFile = null;   
        Intent updateIntent = null;
        
		updateIntent = new Intent(context, OTA.class);
        
        updateIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP |
                Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            // Get the full path name of the downloaded file and the MD5

            // Strip off the .partial at the end to get the completed file
            String partialFileFullPath = c.getString(
                    c.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME));
            String completedFileFullPath = partialFileFullPath.replace(".partial", "");

            File partialFile = new File(partialFileFullPath);
            updateFile = new File(completedFileFullPath);
            partialFile.renameTo(updateFile);

            String downloadedMD5 = prefs.getString(Constants.DOWNLOAD_MD5, "");

            // Start the MD5 check of the downloaded file
            if (MD5.checkMD5(downloadedMD5, updateFile)) {
                // We passed. Bring the main app to the foreground and trigger download completed
                updateIntent.putExtra(Updater.EXTRA_FINISHED_DOWNLOAD_ID, id);
                updateIntent.putExtra(Updater.EXTRA_FINISHED_DOWNLOAD_PATH, completedFileFullPath);
            } else {
                // We failed. Clear the file and reset everything
                dm.remove(id);

                if (updateFile.exists()) {
                    updateFile.delete();
                }

                failureMessageResId = R.string.md5_verification_failed;
            }
        } else if (status == DownloadManager.STATUS_FAILED) {
            // The download failed, reset
            dm.remove(id);

            Utils.deletePartials(Utils.getUpdateFolder());

            failureMessageResId = R.string.unable_to_download_file;
        }

        // Clear the shared prefs
        prefs.edit()
                .remove(Constants.DOWNLOAD_MD5)
                .remove(Constants.DOWNLOAD_ID)
                .remove(Constants.UI_TYPE)
                .apply();

        c.close();

        final UpdateApplication app = (UpdateApplication) context.getApplicationContext();
        if (app.isMainActivityActive()) {
            if (failureMessageResId >= 0) {
                Toast.makeText(context, failureMessageResId, Toast.LENGTH_LONG).show();
            } else {
                context.startActivity(updateIntent);
            }
        } else {
            // Get the notification ready
            PendingIntent contentIntent = PendingIntent.getActivity(context, 1,
                    updateIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder builder = new Notification.Builder(context)
                    .setSmallIcon(R.drawable.updater)
                    .setWhen(System.currentTimeMillis())
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true);

            if (failureMessageResId >= 0) {
                builder.setContentTitle(context.getString(R.string.not_download_failure));
                builder.setContentText(context.getString(failureMessageResId));
                builder.setTicker(context.getString(R.string.not_download_failure));
            } else {
                String updateUiName = UpdateInfo.extractUiName(updateFile.getName());

                builder.setContentTitle(context.getString(R.string.not_download_success));
                builder.setContentText(updateUiName);
                builder.setTicker(context.getString(R.string.not_download_success));

                Notification.BigTextStyle style = new Notification.BigTextStyle();
                style.setBigContentTitle(context.getString(R.string.not_download_success));
                style.bigText(context.getString(R.string.not_download_install_notice, updateUiName));
                builder.setStyle(style);
            }

            final NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(R.string.not_download_success, builder.build());
        }
    }
}
