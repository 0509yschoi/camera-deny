package com.example.studycapturehelper.domain

data class CaptureSettings(
    val intervalSeconds: Int = 50,
    val speechEnabled: Boolean = true,
)

data class CapturedImage(
    val bytes: ByteArray,
    val mimeType: String = "image/jpeg",
)

data class StudyAnalysis(
    val text: String,
    val debugText: String? = null,
)

sealed class SessionState {
    object STOPPED : SessionState()
    object RUNNING : SessionState()
    data class ERROR(val message: String) : SessionState()
}
