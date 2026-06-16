package com.example.studycapturehelper.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
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

    override fun onCreate() {
        super.onCreate()
        notifications.createChannel()
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
        captureJob = lifecycleScope.launch {
            runCatching {
                camera.connect()
                while (true) {
                    val settings = settingsRepository.settings.first()
                    val analysis = analyzer.analyze(camera.captureJpeg())
                    if (settings.speechEnabled) speechOutput.speak(analysis.text)

                    val multiplier = thermalPolicy.multiplier.first()
                    delay(intervalPolicy.delayMillis(settings.intervalSeconds, multiplier))
                }
            }.onFailure { e ->
                Log.e("CaptureSvc", "세션 오류: ${e.message}", e)
                sessionStatus.update(SessionState.ERROR)
                getSystemService(NotificationManager::class.java)
                    .notify(NotificationFactory.ERROR_ID, notifications.error())
                stopSelf()
            }
        }
    }

    private fun stopSession() {
        captureJob?.cancel()
        captureJob = null
        speechOutput.stop()
        lifecycleScope.launch { runCatching { camera.disconnect() } }
        sessionStatus.update(SessionState.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        captureJob?.cancel()
        speechOutput.stop()
        lifecycleScope.launch { runCatching { camera.disconnect() } }
        if (sessionStatus.state.value == SessionState.RUNNING) {
            sessionStatus.update(SessionState.STOPPED)
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.studycapturehelper.START"
        const val ACTION_STOP = "com.example.studycapturehelper.STOP"
    }
}
