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

    fun update(state: SessionState) {
        _state.value = state
    }

    fun updateLastImage(bytes: ByteArray) {
        _lastImageBytes.value = bytes
    }
}
