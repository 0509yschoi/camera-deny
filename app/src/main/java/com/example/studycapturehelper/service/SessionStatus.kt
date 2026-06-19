package com.example.studycapturehelper.service

import com.example.studycapturehelper.domain.SessionState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class SessionStatus @Inject constructor() {
    private val _state = MutableStateFlow<SessionState>(SessionState.STOPPED)
    val state: StateFlow<SessionState> = _state

    private val _lastImageBytes = MutableStateFlow<ByteArray?>(null)
    val lastImageBytes: StateFlow<ByteArray?> = _lastImageBytes

    private val _lastAnalysisText = MutableStateFlow<String?>(null)
    val lastAnalysisText: StateFlow<String?> = _lastAnalysisText

    private val _lastDebugText = MutableStateFlow<String?>(null)
    val lastDebugText: StateFlow<String?> = _lastDebugText

    private val _progressText = MutableStateFlow<String?>(null)
    val progressText: StateFlow<String?> = _progressText

    fun update(state: SessionState) {
        _state.value = state
    }

    fun updateLastImage(bytes: ByteArray) {
        _lastImageBytes.value = bytes
    }

    fun updateAnalysis(text: String, debugText: String?) {
        _lastAnalysisText.value = text
        _lastDebugText.value = debugText
    }

    fun updateProgress(text: String?) {
        _progressText.value = text
    }
}
