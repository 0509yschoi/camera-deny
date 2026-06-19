package com.example.studycapturehelper.data

import android.content.Context
import android.content.ContextWrapper
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.example.studycapturehelper.domain.CameraCapture
import com.example.studycapturehelper.domain.CapturedImage
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.usb.USBMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

private const val TAG = "UvcCameraCapture"
private const val CAMERA_WARMUP_MS = 2_000L
private const val CAMERA_OPEN_TIMEOUT_MS = 10_000L
private const val CAMERA_RETRY_DELAY_MS = 800L

@Singleton
class UvcCameraCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) : CameraCapture {

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var camera: CameraUVC? = null
    private var cameraClient: MultiCameraClient? = null
    private val receiverSafeContext = ReceiverSafeContext(context)

    override suspend fun connect() {
        val usbDevice = findUvcDevice()
            ?: error("USB camera not found. Check the OTG cable, camera power, and UVC support.")

        val ctrlBlock = awaitUsbControlBlock(usbDevice)

        val st = SurfaceTexture(0).also { surfaceTexture = it }
        val sf = Surface(st).also { surface = it }

        camera = openCameraWithFallback(usbDevice, ctrlBlock, sf)
        // Give AE/AWB time to settle before the first capture
        delay(CAMERA_WARMUP_MS)
        Log.d(TAG, "USB camera connected")
    }

    override suspend fun captureJpeg(): CapturedImage {
        return captureJpegs(count = 1, delayMillis = 0L).first()
    }

    private suspend fun openCameraWithFallback(
        usbDevice: UsbDevice,
        ctrlBlock: USBMonitor.UsbControlBlock,
        sf: Surface,
    ): CameraUVC {
        val attempts = listOf(
            CameraSize(1920, 1080),
            CameraSize(1920, 1080),
            CameraSize(1280, 720),
            CameraSize(640, 480),
        )
        var lastError: Throwable? = null
        for (size in attempts) {
            val cam = CameraUVC(context, usbDevice)
            cam.setUsbControlBlock(ctrlBlock)
            val request = CameraRequest.Builder()
                .setFrontCamera(false)
                .setPreviewWidth(size.width)
                .setPreviewHeight(size.height)
                .create()
            val result = runCatching {
                openCameraOnce(cam, sf, request, "${size.width}x${size.height}")
            }
            result.onSuccess { return it }
            lastError = result.exceptionOrNull()
            Log.w(TAG, "Open camera failed at ${size.width}x${size.height}", lastError)
            runCatching { cam.closeCamera() }
            delay(CAMERA_RETRY_DELAY_MS)
        }
        throw IllegalStateException(
            "Camera open failed for all preview sizes. Try unplugging and reconnecting the OTG camera.",
            lastError,
        )
    }

    private suspend fun openCameraOnce(
        cam: CameraUVC,
        sf: Surface,
        request: CameraRequest,
        label: String,
    ): CameraUVC = withTimeout(CAMERA_OPEN_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            cam.setCameraStateCallBack(object : ICameraStateCallBack {
                override fun onCameraState(
                    self: MultiCameraClient.ICamera,
                    code: ICameraStateCallBack.State,
                    msg: String?,
                ) {
                    Log.d(TAG, "Camera state($label): $code / $msg")
                    when (code) {
                        ICameraStateCallBack.State.OPENED -> {
                            if (cont.isActive) cont.resume(cam)
                        }
                        ICameraStateCallBack.State.ERROR -> {
                            if (cont.isActive) {
                                cont.resumeWithException(IllegalStateException("Camera error: $msg"))
                            }
                        }
                        else -> {}
                    }
                }
            })
            cont.invokeOnCancellation {
                runCatching { cam.closeCamera() }
            }
            cam.openCamera(sf, request)
        }
    }

    override suspend fun captureJpegs(count: Int, delayMillis: Long): List<CapturedImage> {
        val cam = checkNotNull(camera) { "Call connect() before captureJpeg()." }
        val safeCount = count.coerceAtLeast(1)
        return suspendCancellableCoroutine { cont ->
            val frames = mutableListOf<CapturedImage>()
            var lastFrameAt = 0L
            val cb = object : IPreviewDataCallBack {
                override fun onPreviewData(
                    data: ByteArray?,
                    width: Int,
                    height: Int,
                    format: IPreviewDataCallBack.DataFormat,
                ) {
                    if (data == null || !cont.isActive) return
                    val now = SystemClock.elapsedRealtime()
                    if (frames.isNotEmpty() && now - lastFrameAt < delayMillis) return

                    runCatching {
                        CapturedImage(
                            bytes = encodePreviewJpeg(data, width, height, format),
                            mimeType = "image/jpeg",
                        )
                    }.onSuccess { image ->
                        frames += image
                        lastFrameAt = now
                        if (frames.size >= safeCount && cont.isActive) {
                            cam.removePreviewDataCallBack(this)
                            cont.resume(frames.toList())
                        }
                    }.onFailure { error ->
                        if (cont.isActive) {
                            cam.removePreviewDataCallBack(this)
                            cont.resumeWithException(error)
                        }
                    }
                }
            }
            cam.addPreviewDataCallBack(cb)
            cont.invokeOnCancellation { cam.removePreviewDataCallBack(cb) }
        }
    }

    private fun encodePreviewJpeg(
        data: ByteArray,
        width: Int,
        height: Int,
        format: IPreviewDataCallBack.DataFormat,
    ): ByteArray {
        return when (format) {
            IPreviewDataCallBack.DataFormat.NV21 -> {
                val yuvImage = android.graphics.YuvImage(
                    data,
                    android.graphics.ImageFormat.NV21,
                    width,
                    height,
                    null,
                )
                ByteArrayOutputStream().also { out ->
                    yuvImage.compressToJpeg(
                        android.graphics.Rect(0, 0, width, height),
                        95,
                        out,
                    )
                }.toByteArray()
            }
            IPreviewDataCallBack.DataFormat.RGBA -> {
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { b ->
                    b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(data))
                }
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                bmp.recycle()
                out.toByteArray()
            }
        }
    }

    override suspend fun disconnect() {
        camera?.closeCamera()
        camera = null
        cameraClient?.unRegister()
        cameraClient?.destroy()
        cameraClient = null
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        Log.d(TAG, "USB camera disconnected")
    }

    private fun findUvcDevice(): UsbDevice? {
        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = mgr.deviceList.values
        if (devices.isEmpty()) {
            Log.w(TAG, "No USB devices visible to UsbManager")
        }
        devices.forEach { dev ->
            Log.d(
                TAG,
                "USB device ${dev.deviceName}: class=${dev.deviceClass}, " +
                    "vendor=${dev.vendorId}, product=${dev.productId}, " +
                    "interfaces=${dev.interfaceCount}, hasPermission=${mgr.hasPermission(dev)}",
            )
            for (i in 0 until dev.interfaceCount) {
                val intf = dev.getInterface(i)
                Log.d(
                    TAG,
                    "  interface[$i]: class=${intf.interfaceClass}, " +
                        "subclass=${intf.interfaceSubclass}, protocol=${intf.interfaceProtocol}",
                )
            }
        }
        return devices.firstOrNull { dev ->
            isSupportedUvcDevice(dev)
        }
    }

    private fun isSupportedUvcDevice(dev: UsbDevice): Boolean {
        if (dev.deviceClass == 0x0E) return true
        if (dev.deviceClass == 0xEF && dev.deviceSubclass == 0x02) return true

        return (0 until dev.interfaceCount).any { i ->
            val intf = dev.getInterface(i)
            intf.interfaceClass == 0x0E ||
                (intf.interfaceClass == 0xEF && intf.interfaceSubclass == 0x02)
        }
    }

    private suspend fun awaitUsbControlBlock(device: UsbDevice): USBMonitor.UsbControlBlock =
        withTimeout(10_000) {
            suspendCancellableCoroutine { cont ->
                val client = MultiCameraClient(
                    receiverSafeContext,
                    object : IDeviceConnectCallBack {
                        override fun onAttachDev(dev: UsbDevice?) {
                            Log.d(TAG, "USB attached: ${dev?.deviceName}")
                        }

                        override fun onDetachDec(dev: UsbDevice?) {
                            if (dev == null) return
                            if (isSameDevice(dev, device) && cont.isActive) {
                                cont.resumeWithException(
                                    IllegalStateException("USB camera detached before connection."),
                                )
                            }
                        }

                        override fun onConnectDev(
                            dev: UsbDevice?,
                            ctrlBlock: USBMonitor.UsbControlBlock?,
                        ) {
                            if (dev == null || ctrlBlock == null) return
                            if (!isSameDevice(dev, device) || !cont.isActive) return
                            Log.d(TAG, "USB control block ready for ${dev.deviceName}")
                            cont.resume(ctrlBlock)
                        }

                        override fun onDisConnectDec(
                            dev: UsbDevice?,
                            ctrlBlock: USBMonitor.UsbControlBlock?,
                        ) {
                            Log.d(TAG, "USB disconnected: ${dev?.deviceName}")
                        }

                        override fun onCancelDev(dev: UsbDevice?) {
                            if (dev == null) return
                            if (isSameDevice(dev, device) && cont.isActive) {
                                cont.resumeWithException(
                                    SecurityException("USB permission denied for ${dev.deviceName}"),
                                )
                            }
                        }
                    },
                )
                cameraClient = client
                client.register()
                cont.invokeOnCancellation {
                    if (cameraClient === client) {
                        client.unRegister()
                        client.destroy()
                        cameraClient = null
                    }
                }
                if (!client.requestPermission(device) && cont.isActive) {
                    cont.resumeWithException(
                        IllegalStateException("USB monitor is not registered."),
                    )
                }
            }
        }

    private fun isSameDevice(a: UsbDevice, b: UsbDevice): Boolean {
        return a.deviceName == b.deviceName &&
            a.vendorId == b.vendorId &&
            a.productId == b.productId
    }

    private data class CameraSize(
        val width: Int,
        val height: Int,
    )
}

private class ReceiverSafeContext(base: Context) : ContextWrapper(base) {
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            super.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            super.registerReceiver(receiver, filter)
        }
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        broadcastPermission: String?,
        scheduler: Handler?,
    ): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            super.registerReceiver(
                receiver,
                filter,
                broadcastPermission,
                scheduler,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            super.registerReceiver(receiver, filter, broadcastPermission, scheduler)
        }
    }
}
