package dev.robotbrain.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean

class CameraPublisher(context: Context, private val onError: (String) -> Unit) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val active = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    @SuppressLint("MissingPermission")
    fun start(capability: PhoneCapability, rateHz: Double) {
        check(active.compareAndSet(false, true)) { "Only one camera can publish at a time in v0.1." }
        val cameraId = requireNotNull(capability.cameraId)
        try {
            val sizes = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG).orEmpty()
            val size = sizes.filter { it.width <= 640 && it.height <= 480 }.maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height }
                ?: error("Camera has no JPEG capture size")
            thread = HandlerThread("RobotBrainCamera").also { it.start() }
            handler = Handler(requireNotNull(thread).looper)
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ source ->
                    val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    try {
                        if (active.get()) {
                            val buffer = image.planes[0].buffer
                            val data = ByteArray(buffer.remaining())
                            buffer.get(data)
                            NativeRuntime.publishImage(capability.id, System.currentTimeMillis() * 1_000_000L, data)
                        }
                    } finally { image.close() }
                }, handler)
            }
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!active.get()) { device.close(); return }
                    camera = device
                    val surface = reader?.surface ?: run { device.close(); return }
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(captureSession: CameraCaptureSession) {
                            if (!active.get()) { captureSession.close(); return }
                            session = captureSession
                            val capture = object : Runnable {
                                override fun run() {
                                    if (!active.get()) return
                                    try {
                                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(surface)
                                            set(CaptureRequest.JPEG_QUALITY, 65.toByte())
                                        }.build()
                                        captureSession.capture(request, null, handler)
                                        handler?.postDelayed(this, (1000.0 / rateHz).toLong())
                                    } catch (e: Exception) { fail(e.message ?: "Camera capture failed") }
                                }
                            }
                            handler?.post(capture)
                        }
                        override fun onConfigureFailed(captureSession: CameraCaptureSession) { fail("Camera session configuration failed") }
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); fail("Camera disconnected") }
                override fun onError(device: CameraDevice, error: Int) { device.close(); fail("Camera error $error") }
            }, handler)
        } catch (e: Exception) { stop(); throw e }
    }

    private fun fail(message: String) {
        if (active.getAndSet(false)) onError(message)
    }

    fun stop() {
        active.set(false)
        handler?.removeCallbacksAndMessages(null)
        session?.close(); session = null
        camera?.close(); camera = null
        reader?.close(); reader = null
        thread?.quitSafely(); thread = null
        handler = null
    }
}
