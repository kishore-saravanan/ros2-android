package dev.ros2android.android

import android.app.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private lateinit var catalog: List<PhoneCapability>
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var generation = 0
    private var foreground = false
    private var foregroundTypes = 0
    @Volatile private var destroyed = false
    @Volatile var connected = false; private set
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
    data class Preview(val id: String, val stamp: Long, val bitmap: Bitmap)
    @Volatile var cameraPreview: Preview? = null; private set
    var cameraPreviewEnabled: Boolean
        get() = camera.previewEnabled
        set(value) { camera.previewEnabled = value; if (!value) cameraPreview = null }
    var onChange: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        catalog = CapabilityCatalog.publishers(this)
        battery = BatteryPublisher(this) { error ->
            stopPublisher("battery"); errors["battery"] = error; changed()
        }
        sensors = SensorPublishers(getSystemService(SensorManager::class.java))
        camera = CameraPublisher(this, { id, bytes ->
            // Decode only a small UI thumbnail, on the encoder worker, never the main thread.
            // No Activity reference or stored image; publishing queues stay bounded.
            if (camera.previewEnabled && !destroyed) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = 2 })
                if (bitmap != null && camera.previewEnabled && !destroyed)
                    cameraPreview = Preview(id, SystemClock.elapsedRealtimeNanos(), bitmap)
            }
        }) { error -> main.post {
            active.firstOrNull { id -> catalog.any { it.id == id && it.kind == 8 } }?.let { id ->
                stopPublisher(id); errors[id] = error; changed()
            }
        } }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Robot runtime", NotificationManager.IMPORTANCE_LOW))
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

    private fun ensureRuntime(profile: Profile) {
        if (connected) return
        check(NativeRuntime.available) { NativeRuntime.diagnostic() }
        NativeRuntime.start(profile.config.domainId, profile.config.namespace, profile.peer, profile.local, applicationInfo.nativeLibraryDir)
        connected = true
    }

    fun startPublisher(cap: PhoneCapability, config: EndpointConfig, profile: Profile) {
        if (busy || cap.id in active) return
        keepRunning(cap.kind == 8)
        val token = generation
        busy = true; state = "Starting ${cap.label}…"; changed()
        worker.execute {
            val result = runCatching { camera.awaitStopped(); ensureRuntime(profile); NativeRuntime.createPublisher(cap.id, config.topic, cap.kind) }
            main.post {
                if (token != generation || destroyed) return@post
                busy = false
                result.onSuccess {
                    runCatching {
                        when (cap.kind) {
                            8 -> camera.start(cap, config.rateHz)
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
            val result = runCatching { ensureRuntime(profile); NativeRuntime.subscribeDisplay(topic) }
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
                ensureRuntime(profile)
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
        generation++; val token = generation
        busy = true; state = "Stopping robot session…"
        sensors.stopAll(); battery.stop(); camera.stop(); cameraPreview = null; active.clear(); subscribed = false; cameraStats = null
        changed()
        worker.execute {
            val result = runCatching { camera.awaitStopped(); if (NativeRuntime.available) NativeRuntime.stop() }
            connected = false
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
        destroyed = true; generation++; onChange = null
        sensors.stopAll(); battery.stop(); camera.stop(); active.clear()
        if (connected || busy) worker.execute { runCatching { camera.awaitStopped(); if (NativeRuntime.available) NativeRuntime.stop() } }
        worker.shutdown(); releaseForeground()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "robot_runtime"
        private const val ACTION_STOP = "dev.ros2android.android.STOP_SESSION"
    }
}
