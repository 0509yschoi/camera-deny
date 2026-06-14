package com.example.studycapturehelper.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.studycapturehelper.domain.CameraCapture
import com.example.studycapturehelper.domain.CapturedImage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "UvcCameraCapture"
private const val WIDTH = 640
private const val HEIGHT = 480

@Singleton
class UvcCameraCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) : CameraCapture {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera-bg").also { it.start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    @SuppressLint("MissingPermission")
    override suspend fun connect() {
        val cameraId = findExternalOrFirstCamera()
            ?: error("OTG 카메라를 찾을 수 없습니다. USB 연결과 OTG 지원 여부를 확인하세요.")

        device = suspendCoroutine { cont ->
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) = cont.resume(cam)
                override fun onDisconnected(cam: CameraDevice) {
                    cam.close()
                    cont.resumeWithException(IllegalStateException("카메라 연결이 끊겼습니다."))
                }
                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close()
                    cont.resumeWithException(IllegalStateException("카메라 오류: $error"))
                }
            }, handler)
        }

        val reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, 2)
        imageReader = reader

        session = suspendCoroutine { cont ->
            @Suppress("DEPRECATION")
            device!!.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) = cont.resume(s)
                    override fun onConfigureFailed(s: CameraCaptureSession) =
                        cont.resumeWithException(IllegalStateException("세션 구성 실패"))
                },
                handler,
            )
        }

        val request = device!!
            .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            .apply { addTarget(reader.surface) }
            .build()
        session!!.setRepeatingRequest(request, null, handler)
        Log.d(TAG, "카메라 연결 완료: $cameraId")
    }

    override suspend fun captureJpeg(): CapturedImage {
        val reader = checkNotNull(imageReader) { "connect()를 먼저 호출하세요." }
        val bytes = suspendCancellableCoroutine<ByteArray> { cont ->
            reader.setOnImageAvailableListener({ r ->
                r.setOnImageAvailableListener(null, null)
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer: ByteBuffer = image.planes[0].buffer
                val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
                image.close()
                cont.resume(data)
            }, handler)
            cont.invokeOnCancellation { reader.setOnImageAvailableListener(null, null) }
        }
        return CapturedImage(bytes = bytes, mimeType = "image/jpeg")
    }

    override suspend fun disconnect() {
        session?.close(); session = null
        device?.close(); device = null
        imageReader?.close(); imageReader = null
        Log.d(TAG, "카메라 연결 해제")
    }

    private fun findExternalOrFirstCamera(): String? {
        val ids = cameraManager.cameraIdList
        // OTG/USB 외장 카메라 우선
        val external = ids.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL
        }
        if (external != null) return external
        Log.w(TAG, "외장 카메라 없음 — 후면 카메라로 대체")
        return ids.firstOrNull()
    }
}
