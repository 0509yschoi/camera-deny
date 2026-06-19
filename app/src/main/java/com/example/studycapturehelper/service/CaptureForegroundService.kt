package com.example.studycapturehelper.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.studycapturehelper.domain.CameraCapture
import com.example.studycapturehelper.domain.CaptureIntervalPolicy
import com.example.studycapturehelper.domain.CapturedImage
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
import kotlinx.coroutines.withTimeout

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
    private var previousInterruptionFilter: Int? = null

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
        sessionStatus.updateProgress("Starting session...")
        runCatching {
            startForeground(
                NotificationFactory.ACTIVE_ID,
                notifications.active("Camera use is visible while this session runs."),
            )
        }.onFailure { error ->
            failSession("Foreground service failed", error)
            stopSelf()
            return
        }
        sessionStatus.update(SessionState.RUNNING)
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
        captureJob = lifecycleScope.launch {
            runCatching {
                val initialSettings = settingsRepository.settings.first()
                applyDoNotDisturbIfNeeded(initialSettings.dndEnabled)
                Log.d(TAG, "Connecting camera")
                sessionStatus.updateProgress("Connecting USB camera...")
                withTimeout(CAMERA_CONNECT_TIMEOUT_MS) { camera.connect() }
                Log.d(TAG, "Camera connected; capture loop started")
                var activeWindowStartedAt = SystemClock.elapsedRealtime()
                while (true) {
                    val settings = settingsRepository.settings.first()
                    applyDoNotDisturbIfNeeded(settings.dndEnabled)
                    if (SystemClock.elapsedRealtime() - activeWindowStartedAt >= ACTIVE_WINDOW_MS) {
                        coolDownCamera()
                        sessionStatus.updateProgress("Reconnecting USB camera after cooldown...")
                        withTimeout(CAMERA_CONNECT_TIMEOUT_MS) { camera.connect() }
                        activeWindowStartedAt = SystemClock.elapsedRealtime()
                    }
                    Log.d(TAG, "Capturing camera burst")
                    sessionStatus.updateProgress("USB camera connected. Capturing 5 frames...")
                    val captureStartedAt = SystemClock.elapsedRealtime()
                    val captured = withTimeout(CAPTURE_TIMEOUT_MS) { captureBurst() }
                    val captureMs = SystemClock.elapsedRealtime() - captureStartedAt
                    Log.d(TAG, "Camera burst captured: ${captured.size} frames in ${captureMs}ms")
                    captured.lastOrNull()?.let { sessionStatus.updateLastImage(it.bytes) }
                    sessionStatus.updateProgress("Analyzing ${captured.size} frames... capture=${captureMs / 1000.0}s")
                    val analysisStartedAt = SystemClock.elapsedRealtime()
                    val analysis = analyzer.analyze(captured)
                    val analysisMs = SystemClock.elapsedRealtime() - analysisStartedAt
                    sessionStatus.updateAnalysis(analysis.text, analysis.debugText)
                    sessionStatus.updateProgress(
                        "Done. capture=${captureMs / 1000.0}s, analyze=${analysisMs / 1000.0}s",
                    )
                    if (settings.speechEnabled) speechOutput.speak(analysis.text)

                    val multiplier = thermalPolicy.multiplier.first()
                    delay(intervalPolicy.delayMillis(settings.intervalSeconds, multiplier))
                }
            }.onFailure { e ->
                failSession("Session failed", e)
                restoreDoNotDisturb()
                runCatching { camera.disconnect() }
                stopSelf()
            }
        }
    }

    private suspend fun captureBurst(): List<CapturedImage> {
        val frames = camera.captureJpegs(BURST_FRAME_COUNT, BURST_FRAME_DELAY_MS)
        frames.forEachIndexed { index, captured ->
            Log.d(TAG, "Camera burst frame ${index + 1}: ${captured.bytes.size} bytes")
            sessionStatus.updateLastImage(captured.bytes)
        }
        return frames
    }

    private suspend fun coolDownCamera() {
        Log.d(TAG, "Cooling down camera for ${COOLDOWN_MS}ms")
        sessionStatus.updateProgress("Cooling down for ${COOLDOWN_MS / 60_000} minutes...")
        runCatching { camera.disconnect() }
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            val remainingMs = COOLDOWN_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (remainingMs <= 0) break
            val remainingSeconds = ((remainingMs + 999) / 1000).coerceAtLeast(1)
            sessionStatus.updateProgress(
                "Cooling down... ${remainingSeconds / 60}m ${remainingSeconds % 60}s until restart",
            )
            delay(minOf(remainingMs, COOLDOWN_TICK_MS))
        }
    }

    private fun stopSession() {
        captureJob?.cancel()
        captureJob = null
        speechOutput.stop()
        restoreDoNotDisturb()
        wakeLock?.release()
        lifecycleScope.launch { runCatching { camera.disconnect() } }
        sessionStatus.update(SessionState.STOPPED)
        sessionStatus.updateProgress(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failSession(prefix: String, error: Throwable) {
        val msg = error.message ?: error.javaClass.simpleName
        val fullMessage = "$prefix: $msg"
        Log.e(TAG, fullMessage, error)
        sessionStatus.update(SessionState.ERROR(fullMessage))
        sessionStatus.updateProgress(fullMessage)
        getSystemService(NotificationManager::class.java)
            .notify(NotificationFactory.ERROR_ID, notifications.error())
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

    private fun applyDoNotDisturbIfNeeded(enabled: Boolean) {
        if (!enabled) {
            restoreDoNotDisturb()
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.isNotificationPolicyAccessGranted) {
            sessionStatus.updateProgress("DND permission not granted. Continuing without Do Not Disturb.")
            return
        }
        if (previousInterruptionFilter == null) {
            previousInterruptionFilter = manager.currentInterruptionFilter
        }
        if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE) {
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        }
    }

    private fun restoreDoNotDisturb() {
        val previous = previousInterruptionFilter ?: return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.isNotificationPolicyAccessGranted) {
            runCatching { manager.setInterruptionFilter(previous) }
        }
        previousInterruptionFilter = null
    }

    companion object {
        const val ACTION_START = "com.example.studycapturehelper.START"
        const val ACTION_STOP = "com.example.studycapturehelper.STOP"
        private const val BURST_FRAME_COUNT = 5
        private const val BURST_FRAME_DELAY_MS = 150L
        private const val CAMERA_CONNECT_TIMEOUT_MS = 10_000L
        private const val CAPTURE_TIMEOUT_MS = 8_000L
        private const val ACTIVE_WINDOW_MS = 20 * 60 * 1000L
        private const val COOLDOWN_MS = 4 * 60 * 1000L
        private const val COOLDOWN_TICK_MS = 10_000L
    }
}
