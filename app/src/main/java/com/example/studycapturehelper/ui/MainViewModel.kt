package com.example.studycapturehelper.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.studycapturehelper.domain.AppUpdate
import com.example.studycapturehelper.domain.AppUpdateRepository
import com.example.studycapturehelper.domain.CaptureSettings
import com.example.studycapturehelper.domain.CapturedImage
import com.example.studycapturehelper.domain.ImageAnalyzer
import com.example.studycapturehelper.domain.SessionState
import com.example.studycapturehelper.domain.SettingsRepository
import com.example.studycapturehelper.domain.SpeechOutput
import com.example.studycapturehelper.domain.UpdateState
import com.example.studycapturehelper.service.SessionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val appUpdateRepository: AppUpdateRepository,
    private val imageAnalyzer: ImageAnalyzer,
    private val speechOutput: SpeechOutput,
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

    private val _analyzeState = MutableStateFlow<String?>(null)
    val analyzeState: StateFlow<String?> = _analyzeState.asStateFlow()

    fun analyzeSharedImage(uri: Uri) {
        viewModelScope.launch {
            _analyzeState.value = "분석 중..."
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                    ?: error("이미지를 읽을 수 없습니다.")
                val image = CapturedImage(bytes = bytes, mimeType = "image/jpeg")
                val result = imageAnalyzer.analyze(image)
                _analyzeState.value = result.text
                speechOutput.speak(result.text)
            }.onFailure {
                _analyzeState.value = "오류: ${it.message}"
            }
        }
    }
}
