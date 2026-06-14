package com.example.studycapturehelper.domain

data class AppUpdate(
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val fileName: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object NotConfigured : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate) : UpdateState
    data class Error(val message: String) : UpdateState
}

interface AppUpdateRepository {
    suspend fun checkForUpdate(): UpdateState
}
