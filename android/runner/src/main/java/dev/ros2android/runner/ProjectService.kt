package dev.ros2android.runner

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.*
import org.json.JSONObject
import java.util.concurrent.Executors

class ProjectService : Service() {
    private val worker get() = control
    private val main = Handler(Looper.getMainLooper())
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null
    private var generation = 0
    @Volatile var state = "Stopped"
        private set(value) { field = value; lastState = value; ProjectLog.append(value) }
    @Volatile var busy = false; private set
    private var destroyed = false
    private val monitor = object : Runnable {
        override fun run() {
            if (destroyed || busy || state != "Running") return
            val diagnostic = runCatching { JSONObject(ProjectNative.diagnostic()) }
            val failure = diagnostic.fold({
                if (it.optBoolean("transition") || it.optBoolean("running")) null
                else it.optString("error").ifEmpty { "Native executor stopped" }
            }, { it.message ?: "Cannot inspect native executor" })
            if (failure != null) failProject(failure)
            else main.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate(); current = this
        ProjectNative.prepare(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "ROS project runtime", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopProject(); return START_NOT_STICKY }
        val config = runCatching { ProjectConfig.from(intent ?: Intent()) }
        if (config.isFailure) {
            // A startForegroundService request must be acknowledged even for malformed input.
            promote(); state = "Failed: ${config.exceptionOrNull()?.message}"; stopProject(); return START_NOT_STICKY
        }
        promote()
        if (busy || state == "Running") return START_NOT_STICKY
        val accepted = config.getOrThrow()
        val profile = JSONObject()
            .put("revision", java.util.UUID.randomUUID().toString())
            .put("domain", accepted.domain.toString()).put("namespace", accepted.namespace)
            .put("peer", accepted.peer).put("local", accepted.local)
            .put("ros_args", org.json.JSONArray(accepted.arguments.toList()).toString())
        // Both UI and shell starts use this path. Persist only validated, accepted requests.
        val edit = getSharedPreferences("project_profile", MODE_PRIVATE).edit()
        for (key in listOf("domain", "namespace", "peer", "local", "ros_args")) edit.putString(key, profile.getString(key))
        edit.putString("config_revision", profile.getString("revision")).apply()
        lastConfig = profile.toString()
        val token = ++generation
        busy = true; state = "Starting"
        worker.execute {
            val result = runCatching {
                ProjectNative.requireLoaded()
                val value = config.getOrThrow()
                ProjectNative.start(value.domain, value.namespace, value.peer, value.local, applicationInfo.nativeLibraryDir, value.arguments, value.nodes.map { it.component }.toTypedArray(), value.nodes.map { it.arguments }.toTypedArray())
            }
            main.post {
                if (destroyed || token != generation) return@post
                busy = false
                state = result.fold({ "Running" }, { "Failed: ${it.message}" })
                if (result.isFailure) failProject(result.exceptionOrNull()?.message ?: "Project start failed")
                else main.post(monitor)
            }
        }
        return START_NOT_STICKY
    }
    private fun promote() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ProjectService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("ROS2 Android · ${BuildConfig.PROJECT_NAME}").setContentText("Project runtime active · Stop to release")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(2, notification)
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ROS2Android:Project").apply { setReferenceCounted(false); acquire() }
        if (multicast == null) multicast = applicationContext.getSystemService(WifiManager::class.java)
            .createMulticastLock("ROS2AndroidProjectDDS").apply { setReferenceCounted(false); acquire() }
    }
    fun stopProject() {
        main.removeCallbacks(monitor)
        val token = ++generation
        busy = true; state = "Stopping"
        worker.execute {
            val result = runCatching { ProjectNative.stop() }
            main.post {
                if (destroyed || token != generation) return@post
                busy = false; state = result.fold({ "Stopped" }, { "Stop failed: ${it.message}" })
                releaseLocks(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
    }
    private fun failProject(reason: String) {
        main.removeCallbacks(monitor)
        val token = ++generation
        busy = true; state = "Failing: $reason"
        worker.execute {
            val cleanup = runCatching { ProjectNative.stop() }
            main.post {
                if (destroyed || token != generation) return@post
                busy = false
                state = "Failed: $reason" + (cleanup.exceptionOrNull()?.let { "; cleanup: ${it.message}" } ?: "")
                releaseLocks(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
    }
    private fun releaseLocks() {
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
    }
    override fun onDestroy() {
        main.removeCallbacks(monitor)
        destroyed = true; generation++; current = null
        if (state != "Stopped" || busy) worker.execute { runCatching { ProjectNative.stop() } }
        releaseLocks(); super.onDestroy()
    }
    companion object {
        const val START = "dev.ros2android.runner.START"
        const val STOP = "dev.ros2android.runner.STOP"
        private const val CHANNEL = "project_runtime"
        private val control = Executors.newSingleThreadExecutor()
        @Volatile private var lastState = "Stopped"
        @Volatile private var lastConfig: String? = null
        @Volatile var current: ProjectService? = null; private set
        fun statusJson(): String = JSONObject().put("project", BuildConfig.PROJECT_ID)
            .put("sourceId", BuildConfig.PROJECT_SOURCE_ID).put("state", current?.state ?: lastState).put("busy", current?.busy ?: false)
            .put("config", lastConfig?.let { JSONObject(it) })
            .put("native", runCatching { JSONObject(ProjectNative.diagnostic()) }.getOrElse { JSONObject().put("error", it.message) }).toString()
    }
}
