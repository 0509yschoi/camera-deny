package com.example.studycapturehelper.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import com.example.studycapturehelper.domain.CameraCapture
import com.example.studycapturehelper.domain.CapturedImage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "UvcCameraCapture"

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
    private var useYuv = false

    @SuppressLint("MissingPermission")
    override suspend fun connect() {
        val cameraId = findExternalOrFirstCamera()
            ?: error("USB 카메라를 찾을 수 없습니다.")

        device = suspendCoroutine { cont ->
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) = cont.resume(cam)
                override fun onDisconnected(cam: CameraDevice) {
                    cam.close()
                    cont.resumeWithException(IllegalStateException("카메라 연결이 끊겼습니다."))
                }
                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close()
                    cont.resumeWithException(IllegalStateException("카메라 오류 코드: $error"))
                }
            }, handler)
        }

        val (format, width, height) = bestFormat(cameraId)
        useYuv = (format == ImageFormat.YUV_420_888)
        Log.d(TAG, "포맷: $format, 해상도: ${width}x${height}")

        val reader = ImageReader.newInstance(width, height, format, 2)
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
                try {
                    val data = if (useYuv) yuvToJpeg(image) else jpegBytes(image)
                    cont.resume(data)
                } finally {
                    image.close()
                }
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

    private fun jpegBytes(image: android.media.Image): ByteArray {
        val buffer = image.planes[0].buffer
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun yuvToJpeg(image: android.media.Image): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val ySize = yPlane.buffer.remaining()
        val uSize = uPlane.buffer.remaining()
        val vSize = vPlane.buffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yPlane.buffer.get(nv21, 0, ySize)
        vPlane.buffer.get(nv21, ySize, vSize)
        uPlane.buffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 95, out)
        return out.toByteArray()
    }

    private fun bestFormat(cameraId: String): Triple<Int, Int, Int> {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        // JPEG 우선 시도
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
        if (jpegSizes.isNotEmpty()) {
            val best = jpegSizes.maxByOrNull { it.width.toLong() * it.height }!!
            return Triple(ImageFormat.JPEG, best.width, best.height)
        }

        // YUV 폴백
        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
        if (yuvSizes.isNotEmpty()) {
            val best = yuvSizes.maxByOrNull { it.width.toLong() * it.height }!!
            return Triple(ImageFormat.YUV_420_888, best.width, best.height)
        }

        return Triple(ImageFormat.JPEG, 1280, 720)
    }

    private fun findExternalOrFirstCamera(): String? {
        val ids = cameraManager.cameraIdList
        val facingNames = mapOf(0 to "후면", 1 to "전면", 2 to "외장(USB)")
        val summary = ids.joinToString { id ->
            val f = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            "$id=${facingNames[f] ?: f}"
        }
        Log.d(TAG, "카메라 목록: $summary")

        val external = ids.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL
        }
        if (external != null) {
            Log.d(TAG, "USB 외장 카메라 사용: $external")
            return external
        }
        Log.w(TAG, "USB 카메라 없음(목록: $summary) — 폰 카메라로 대체")
        error("USB 카메라가 Camera2에서 감지되지 않습니다. 감지된 카메라: $summary")
    }
}
