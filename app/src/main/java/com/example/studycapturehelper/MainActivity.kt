package com.example.studycapturehelper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.studycapturehelper.domain.SessionState
import com.example.studycapturehelper.domain.AppUpdate
import com.example.studycapturehelper.domain.UpdateState
import com.example.studycapturehelper.service.CaptureForegroundService
import com.example.studycapturehelper.ui.MainViewModel
import com.example.studycapturehelper.update.UpdateDownloadManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var updateDownloadManager: UpdateDownloadManager
    private var pendingUpdate: AppUpdate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CaptureScreen(
                    viewModel = viewModel,
                    onStart = ::requestPermissionsAndStart,
                    onStop = ::stopSession,
                    onCheckUpdate = viewModel::checkForUpdate,
                    onDownloadUpdate = ::requestInstallPermissionAndDownload,
                )
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            startSession()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) startSession()
    }

    private fun startSession() {
        val intent = Intent(this, CaptureForegroundService::class.java)
            .setAction(CaptureForegroundService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopSession() {
        stopService(Intent(this, CaptureForegroundService::class.java))
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (packageManager.canRequestPackageInstalls()) {
            pendingUpdate?.let(::downloadUpdate)
        }
        pendingUpdate = null
    }

    private fun requestInstallPermissionAndDownload(update: AppUpdate) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingUpdate = update
            installPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                ),
            )
        } else {
            downloadUpdate(update)
        }
    }

    private fun downloadUpdate(update: AppUpdate) {
        updateDownloadManager.enqueue(update)
        viewModel.markDownloadStarted(update)
    }
}

@Composable
private fun CaptureScreen(
    viewModel: MainViewModel,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: (AppUpdate) -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val state by viewModel.sessionState.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val running = state == SessionState.RUNNING
    var intervalValue by remember(settings.intervalSeconds) {
        mutableFloatStateOf(settings.intervalSeconds.toFloat())
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Study Capture Helper", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            Text("Interval: ${intervalValue.toInt()} seconds")
            Slider(
                value = intervalValue,
                onValueChange = { intervalValue = it },
                onValueChangeFinished = { viewModel.setInterval(intervalValue.toInt()) },
                valueRange = 15f..300f,
                enabled = !running,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Read analysis aloud")
                Switch(
                    checked = settings.speechEnabled,
                    onCheckedChange = viewModel::setSpeechEnabled,
                    enabled = !running,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = if (running) onStop else onStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) "Stop visible session" else "Start visible session")
            }
            if (state == SessionState.ERROR) {
                Spacer(Modifier.height(12.dp))
                Text("Session stopped. Check USB camera and backend connection.")
            }
            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))
            Text("앱 업데이트", style = MaterialTheme.typography.titleMedium)
            Text(
                "현재 버전 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            UpdateSection(
                state = updateState,
                onCheck = onCheckUpdate,
                onDownload = onDownloadUpdate,
            )
        }
    }
}

@Composable
private fun UpdateSection(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: (AppUpdate) -> Unit,
) {
    when (state) {
        UpdateState.Idle -> Button(onClick = onCheck) { Text("업데이트 확인") }
        UpdateState.Checking -> Text("새 버전을 확인하는 중...")
        UpdateState.UpToDate -> {
            Text("최신 버전입니다.")
            Button(onClick = onCheck) { Text("다시 확인") }
        }
        UpdateState.NotConfigured -> {
            Text("GitHub 저장소가 설정되지 않은 빌드입니다.")
        }
        is UpdateState.Available -> {
            Text("새 버전 ${state.update.versionName} 사용 가능")
            if (state.update.releaseNotes.isNotBlank()) {
                Text(state.update.releaseNotes, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { onDownload(state.update) }) {
                Text("다운로드 후 설치")
            }
        }
        is UpdateState.Downloading -> {
            Text("${state.update.versionName} 다운로드 중")
            Text("완료 알림을 눌러 설치하세요.", style = MaterialTheme.typography.bodySmall)
        }
        is UpdateState.Error -> {
            Text(state.message)
            Button(onClick = onCheck) { Text("다시 시도") }
        }
    }
}
