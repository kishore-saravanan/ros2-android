package dev.ros2android.android

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import org.json.JSONObject
import java.io.File

/** Debug-only, phone-owned recorder. No instrumentation/ADB connection owns its lifetime. */
class AutonomyQualificationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var session: RobotSessionService? = null
    private var bound = false
    private var phase = "idle"
    private var deadline = 0L
    private var started = 0L
    private var duration = 180
    private var minimumOffline = 30
    private var samples = 0
    private var offline = 0
    private var maxAge = 0L
    private var finished = false
    private val evidence = JSONObject()
    private val heartbeat = ControlWidget("autonomy_heartbeat", "Heartbeat", "Subscribed value", "/esp32/heartbeat", messageType = "std_msgs/msg/Int32")
    private val reply = ControlWidget("autonomy_reply", "MCU reply", "Subscribed value", "/phone/display/text", messageType = "std_msgs/msg/String")
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            session = (binder as RobotSessionService.LocalBinder).session
            session!!.stopSession()
            stopService(Intent(this@AutonomyQualificationService, MicroRosAgentService::class.java))
            phase = "stopping previous session"
            handler.post(tick)
        }
        override fun onServiceDisconnected(name: ComponentName) { finish("failed", "Sensor service disconnected") }
    }
    override fun onBind(intent: Intent): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finish("cancelled", "Stopped by developer"); return START_NOT_STICKY }
        if (active) return START_NOT_STICKY
        duration = intent?.getIntExtra("seconds", 180)?.coerceIn(30, 7200) ?: 180
        minimumOffline = intent?.getIntExtra("minimumOfflineSamples", 30)?.coerceIn(0, duration) ?: 30
        val channel = "autonomy_qualification"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, "Development continuity test", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 81, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_ros_notification)
            .setContentTitle("ROS2 Android · continuity test").setContentText("Battery → USB ESP32 → phone; temporary test endpoints")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop test", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(81, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(81, notification)
        active = true
        phase = "binding"
        deadline = SystemClock.elapsedRealtime() + 90000
        evidence.put("requestedSeconds", duration).put("minimumOfflineSamples", minimumOffline)
            .put("powerSavingAtStart", getSystemService(PowerManager::class.java).isPowerSaveMode)
        save()
        bound = bindService(Intent(this, RobotSessionService::class.java), connection, BIND_AUTO_CREATE)
        if (!bound) finish("failed", "Cannot bind sensor service")
        handler.postDelayed({ if (!finished && phase != "recording") finish("failed", "Startup deadline exceeded: $phase") }, 90000)
        return START_NOT_STICKY
    }
    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            try {
                val s = session ?: error("Sensor service unavailable")
                val now = SystemClock.elapsedRealtime()
                val profile = RobotSessionService.Profile(SessionConfig("/phone", 0), "", "")
                when (phase) {
                    "stopping previous session" -> if (!s.busy && !s.connected && !MicroRosAgentService.serviceActive) {
                        s.startPublisher(CapabilityCatalog.publishers(this@AutonomyQualificationService).single { it.id == "battery" }, EndpointConfig("/phone/battery_percent", 1.0), profile)
                        phase = "starting battery"
                    }
                    "starting battery" -> if (!s.busy && "battery" in s.active) {
                        s.startControls(listOf(heartbeat, reply), profile)
                        startForegroundService(Intent(this@AutonomyQualificationService, MicroRosAgentService::class.java).putExtra(MicroRosAgentService.USB, true))
                        phase = "waiting for USB round trip"
                    }
                    "waiting for USB round trip" -> if (freshAge(now) < 5000 && s.dashboardReadings[reply.id]?.value?.contains("ESP32 received phone battery:") == true) {
                        phase = "recording"; started = now
                    }
                    "recording" -> {
                        samples++
                        val networks = getSystemService(ConnectivityManager::class.java)
                        val wifi = networks.allNetworks.any { networks.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                        if (!wifi) offline++
                        val age = freshAge(now); maxAge = maxOf(maxAge, age)
                        evidence.put("samples", samples).put("withoutWifiSamples", offline).put("elapsedMs", now - started)
                            .put("heartbeatAgeMs", now - (s.dashboardReadings[heartbeat.id]?.receivedAt ?: 0))
                            .put("replyAgeMs", now - (s.dashboardReadings[reply.id]?.receivedAt ?: 0))
                            .put("maxAgeMs", maxAge).put("publishedBattery", NativeRuntime.publishedCount("battery"))
                            .put("agentRunning", MicroRosAgentService.running).put("usbDiagnostics", MicroRosAgentService.usbDiagnostics())
                        check(age < 10000) { "MCU data stale for ${age}ms" }
                        check(s.connected && s.errors.isEmpty() && MicroRosAgentService.running) { "Sensor or Agent stopped" }
                        if (now - started >= duration * 1000L) {
                            finish(if (offline >= minimumOffline) "passed" else "incomplete", if (offline >= minimumOffline) "" else "Required Wi-Fi-absent interval not observed")
                            return
                        }
                    }
                }
                if (phase != "recording") check(now < deadline) { "Startup timed out: $phase; ${MicroRosAgentService.state}" }
                save()
                handler.postDelayed(this, 1000)
            } catch (error: Exception) { finish("failed", error.message.orEmpty()) }
        }
    }
    private fun freshAge(now: Long) = now - listOf(heartbeat, reply).minOf { session?.dashboardReadings?.get(it.id)?.receivedAt ?: 0 }
    private fun save() {
        evidence.put("phase", phase)
        val target = File(cacheDir, FILE)
        val temporary = File(cacheDir, "$FILE.tmp")
        temporary.writeText(evidence.toString(2))
        check(temporary.renameTo(target)) { "Cannot save continuity evidence" }
    }
    private fun finish(result: String, reason: String) {
        if (finished) return
        finished = true
        evidence.put("result", result).put("reason", reason)
        phase = "finished"
        runCatching { save() }
        stopSelf()
    }
    override fun onDestroy() {
        if (!finished) {
            evidence.put("result", "interrupted").put("reason", "Recorder service destroyed")
            phase = "finished"; runCatching { save() }
        }
        handler.removeCallbacksAndMessages(null)
        session?.stopSession()
        if (bound) unbindService(connection)
        active = false
        super.onDestroy()
    }
    companion object {
        const val FILE = "autonomy-qualification.json"
        const val STOP = "dev.ros2android.android.STOP_AUTONOMY_TEST"
        @Volatile var active = false; private set
    }
}
