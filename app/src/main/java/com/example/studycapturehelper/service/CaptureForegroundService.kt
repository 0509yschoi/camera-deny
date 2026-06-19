package com.example.studycapturehelper.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.studycapturehelper.domain.CameraCapture
import com.example.studycapturehelper.domain.CaptureIntervalPolicy
import com.example.studycapturehelper.domain.ImageAnalyzer
import com.example.studycapturehelper.domain.SessionState
import com.example.studycapturehelper.domain.SettingsRepository
import com.example.studycapturehelper.domain.SpeechOutput
import com.example.studycapturehelper.domain.ThermalPolicy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "CaptureSvc"

@AndroidEntryPoint
class CaptureForegroundService : LifecycleService() {
    @Inject lateinit var camera: CameraCapture
    @Inject lateinit var analyzer: ImageAnalyzer
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var speechOutput: SpeechOutput
    @Inject lateinit var thermalPolicy: ThermalPolicy
    @Inject lateinit var intervalPolicy: CaptureIntervalPolicy
    @Inject lateinit var sessionStatus: SessionStatus
    @Inject lateinit var notifications: NotificationFactory

    private var captureJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createChannel()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StudyCapture::WakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            else -> startSession()
        }
        return Service.START_NOT_STICKY
    }

    private fun startSession() {
        if (captureJob?.isActive == true) return
        startForeground(
            NotificationFactory.ACTIVE_ID,
            notifications.active("Camera use is visible while this session runs."),
        )
        sessionStatus.update(SessionState.RUNNING)
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
        captureJob = lifecycleScope.launch {
            runCatching {
                Log.d(TAG, "Connecting camera")
                camera.connect()
                Log.d(TAG, "Camera connected; capture loop started")
                while (true) {
                    val settings = settingsRepository.settings.first()
                    Log.d(TAG, "Capturing camera burst")
                    val captured = captureBurst()
                    Log.d(TAG, "Camera burst captured: ${captured.size} frames")
                    captured.lastOrNull()?.let { sessionStatus.updateLastImage(it.bytes) }
                    val analysis = analyzer.analyze(captured)
                    sessionStatus.updateAnalysis(analysis.text, analysis.debugText)
                    if (settings.speechEnabled) speechOutput.speak(analysis.text)

                    val multiplier = thermalPolicy.multiplier.first()
                    delay(intervalPolicy.delayMillis(settings.intervalSeconds, multiplier))
                }
            }.onFailure { e ->
                val msg = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "Session error: $msg", e)
                sessionStatus.update(SessionState.ERROR(msg))
                getSystemService(NotificationManager::class.java)
                    .notify(NotificationFactory.ERROR_ID, notifications.error())
                stopSelf()
            }
        }
    }

    private suspend fun captureBurst(): List<com.example.studycapturehelper.domain.CapturedImage> {
        val frames = mutableListOf<com.example.studycapturehelper.domain.CapturedImage>()
        repeat(BURST_FRAME_COUNT) { index ->
            val captured = camera.captureJpeg()
            Log.d(TAG, "Camera burst frame ${index + 1}: ${captured.bytes.size} bytes")
            sessionStatus.updateLastImage(captured.bytes)
            frames += captured
            if (index < BURST_FRAME_COUNT - 1) delay(BURST_FRAME_DELAY_MS)
        }
        return frames
    }

    private fun stopSession() {
        captureJob?.cancel()
        captureJob = null
        speechOutput.stop()
        wakeLock?.release()
        lifecycleScope.launch { runCatching { camera.disconnect() } }
        sessionStatus.update(SessionState.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        captureJob?.cancel()
        speechOutput.stop()
        lifecycleScope.launch { runCatching { camera.disconnect() } }
        if (sessionStatus.state.value is SessionState.RUNNING) {
            sessionStatus.update(SessionState.STOPPED)
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.studycapturehelper.START"
        const val ACTION_STOP = "com.example.studycapturehelper.STOP"
        private const val BURST_FRAME_COUNT = 5
        private const val BURST_FRAME_DELAY_MS = 700L
    }
}
