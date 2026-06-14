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
)

enum class SessionState {
    STOPPED,
    RUNNING,
    ERROR,
}
