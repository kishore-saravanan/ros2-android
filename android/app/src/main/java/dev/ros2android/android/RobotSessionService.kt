package dev.ros2android.android

import android.app.*
import android.graphics.Bitmap
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Owns hardware and ROS independently of the Activity. Commands and observable state live on main. */
class RobotSessionService : Service() {
    data class Profile(val config: SessionConfig, val peer: String, val local: String)
    inner class LocalBinder : Binder() { val session: RobotSessionService get() = this@RobotSessionService }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private lateinit var sensors: SensorPublishers
    private lateinit var battery: BatteryPublisher
    private lateinit var camera: CameraPublisher
    private lateinit var previewDecoder: LatestPreviewDecoder
    private lateinit var catalog: List<PhoneCapability>
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var generation = 0
    private var foreground = false
    private var foregroundTypes = 0
    @Volatile private var destroyed = false
    private val runtime = SessionRuntime<Profile>(
        startNative = { profile ->
            check(NativeRuntime.available) { NativeRuntime.diagnostic() }
            NativeRuntime.start(profile.config.domainId, profile.config.namespace, profile.peer, profile.local, applicationInfo.nativeLibraryDir)
        },
        stopNative = { camera.awaitStopped(); if (NativeRuntime.available) NativeRuntime.stop() }
    )
    val connected: Boolean get() = runtime.connected
    var busy = false; private set
    val active = mutableSetOf<String>()
    val errors = mutableMapOf<String, String>()
    var subscribed = false; private set
    var received = "Waiting for a message…"; private set
    var receivedCount = 0L; private set
    var state = "Stopped · choose an endpoint to connect"; private set
    var counts: Map<String, Long> = emptyMap(); private set
    val batteryPercent: Int? get() = battery.lastPercentage
    var cameraStats: CameraPublisher.Stats? = null; private set
    data class Preview(val id: String, val stamp: Long, val bitmap: Bitmap, val capturedEpochNanos: Long)
    @Volatile var cameraPreview: Preview? = null; private set
    var cameraPreviewEnabled: Boolean
        get() = camera.previewEnabled
        set(value) { camera.previewEnabled = value; previewDecoder.enabled = value; if (!value) cameraPreview = null }
    var onChange: (() -> Unit)? = null

    data class DashboardReading(val value: String, val receivedAt: Long, val bitmap: Bitmap? = null) {
        fun state(now: Long): String = if (now - receivedAt > 5000) "Stale" else "Live"
    }
    private val dashboardImageDecoders = mutableMapOf<String, DashboardImageDecoder>()
    val dashboardReadings = mutableMapOf<String, DashboardReading>() // main-thread observations
    @Volatile private var dashboardUpdatePending = false
    @Volatile private var dashboardEpoch = 0
    @Volatile var controlsEnabled = false; private set
    val dashboardPublisherCount: Int get() = if (controlsEnabled) controlWidgets.count { !it.subscriber } else 0
    val dashboardSubscriberCount: Int get() = if (controlsEnabled) controlWidgets.count { it.subscriber } else 0
    private val controlCommands = DashboardCommandQueue()
    fun switchValue(id: String): Boolean = controlCommands.status(id)?.command?.takeIf { it.switchInput }?.first?.let { it != 0.0 } ?: false
    fun switchDescription(id: String): String = controlCommands.switchDescription(id)
    private var controlsRequested = false
    @Volatile private var controlWidgets = emptyList<ControlWidget>()
    private val motionActive = mutableSetOf<String>() // worker-owned
    fun startControls(widgets: List<ControlWidget>, profile: Profile) {
        if (busy || controlsRequested || widgets.isEmpty()) return
        require(widgets.size <= 32 && widgets.map { it.id }.distinct().size == widgets.size) { "Use at most 32 unique controls" }
        require(widgets.map { it.topic }.distinct().size == widgets.size) { "Each control needs its own topic" }
        require(widgets.count { it.image } <= 4) { "Use at most four image widgets" }
        keepRunning(false)
        dashboardEpoch++; dashboardReadings.clear()
        val imageEpoch = dashboardEpoch
        widgets.filter { it.image }.forEach { widget ->
            dashboardImageDecoders[widget.id] = DashboardImageDecoder(maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)) { receivedAt, bitmap -> main.post {
                if (!destroyed && controlsEnabled && dashboardEpoch == imageEpoch)
                    dashboardReadings[widget.id] = DashboardReading(if (bitmap == null) "Cannot display image" else "", receivedAt, bitmap)
            } }
        }
        controlCommands.retain(widgets.filter { !it.subscriber }.map { it.id }.toSet())
        controlWidgets = widgets.toList(); controlsRequested = true
        val token = generation
        busy = true; errors.remove("controls"); changed()
        worker.execute {
            val result = runCatching {
                runtime.ensure(profile)
                widgets.forEach {
                    if (it.subscriber) NativeRuntime.createDashboardSubscription(it.id, it.topic, it.kind)
                    else NativeRuntime.createPublisher("control:${it.id}", it.topic, it.kind)
                }
            }
            if (result.isFailure) widgets.forEach { runCatching {
                if (it.subscriber) NativeRuntime.removeDashboardSubscription(it.id)
                else NativeRuntime.removePublisher("control:${it.id}")
            } }
            main.post {
                if (token != generation || destroyed) return@post
                busy = false
                result.onSuccess { if (controlsRequested) controlsEnabled = true }
                    .onFailure { controlsRequested = false; dashboardImageDecoders.values.forEach { it.close() }; dashboardImageDecoders.clear(); errors["controls"] = it.message ?: "Control connection failed" }
                changed()
            }
        }
    }
    fun control(widget: ControlWidget, first: Double = 0.0, second: Double = 0.0, text: String = "") {
        if (!controlsEnabled || widget.subscriber || widget !in controlWidgets) return
        require(first.isFinite() && second.isFinite() && text.length <= 1024)
        val a = when (widget.kind) { 12 -> first.coerceIn(-widget.linear, widget.linear); 13 -> first.coerceIn(widget.minimum, widget.maximum); else -> first }
        val b = if (widget.kind == 12) second.coerceIn(-widget.angular, widget.angular) else 0.0
        controlCommands.submit(widget.id, a, b, text, SystemClock.elapsedRealtime(), widget.kind == 12, switchInput = widget.type == "Switch")
    }
    /** End motion input without tearing down dashboard endpoints or readings. */
    fun stopDashboardMotion() {
        controlWidgets.filter { !it.subscriber && it.kind == 12 }.forEach { control(it) }
    }
    fun stopControls() {
        dashboardImageDecoders.values.forEach { it.close() }; dashboardImageDecoders.clear()
        dashboardEpoch++; dashboardReadings.clear()
        controlsRequested = false; controlsEnabled = false
        val widgets = controlWidgets; controlWidgets = emptyList(); controlCommands.cancel()
        worker.execute {
            widgets.forEach { widget ->
                runCatching {
                    if (widget.kind == 12) NativeRuntime.publishControl("control:${widget.id}", doubleArrayOf(0.0, 0.0), "")
                    if (widget.subscriber) NativeRuntime.removeDashboardSubscription(widget.id)
                    else NativeRuntime.removePublisher("control:${widget.id}")
                }
            }
            motionActive.clear()
        }
        changed()
    }

    override fun onCreate() {
        super.onCreate()
        catalog = CapabilityCatalog.publishers(this)
        battery = BatteryPublisher(this) { error ->
            stopPublisher("battery"); errors["battery"] = error; changed()
        }
        sensors = SensorPublishers(getSystemService(SensorManager::class.java))
        previewDecoder = LatestPreviewDecoder { id, capturedEpochNanos, bitmap ->
            if (!destroyed) cameraPreview = Preview(id, SystemClock.elapsedRealtimeNanos(), bitmap, capturedEpochNanos)
        }
        camera = CameraPublisher(this, { id, bytes, capturedEpochNanos ->
            previewDecoder.submit(id, bytes, capturedEpochNanos)
        }) { error -> main.post {
            active.firstOrNull { id -> catalog.any { it.id == id && it.kind == 8 } }?.let { id ->
                stopPublisher(id); errors[id] = error; changed()
            }
        } }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Robot runtime", NotificationManager.IMPORTANCE_LOW))
        worker.scheduleWithFixedDelay({
            if (!connected || destroyed) return@scheduleWithFixedDelay
            val epoch = dashboardEpoch
            controlCommands.snapshot().forEach { command ->
                val id = command.id
                if (!controlsEnabled || epoch != dashboardEpoch || !controlCommands.isCurrent(command)) return@forEach
                val widget = controlWidgets.firstOrNull { it.id == id } ?: return@forEach
                val stale = SystemClock.elapsedRealtime() - command.stamp > 300
                if (widget.kind == 12 && stale && id !in motionActive) return@forEach
                val values = if (widget.kind == 12 && stale) doubleArrayOf(0.0, 0.0) else doubleArrayOf(command.first, command.second)
                val result = runCatching { check(NativeRuntime.publishControl("control:$id", values, command.text)) { "Control write failed" } }
                val current = controlCommands.complete(command, result.isSuccess)
                if (widget.kind == 12) {
                    if (!stale && values.any { it != 0.0 }) motionActive += id else motionActive -= id
                }
                result.onFailure { error -> main.post {
                    if (current && epoch == dashboardEpoch && controlsEnabled) {
                        errors["controls"] = error.message ?: "Control failed"; stopControls()
                    }
                } }
            }
        }, 0, 50, TimeUnit.MILLISECONDS)
        worker.scheduleWithFixedDelay({
            if (!connected || destroyed) return@scheduleWithFixedDelay
            val token = generation
            val status = runCatching { NativeRuntime.status() }.getOrDefault("ROS runtime error")
            val message = runCatching { NativeRuntime.pollDisplay() }.getOrNull()
            val latestCounts = catalog.associate { it.id to NativeRuntime.publishedCount(it.id) }
            val latestCamera = camera.stats()
            main.post {
                if (token != generation || destroyed || !connected) return@post
                if (!busy) state = status
                if (message != null) { received = message; receivedCount++ }
                counts = latestCounts; cameraStats = latestCamera; changed()
            }
        }, 0, 500, TimeUnit.MILLISECONDS)
        @Suppress("DEPRECATION")
        val refreshRate = getSystemService(android.view.WindowManager::class.java).defaultDisplay.refreshRate
        val dashboardPollMillis = (1000.0 / refreshRate.coerceAtLeast(1f)).toLong().coerceAtLeast(1)
        worker.scheduleWithFixedDelay({
            if (!connected || destroyed || !controlsEnabled || dashboardUpdatePending) return@scheduleWithFixedDelay
            val epoch = dashboardEpoch
            val updates = runCatching { NativeRuntime.pollDashboard() }.getOrDefault(emptyArray())
            if (updates.isEmpty()) return@scheduleWithFixedDelay
            val receivedAt = SystemClock.elapsedRealtime()
            dashboardUpdatePending = true
            main.post {
                try {
                    if (epoch != dashboardEpoch || !controlsEnabled || destroyed) return@post
                    updates.forEach { bytes ->
                        val split = bytes.indexOf(10.toByte())
                        if (split < 1) return@forEach
                        val id = bytes.copyOfRange(0, split).toString(Charsets.UTF_8)
                        val widget = controlWidgets.firstOrNull { it.id == id && it.subscriber } ?: return@forEach
                        val payload = bytes.copyOfRange(split + 1, bytes.size)
                        if (widget.image) dashboardImageDecoders[id]?.submit(payload, receivedAt)
                        else dashboardReadings[id] = DashboardReading(payload.toString(Charsets.UTF_8), receivedAt)
                    }
                } finally { dashboardUpdatePending = false }
            }
        }, 0, dashboardPollMillis, TimeUnit.MILLISECONDS)
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSession()
        return START_NOT_STICKY // Never silently restart camera capture after process death.
    }

    /** Invoked only from a visible Activity action, before opening camera/sensors. */
    private fun keepRunning(withCamera: Boolean) {
        val cameraActive = withCamera || catalog.any { it.kind == 8 && it.id in active }
        val types = (if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0) or
            (if (Build.VERSION.SDK_INT >= 30 && cameraActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        if (!foreground) startForegroundService(Intent(this, RobotSessionService::class.java))
        foregroundTypes = types
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, RobotSessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_ros_notification)
            .setContentTitle("ROS2 Android is running")
            .setContentText("Robot session active · tap to open or Stop to release hardware")
            .setOngoing(true).setContentIntent(open).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, foregroundTypes) else startForeground(1, notification)
        foreground = true
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ROS2Android:RobotSession").apply { setReferenceCounted(false); acquire() }
        if (multicast == null) multicast = applicationContext.getSystemService(WifiManager::class.java)
            .createMulticastLock("ROS2AndroidDDS").apply { setReferenceCounted(false); acquire() }
    }

    fun startPublisher(cap: PhoneCapability, config: EndpointConfig, profile: Profile) {
        if (busy || cap.id in active) return
        keepRunning(cap.kind == 8)
        val token = generation
        busy = true; state = "Starting ${cap.label}…"; changed()
        worker.execute {
            val result = runCatching { camera.awaitStopped(); runtime.ensure(profile); NativeRuntime.createPublisher(cap.id, config.topic, cap.kind) }
            main.post {
                if (token != generation || destroyed) return@post
                busy = false
                result.onSuccess {
                    runCatching {
                        when (cap.kind) {
                            8 -> camera.start(cap, config.rateHz, config.camera ?: CameraOptions())
                            9 -> battery.start(config)
                            else -> sensors.start(cap, config)
                        }
                        active += cap.id; errors.remove(cap.id); state = "ROS session active"
                    }.onFailure { error ->
                        errors[cap.id] = error.message ?: "Hardware start failed"
                        worker.execute { NativeRuntime.removePublisher(cap.id) }
                    }
                }.onFailure { errors[cap.id] = it.message ?: "ROS start failed"; state = errors.getValue(cap.id) }
                changed()
            }
        }
    }

    fun stopPublisher(id: String) {
        if (id == "battery") battery.stop()
        sensors.stop(id)
        if (catalog.any { it.id == id && it.kind == 8 }) { camera.stop(); cameraPreview = null }
        active -= id
        worker.execute {
            runCatching { camera.awaitStopped(); if (NativeRuntime.available) NativeRuntime.removePublisher(id) }
                .onFailure { error -> main.post { errors[id] = error.message ?: "Hardware shutdown failed"; changed() } }
        }
        changed()
    }

    fun subscribe(topic: String, profile: Profile) {
        if (busy || subscribed) return
        keepRunning(false)
        val token = generation
        busy = true; state = "Starting display subscriber…"; changed()
        worker.execute {
            val result = runCatching { runtime.ensure(profile); NativeRuntime.subscribeDisplay(topic) }
            main.post {
                if (token != generation || destroyed) return@post
                busy = false
                result.onSuccess { subscribed = true; errors.remove("display"); state = "ROS session active" }
                    .onFailure { errors["display"] = it.message ?: "Subscribe failed" }
                changed()
            }
        }
    }

    fun unsubscribe() {
        subscribed = false
        worker.execute { NativeRuntime.unsubscribeDisplay() }
        changed()
    }

    fun discover(type: String, profile: Profile, callback: (Result<List<String>>) -> Unit) {
        if (busy) return
        keepRunning(false)
        val token = generation
        busy = true; state = "Discovering ROS topics…"; changed()
        worker.execute {
            val result = runCatching {
                runtime.ensure(profile)
                // ROS graph discovery is asynchronous, so allow an initial discovery window.
                val deadline = SystemClock.elapsedRealtime() + 1500
                var names: List<String>
                do {
                    names = NativeRuntime.topics().filter { it.substringAfter('|') == type }.map { it.substringBefore('|') }.distinct().sorted()
                    if (names.isEmpty()) Thread.sleep(100)
                } while (names.isEmpty() && SystemClock.elapsedRealtime() < deadline)
                names
            }
            main.post {
                if (token != generation || destroyed) return@post
                busy = false; state = "ROS session active"; changed(); callback(result)
            }
        }
    }

    fun stopSession() {
        stopControls()
        generation++; val token = generation
        busy = true; state = "Stopping robot session…"
        sensors.stopAll(); battery.stop(); camera.stop(); cameraPreview = null; active.clear(); subscribed = false; cameraStats = null
        changed()
        worker.execute {
            val result = runCatching { runtime.stop() }
            main.post {
                if (token != generation || destroyed) return@post
                busy = false; counts = emptyMap()
                state = result.fold({ "Stopped · hardware and ROS endpoints released" }, { "Stop failed: ${it.message}" })
                releaseForeground(); changed(); stopSelf()
            }
        }
    }

    private fun changed() { if (!destroyed) onChange?.invoke() }
    private fun releaseForeground() {
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false; foregroundTypes = 0
    }
    override fun onDestroy() {
        stopControls()
        destroyed = true; generation++; onChange = null
        sensors.stopAll(); battery.stop(); camera.stop(); previewDecoder.close(); cameraPreview = null; active.clear()
        if (connected || busy) worker.execute { runCatching { runtime.stop() } }
        worker.shutdown(); releaseForeground()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "robot_runtime"
        private const val ACTION_STOP = "dev.ros2android.android.STOP_SESSION"
    }
}
