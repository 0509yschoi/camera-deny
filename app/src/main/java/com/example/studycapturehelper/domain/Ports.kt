package com.example.studycapturehelper.domain

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<CaptureSettings>
    suspend fun setIntervalSeconds(seconds: Int)
    suspend fun setSpeechEnabled(enabled: Boolean)
}

interface CameraCapture {
    suspend fun connect()
    suspend fun captureJpeg(): CapturedImage
    suspend fun disconnect()
}

interface ImageAnalyzer {
    suspend fun analyze(image: CapturedImage): StudyAnalysis
}

interface SpeechOutput {
    suspend fun speak(text: String)
    fun stop()
}

interface ThermalPolicy {
    val multiplier: Flow<Int>
}
