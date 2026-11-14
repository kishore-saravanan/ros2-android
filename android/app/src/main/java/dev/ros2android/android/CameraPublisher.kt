package dev.ros2android.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Continuous YUV capture -> bounded JPEG encoder -> bounded DDS publisher. No images are saved. */
class CameraPublisher(
    context: Context,
    private val onPreview: (String, ByteArray, Long) -> Unit = { _, _, _ -> },
    private val onError: (String) -> Unit,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    @Volatile var previewEnabled = false
    @Volatile private var current: Stream? = null
    private val retiring = mutableListOf<Stream>()

    data class Stats(
        val width: Int,
        val height: Int,
        val requestedFps: Double,
        val sensorFps: Int,
        val captured: Long,
        val throttled: Long,
        val dropped: Long,
        val encoded: Long,
        val published: Long,
        val failures: Long,
        val captureFps: Double,
        val publishFps: Double,
        val encodeMs: Double,
        val clock: String,
        val jpegQuality: Int = 65,
    ) {
        fun label(): String =
            String.format(
                Locale.US,
                "%d×%d · requested %s fps · capture %.1f · publish %.1f fps\n" +
                    "%d sent · %d pipeline drops · %d rate skips · JPEG %.1f ms · quality " +
                    "%d\n%s",
                width,
                height,
                if (requestedFps == 0.0) "auto" else "%.1f".format(Locale.US, requestedFps),
                captureFps,
                publishFps,
                published,
                dropped,
                throttled,
                encodeMs,
                jpegQuality,
                clock,
            )
    }

    private data class RawFrame(val bytes: ByteArray, val timestamp: Long)

    private data class JpegFrame(val bytes: ByteArray, val timestamp: Long)

    @Synchronized
    @SuppressLint("MissingPermission")
    fun start(
        capability: PhoneCapability,
        rateHz: Double,
        options: CameraOptions = CameraOptions(),
    ) {
        require(rateHz.isFinite() && (rateHz == 0.0 || rateHz >= 0.2)) {
            "Use 0 for automatic camera FPS, or at least 0.2 fps"
        }

        check(current == null) { "Stop the active camera before starting another." }
        val id = requireNotNull(capability.cameraId)
        val characteristics = manager.getCameraCharacteristics(id)
        val map =
            requireNotNull(
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            )
        val sizes =
            map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().filter {
                it.width % 2 == 0 && it.height % 2 == 0
            }
        val size =
            if (options.width > 0) {
                sizes.firstOrNull { it.width == options.width && it.height == options.height }
                    ?: error("Selected camera resolution is not supported")
            } else {
                sizes
                    .filter { it.width <= 640 && it.height <= 480 }
                    .maxByOrNull { it.width * it.height }
                    ?: sizes.minByOrNull { it.width * it.height }
                    ?: error("Camera has no supported YUV capture size")
            }
        val ranges =
            characteristics
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                .orEmpty()
        val minDuration = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size)
        val streamMax =
            if (minDuration > 0) 1_000_000_000.0 / minDuration else Double.POSITIVE_INFINITY
        val usable = ranges.filter { it.upper <= streamMax + 0.01 }.ifEmpty { ranges.toList() }
        val chosen = CameraRatePolicy.select(usable.map { it.lower to it.upper }, rateHz)
        val fps = Range(chosen.first, chosen.second)
        require(size.width.toLong() * size.height * 12 <= Runtime.getRuntime().maxMemory() / 2) {
            "Selected resolution exceeds this app's camera memory budget; choose a lower resolution"
        }

        val realtime =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val stream =
            Stream(
                capability.id,
                size.width,
                size.height,
                rateHz,
                fps,
                realtime,
                options.jpegQuality,
            )
        current = stream

        try {
            stream.startWorkers()
            stream.reader.setOnImageAvailableListener(
                { source -> stream.acquire(source) },
                stream.handler,
            )
            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        synchronized(stream) {
                            if (!stream.running.get()) {
                                device.close()
                                return
                            }

                            stream.camera = device
                            @Suppress("DEPRECATION")
                            device.createCaptureSession(
                                listOf(stream.reader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        synchronized(stream) {
                                            if (!stream.running.get()) {
                                                session.close()
                                                return
                                            }

                                            stream.session = session

                                            try {
                                                val request =
                                                    device
                                                        .createCaptureRequest(
                                                            CameraDevice.TEMPLATE_PREVIEW
                                                        )
                                                        .apply {
                                                            addTarget(stream.reader.surface)
                                                            set(
                                                                CaptureRequest
                                                                    .CONTROL_AE_TARGET_FPS_RANGE,
                                                                fps,
                                                            )
                                                        }
                                                        .build()
                                                session.setRepeatingRequest(
                                                    request,
                                                    null,
                                                    stream.handler,
                                                )
                                            } catch (e: Exception) {
                                                stream.fail(e)
                                            }
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        session.close()
                                        stream.fail(
                                            IllegalStateException(
                                                "Camera stream configuration failed"
                                            )
                                        )
                                    }
                                },
                                stream.handler,
                            )
                        }
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        stream.fail(IllegalStateException("Camera disconnected"))
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        stream.fail(IllegalStateException("Camera error $error"))
                    }
                },
                stream.handler,
            )
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun stats(): Stats? = current?.stats()

    @Synchronized
    fun stop() {
        current?.let { stream ->
            current = null
            stream.close()
            retiring += stream
        }
    }

    /**
     * Call on the ROS control worker before destroying/reusing publisher IDs, never on the UI
     * thread.
     */
    @Synchronized
    fun awaitStopped() {
        retiring.forEach { it.awaitStopped() }
        retiring.clear()
    }

    companion object {
        fun fpsRanges(context: Context, cameraId: String): List<Pair<Int, Int>> =
            context
                .getSystemService(CameraManager::class.java)
                .getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                .orEmpty()
                .map { it.lower to it.upper }

        fun resolutions(context: Context, cameraId: String): List<android.util.Size> =
            context
                .getSystemService(CameraManager::class.java)
                .getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                .orEmpty()
                .filter { it.width % 2 == 0 && it.height % 2 == 0 }
                .distinctBy { it.width to it.height }
                .sortedBy { it.width.toLong() * it.height }
    }

    private inner class Stream(
        val id: String,
        val width: Int,
        val height: Int,
        val rate: Double,
        val fps: Range<Int>,
        val realtime: Boolean,
        val quality: Int,
    ) {
        val running = AtomicBoolean(true)
        private val errorReported = AtomicBoolean(false)
        private val captureThread = HandlerThread("ROS2CameraCapture").apply { start() }
        val handler = Handler(captureThread.looper)
        val reader: ImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        private val free =
            ArrayBlockingQueue<ByteArray>(3).apply {
                repeat(3) { add(ByteArray(width * height * 3 / 2)) }
            }
        private val raw = ArrayBlockingQueue<RawFrame>(1)
        private val jpeg = ArrayBlockingQueue<JpegFrame>(1)
        private val captured = AtomicLong()
        private val throttled = AtomicLong()
        private val dropped = AtomicLong()
        private val encoded = AtomicLong()
        private val published = AtomicLong()
        private val failures = AtomicLong()
        private val encodeNanos = AtomicLong()
        private val clock =
            CameraClock(
                realtime,
                System.currentTimeMillis() * 1_000_000L - SystemClock.elapsedRealtimeNanos(),
            )
        private val interval = if (rate == 0.0) 0L else (1_000_000_000.0 / rate).toLong()
        private var nextFrame = 0L
        private var lastTime = SystemClock.elapsedRealtimeNanos()
        private var lastCaptured = 0L
        private var lastPublished = 0L
        private var captureRate = 0.0
        private var publishRate = 0.0
        private val encoder = Thread({ encodeLoop() }, "ROS2CameraEncoder")
        private val publisher = Thread({ publishLoop() }, "ROS2CameraPublisher")

        fun startWorkers() {
            encoder.start()
            publisher.start()
        }

        fun acquire(source: ImageReader) {
            if (!running.get()) return

            try {
                val image = source.acquireLatestImage() ?: return
                image.use {
                    if (!running.get()) return
                    captured.incrementAndGet()
                    val sensorTime = image.timestamp
                    if (sensorTime < nextFrame) {
                        throttled.incrementAndGet()
                        return
                    }

                    if (nextFrame == 0L || sensorTime - nextFrame > interval) nextFrame = sensorTime
                    nextFrame += interval
                    val buffer =
                        free.poll()
                            ?: run {
                                dropped.incrementAndGet()
                                return
                            }
                    var queued = false

                    try {
                        val crop = image.cropRect
                        require(crop.width() == width && crop.height() == height) {
                            "Unsupported camera crop: $crop"
                        }

                        YuvPlanes.toNv21(
                            width,
                            height,
                            crop.left,
                            crop.top,
                            image.planes.map {
                                YuvPlanes.Plane(it.buffer, it.rowStride, it.pixelStride)
                            },
                            buffer,
                        )
                        val frame =
                            RawFrame(
                                buffer,
                                clock.toEpochNanos(sensorTime, SystemClock.elapsedRealtimeNanos()),
                            )
                        raw.poll()?.let {
                            free.offer(it.bytes)
                            dropped.incrementAndGet()
                        }

                        queued = raw.offer(frame)
                    } finally {
                        if (!queued) free.offer(buffer)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) fail(e)
            }
        }

        private fun encodeLoop() {
            val output = ByteArrayOutputStream(width * height / 4)

            try {
                while (running.get()) {
                    val frame = raw.take()

                    try {
                        if (!running.get()) break
                        val start = SystemClock.elapsedRealtimeNanos()
                        output.reset()
                        check(
                            YuvImage(frame.bytes, ImageFormat.NV21, width, height, null)
                                .compressToJpeg(Rect(0, 0, width, height), quality, output)
                        ) {
                            "JPEG encoding failed"
                        }

                        val result = JpegFrame(output.toByteArray(), frame.timestamp)
                        encodeNanos.addAndGet(SystemClock.elapsedRealtimeNanos() - start)
                        encoded.incrementAndGet()
                        jpeg.poll()?.let { dropped.incrementAndGet() }
                        if (running.get()) jpeg.offer(result)
                        if (previewEnabled && running.get()) {
                            onPreview(id, result.bytes, result.timestamp)
                        }
                    } finally {
                        free.offer(frame.bytes)
                    }
                }
            } catch (_: InterruptedException) {
                /* Explicit stop wakes the bounded queue. */
            } catch (e: Exception) {
                if (running.get()) fail(e)
            }
        }

        private fun publishLoop() {
            try {
                while (running.get()) {
                    val frame = jpeg.take()

                    if (!running.get()) break

                    if (NativeRuntime.publishImage(id, frame.timestamp, frame.bytes)) {
                        published.incrementAndGet()
                    } else {
                        failures.incrementAndGet()
                        fail(
                            IllegalStateException(
                                "Camera ROS publication failed: ${NativeRuntime.status()}"
                            )
                        )
                    }
                }
            } catch (_: InterruptedException) {
                /* Explicit stop. */
            } catch (e: Exception) {
                if (running.get()) fail(e)
            }
        }

        @Synchronized
        fun stats(): Stats {
            val now = SystemClock.elapsedRealtimeNanos()
            val c = captured.get()
            val p = published.get()

            if (now - lastTime >= 1_000_000_000L) {
                val seconds = (now - lastTime) / 1e9
                captureRate = (c - lastCaptured) / seconds
                publishRate = (p - lastPublished) / seconds
                lastTime = now
                lastCaptured = c
                lastPublished = p
            }

            val e = encoded.get()
            return Stats(
                width,
                height,
                rate,
                fps.upper,
                c,
                throttled.get(),
                dropped.get(),
                e,
                p,
                failures.get(),
                captureRate,
                publishRate,
                if (e == 0L) 0.0 else encodeNanos.get() / e / 1e6,
                if (realtime) {
                    "Sensor capture timestamps"
                } else {
                    "Sensor clock anchored to first arrival (approximate)"
                },
                quality,
            )
        }

        fun fail(error: Exception) {
            if (running.get() && errorReported.compareAndSet(false, true)) {
                onError(error.message ?: "Camera stream failed")
            }
        }

        @Synchronized
        fun close() {
            running.set(false)
            runCatching { session?.close() }
            session = null
            runCatching { camera?.close() }
            camera = null
            handler.removeCallbacksAndMessages(null)
            // Close on the capture thread, after any in-flight plane copies finish.
            handler.post {
                reader.close()
                captureThread.quitSafely()
            }

            encoder.interrupt()
            publisher.interrupt()
        }

        fun awaitStopped() {
            encoder.join(3000)
            publisher.join(3000)
            captureThread.join(3000)
            check(!encoder.isAlive && !publisher.isAlive && !captureThread.isAlive) {
                "Camera workers did not stop; restart the session before reusing the camera."
            }

            raw.clear()
            jpeg.clear()
            free.clear()
        }
    }
}
