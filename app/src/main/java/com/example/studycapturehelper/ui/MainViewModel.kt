package com.example.studycapturehelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.studycapturehelper.domain.AppUpdate
import com.example.studycapturehelper.domain.AppUpdateRepository
import com.example.studycapturehelper.domain.CaptureSettings
import com.example.studycapturehelper.domain.SessionState
import com.example.studycapturehelper.domain.SettingsRepository
import com.example.studycapturehelper.domain.UpdateState
import com.example.studycapturehelper.service.SessionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val appUpdateRepository: AppUpdateRepository,
    sessionStatus: SessionStatus,
) : ViewModel() {
    val settings: StateFlow<CaptureSettings> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CaptureSettings(),
    )
    val sessionState: StateFlow<SessionState> = sessionStatus.state
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    init {
        checkForUpdate()
    }

    fun setInterval(seconds: Int) {
        viewModelScope.launch { settingsRepository.setIntervalSeconds(seconds) }
    }

    fun setSpeechEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSpeechEnabled(enabled) }
    }

    fun checkForUpdate() {
        if (_updateState.value == UpdateState.Checking) return
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            _updateState.value = appUpdateRepository.checkForUpdate()
        }
    }

    fun markDownloadStarted(update: AppUpdate) {
        _updateState.value = UpdateState.Downloading(update)
    }
}
