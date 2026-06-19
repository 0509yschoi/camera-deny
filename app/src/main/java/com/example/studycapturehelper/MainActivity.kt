package com.example.studycapturehelper

import android.Manifest
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.studycapturehelper.domain.AppUpdate
import com.example.studycapturehelper.domain.SessionState
import com.example.studycapturehelper.domain.UpdateState
import com.example.studycapturehelper.service.CaptureForegroundService
import com.example.studycapturehelper.ui.MainViewModel
import com.example.studycapturehelper.update.UpdateDownloadManager
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var updateDownloadManager: UpdateDownloadManager
    private var pendingUpdate: AppUpdate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            MaterialTheme {
                CaptureScreen(
                    viewModel = viewModel,
                    onStart = ::requestPermissionsAndStart,
                    onStop = ::stopSession,
                    onCheckUpdate = viewModel::checkForUpdate,
                    onDownloadUpdate = ::requestInstallPermissionAndDownload,
                    onSaveImage = ::saveCapturedImage,
                    onOpenDndSettings = ::openDndSettings,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    uri?.let { viewModel.analyzeSharedImage(it) }
                }
            }
            android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                requestPermissionsAndStart()
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
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

    private fun openDndSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    private fun saveCapturedImage(bytes: ByteArray) {
        val fileName = "StudyCapture_${SAVE_FORMAT.format(Date())}.jpg"
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/StudyCaptureHelper",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "StudyCaptureHelper",
                )
                directory.mkdirs()
                put(MediaStore.Images.Media.DATA, File(directory, fileName).absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "이미지 저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Output stream is unavailable.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        }.onSuccess {
            Toast.makeText(this, "캡처 이미지를 저장했습니다.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            resolver.delete(uri, null, null)
            Toast.makeText(this, "이미지 저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
        }
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

    private companion object {
        val SAVE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}

@Composable
private fun CaptureScreen(
    viewModel: MainViewModel,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: (AppUpdate) -> Unit,
    onSaveImage: (ByteArray) -> Unit,
    onOpenDndSettings: () -> Unit,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val state by viewModel.sessionState.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val analyzeState by viewModel.analyzeState.collectAsState()
    val lastAnalysisText by viewModel.lastAnalysisText.collectAsState()
    val lastDebugText by viewModel.lastDebugText.collectAsState()
    val progressText by viewModel.progressText.collectAsState()
    val lastImageBytes by viewModel.lastImageBytes.collectAsState()
    var showPreview by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    val running = state is SessionState.RUNNING
    val visibleAnalysis = lastAnalysisText ?: analyzeState
    val dndAccessGranted = context.getSystemService(NotificationManager::class.java)
        .isNotificationPolicyAccessGranted
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
            Text("촬영 간격: ${intervalValue.toInt()}초")
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
                Text("분석 결과 음성 읽기")
                Switch(
                    checked = settings.speechEnabled,
                    onCheckedChange = viewModel::setSpeechEnabled,
                    enabled = !running,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("세션 중 방해금지")
                Switch(
                    checked = settings.dndEnabled,
                    onCheckedChange = viewModel::setDndEnabled,
                    enabled = !running,
                )
            }
            if (settings.dndEnabled && !dndAccessGranted) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onOpenDndSettings,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running,
                ) {
                    Text("방해금지 권한 설정")
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = if (running) onStop else onStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) "세션 중지" else "세션 시작")
            }
            if (state is SessionState.ERROR) {
                Spacer(Modifier.height(12.dp))
                Text("오류: ${(state as SessionState.ERROR).message}")
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showPreview = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = lastImageBytes != null,
            ) {
                Text("화면 확인")
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { lastImageBytes?.let(onSaveImage) },
                modifier = Modifier.fillMaxWidth(),
                enabled = lastImageBytes != null,
            ) {
                Text("캡처 저장")
            }
            progressText?.let { progress ->
                Spacer(Modifier.height(12.dp))
                Text(progress, style = MaterialTheme.typography.bodySmall)
            }
            visibleAnalysis?.let { result ->
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("분석 결과", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(result, style = MaterialTheme.typography.bodyLarge)
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showDebug = !showDebug },
                modifier = Modifier.fillMaxWidth(),
                enabled = lastDebugText != null,
            ) {
                Text(if (showDebug) "Hide OCR Debug" else "Show OCR Debug")
            }
            if (showDebug && lastDebugText != null) {
                Spacer(Modifier.height(8.dp))
                Text("OCR Debug", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(lastDebugText!!, style = MaterialTheme.typography.bodySmall)
            }

            if (showPreview && lastImageBytes != null) {
                Dialog(onDismissRequest = { showPreview = false }) {
                    Box(
                        Modifier.background(Color.Black).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap = remember(lastImageBytes) {
                            BitmapFactory.decodeByteArray(lastImageBytes, 0, lastImageBytes!!.size)
                        }
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "캡처 이미지",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TextButton(
                            onClick = { showPreview = false },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        ) { Text("닫기", color = Color.White) }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))
            Text("업데이트", style = MaterialTheme.typography.titleMedium)
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
            Text("다운로드가 끝나면 설치 화면이 자동으로 열립니다.", style = MaterialTheme.typography.bodySmall)
        }
        is UpdateState.Error -> {
            Text(state.message)
            Button(onClick = onCheck) { Text("다시 시도") }
        }
    }
}
