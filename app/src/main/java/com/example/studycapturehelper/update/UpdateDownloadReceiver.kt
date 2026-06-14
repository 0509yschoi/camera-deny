package com.example.studycapturehelper.update

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val preferences = context.getSharedPreferences(
            UpdateDownloadManager.PREFERENCES,
            Context.MODE_PRIVATE,
        )
        if (completedId != preferences.getLong(UpdateDownloadManager.KEY_DOWNLOAD_ID, -2L)) return

        val downloadManager = context.getSystemService(DownloadManager::class.java)
        val downloadedUri = downloadManager.getUriForDownloadedFile(completedId) ?: return
        preferences.edit().remove(UpdateDownloadManager.KEY_DOWNLOAD_ID).apply()

        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(downloadedUri, UpdateDownloadManager.APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val installPendingIntent = PendingIntent.getActivity(
            context,
            20,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        notificationManager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("업데이트 다운로드 완료")
                .setContentText("눌러서 새 버전을 설치하세요.")
                .setContentIntent(installPendingIntent)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 2001
    }
}
