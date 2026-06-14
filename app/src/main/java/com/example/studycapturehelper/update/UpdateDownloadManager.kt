package com.example.studycapturehelper.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.studycapturehelper.domain.AppUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue(update: AppUpdate): Long {
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("Study Capture Helper ${update.versionName}")
            .setDescription("업데이트 APK 다운로드 중")
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setAllowedOverMetered(true)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "StudyCaptureHelper/${update.fileName}",
            )
        val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .apply()
        return id
    }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val PREFERENCES = "app_update"
        const val KEY_DOWNLOAD_ID = "download_id"
    }
}
