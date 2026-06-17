package com.example.studycapturehelper.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.Surface
import com.example.studycapturehelper.domain.CameraCapture
import com.example.studycapturehelper.domain.CapturedImage
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
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

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var camera: CameraUVC? = null

    override suspend fun connect() {
        val usbDevice = findUvcDevice()
            ?: error("USB 카메라를 찾을 수 없습니다. OTG 연결을 확인하세요.")

        val st = SurfaceTexture(0).also { surfaceTexture = it }
        val sf = Surface(st).also { surface = it }

        val cam = CameraUVC(context, usbDevice)
        cam.setCameraStateCallBack(object : ICameraStateCallBack {
            override fun onCameraState(
                self: MultiCameraClient.ICamera,
                code: ICameraStateCallBack.State,
                msg: String?,
            ) {
                Log.d(TAG, "카메라 상태: $code / $msg")
            }
        })

        val request = CameraRequest.Builder()
            .setFrontCamera(false)
            .create()

        camera = suspendCoroutine { cont ->
            cam.setCameraStateCallBack(object : ICameraStateCallBack {
                override fun onCameraState(
                    self: MultiCameraClient.ICamera,
                    code: ICameraStateCallBack.State,
                    msg: String?,
                ) {
                    when (code) {
                        ICameraStateCallBack.State.OPENED -> cont.resume(cam)
                        ICameraStateCallBack.State.ERROR ->
                            cont.resumeWithException(IllegalStateException("카메라 오류: $msg"))
                        else -> {}
                    }
                }
            })
            cam.openCamera(sf, request)
        }
        Log.d(TAG, "USB 카메라 연결 완료")
    }

    override suspend fun captureJpeg(): CapturedImage {
        val cam = checkNotNull(camera) { "connect()를 먼저 호출하세요." }
        val bytes = suspendCancellableCoroutine<ByteArray> { cont ->
            val cb = object : IPreviewDataCallBack {
                override fun onPreviewData(
                    data: ByteArray?,
                    width: Int,
                    height: Int,
                    format: IPreviewDataCallBack.DataCallBackType,
                ) {
                    cam.removePreviewDataCallBack(this)
                    if (data == null || !cont.isActive) return
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(data))
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    bmp.recycle()
                    cont.resume(out.toByteArray())
                }
            }
            cam.addPreviewDataCallBack(cb)
            cont.invokeOnCancellation { cam.removePreviewDataCallBack(cb) }
        }
        return CapturedImage(bytes = bytes, mimeType = "image/jpeg")
    }

    override suspend fun disconnect() {
        camera?.closeCamera()
        camera = null
        surface?.release(); surface = null
        surfaceTexture?.release(); surfaceTexture = null
        Log.d(TAG, "카메라 연결 해제")
    }

    private fun findUvcDevice(): UsbDevice? {
        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return mgr.deviceList.values.firstOrNull { dev ->
            dev.deviceClass == 0x0E || (0 until dev.interfaceCount).any { i ->
                dev.getInterface(i).interfaceClass == 0x0E
            }
        }
    }
}
