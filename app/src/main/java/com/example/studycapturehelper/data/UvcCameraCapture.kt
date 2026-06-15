package com.example.studycapturehelper.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.Surface
import com.example.studycapturehelper.domain.CameraCapture
import com.example.studycapturehelper.domain.CapturedImage
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
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

    private var client: MultiCameraClient? = null
    private var camera: CameraUVC? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    override suspend fun connect() {
        val st = SurfaceTexture(0)
        surfaceTexture = st
        surface = Surface(st)

        val usbDevice = findUsbCamera() ?: error("USB 카메라를 찾을 수 없습니다. 연결을 확인하세요.")

        camera = suspendCoroutine { cont ->
            val cam = CameraUVC(context, usbDevice)
            val request = CameraRequest.Builder()
                .setFrontCamera(false)
                .setHandleGravity(false)
                .create()

            cam.openCamera(surface, object : ICameraStateCallBack {
                override fun onCameraState(
                    self: MultiCameraClient.ICamera,
                    code: ICameraStateCallBack.State,
                    msg: String?,
                ) {
                    when (code) {
                        ICameraStateCallBack.State.OPENED -> {
                            Log.d(TAG, "카메라 열림")
                            cont.resume(cam)
                        }
                        ICameraStateCallBack.State.ERROR -> {
                            cont.resumeWithException(IllegalStateException("카메라 오류: $msg"))
                        }
                        else -> {}
                    }
                }
            }, request)
        }
        Log.d(TAG, "USB 카메라 연결 완료")
    }

    override suspend fun captureJpeg(): CapturedImage {
        val cam = checkNotNull(camera) { "connect()를 먼저 호출하세요." }
        val bytes = suspendCancellableCoroutine<ByteArray> { cont ->
            cam.captureImage(object : ICaptureCallBack {
                override fun onBegin() {}
                override fun onError(error: String?) {
                    cont.resumeWithException(IllegalStateException("캡처 실패: $error"))
                }
                override fun onComplete(path: String?) {
                    // path 방식 대신 bitmap 캡처 사용
                }
            }, null)

            // bitmap 방식으로 캡처
            cam.addPreviewDataCallBack { data, width, height ->
                cam.removePreviewDataCallBack()
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val buf = java.nio.ByteBuffer.wrap(data)
                bmp.copyPixelsFromBuffer(buf)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                bmp.recycle()
                if (cont.isActive) cont.resume(out.toByteArray())
            }
            cont.invokeOnCancellation { cam.removePreviewDataCallBack() }
        }
        return CapturedImage(bytes = bytes, mimeType = "image/jpeg")
    }

    override suspend fun disconnect() {
        camera?.closeCamera()
        camera = null
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        client?.release()
        client = null
        Log.d(TAG, "카메라 연결 해제")
    }

    private fun findUsbCamera(): UsbDevice? {
        val manager = context.getSystemService(Context.USB_SERVICE)
                as android.hardware.usb.UsbManager
        return manager.deviceList.values.firstOrNull { device ->
            // UVC 비디오 클래스 = 0x0E
            device.deviceClass == 0x0E ||
                (0 until device.interfaceCount).any { i ->
                    device.getInterface(i).interfaceClass == 0x0E
                }
        }
    }
}
