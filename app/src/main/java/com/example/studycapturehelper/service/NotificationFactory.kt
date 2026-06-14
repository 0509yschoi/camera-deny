package com.example.studycapturehelper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.studycapturehelper.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun createChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Active study capture",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun active(message: String): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, StopSessionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Study capture is active")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    fun error(): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentTitle("Study capture stopped")
        .setContentText("Open the app to check the camera or connection.")
        .setAutoCancel(true)
        .build()

    companion object {
        const val CHANNEL_ID = "capture_session"
        const val ACTIVE_ID = 1001
        const val ERROR_ID = 1002
    }
}
