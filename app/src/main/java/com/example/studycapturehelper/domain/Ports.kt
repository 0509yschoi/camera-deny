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
    suspend fun captureJpegs(count: Int, delayMillis: Long): List<CapturedImage> =
        List(count.coerceAtLeast(1)) {
            captureJpeg()
        }
    suspend fun disconnect()
}

interface ImageAnalyzer {
    suspend fun analyze(image: CapturedImage): StudyAnalysis
    suspend fun analyze(images: List<CapturedImage>): StudyAnalysis = analyze(images.first())
}

interface PastQuestionBank {
    suspend fun findRelevant(ocrText: String, limit: Int = 5): List<PastQuestionMatch>
}

data class PastQuestionMatch(
    val id: String,
    val source: String,
    val subject: String,
    val question: String,
    val choices: List<String>,
    val answer: String,
    val explanation: String?,
    val score: Int,
)

interface SpeechOutput {
    suspend fun speak(text: String)
    fun stop()
}

interface ThermalPolicy {
    val multiplier: Flow<Int>
}
