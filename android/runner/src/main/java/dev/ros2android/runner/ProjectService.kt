package dev.ros2android.runner

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.*
import dev.ros2android.health.BackgroundPolicy
import dev.ros2android.health.PauseMonitor
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
            val diagnostic = runCatching { JSONObject(backend.diagnostic()) }
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
        backend.prepare(applicationContext)
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
        pauses.start() // Execution pauses are reported for this run until it stops or fails.
        worker.execute {
            val result = runCatching {
                backend.requireLoaded()
                backend.start(accepted, applicationInfo.nativeLibraryDir)
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
            val result = runCatching { backend.stop() }
            main.post {
                if (destroyed || token != generation) return@post
                busy = false; state = result.fold({ "Stopped" }, { "Stop failed: ${it.message}" })
                pauses.stop(); releaseLocks(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        watchStop(token)
    }
    /** A node callback that never returns must not keep the project running: end the runner process. */
    private fun watchStop(token: Int) = main.postDelayed({
        if (destroyed || token != generation || !busy) return@postDelayed
        android.util.Log.e("ROS2AndroidProject", "Stop did not finish in ${STOP_LIMIT_MS / 1000} s; ending the runner process")
        releaseLocks(); stopForeground(STOP_FOREGROUND_REMOVE)
        android.os.Process.killProcess(android.os.Process.myPid())
    }, STOP_LIMIT_MS)
    private fun failProject(reason: String) {
        main.removeCallbacks(monitor)
        val token = ++generation
        busy = true; state = "Failing: $reason"
        worker.execute {
            val cleanup = runCatching { backend.stop() }
            main.post {
                if (destroyed || token != generation) return@post
                busy = false
                state = "Failed: $reason" + (cleanup.exceptionOrNull()?.let { "; cleanup: ${it.message}" } ?: "")
                pauses.stop(); releaseLocks(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        watchStop(token)
    }
    private fun releaseLocks() {
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
    }
    override fun onDestroy() {
        main.removeCallbacks(monitor)
        destroyed = true; generation++; current = null; pauses.stop()
        if (state != "Stopped" || busy) worker.execute { runCatching { backend.stop() } }
        releaseLocks(); super.onDestroy()
    }
    companion object {
        const val START = "dev.ros2android.runner.START"
        const val STOP = "dev.ros2android.runner.STOP"
        private const val CHANNEL = "project_runtime"
        private const val STOP_LIMIT_MS = 15_000L
        private val control = Executors.newSingleThreadExecutor()
        private val backend: RunnerBackend = ProjectNative
        @Volatile private var lastState = "Stopped"
        @Volatile private var lastConfig: String? = null
        @Volatile var current: ProjectService? = null; private set
        /** Process-wide so the last run's pauses stay visible after Stop until the next Start. */
        private val pauses = PauseMonitor()
        fun statusJson(context: Context): String = JSONObject().put("project", BuildConfig.PROJECT_ID)
            .put("sourceId", BuildConfig.PROJECT_SOURCE_ID).put("state", current?.state ?: lastState).put("busy", current?.busy ?: false)
            .put("config", savedConfig(context))
            .put("runConfig", lastConfig?.let { JSONObject(it) })
            .put("native", runCatching { JSONObject(backend.diagnostic()) }.getOrElse { JSONObject().put("error", it.message) })
            .put("health", health(context)).toString()
        /** The saved profile the next Start uses; "runConfig" is what the current or last run started with. */
        private fun savedConfig(context: Context): JSONObject =
            context.getSharedPreferences("project_profile", Context.MODE_PRIVATE).let { prefs ->
                JSONObject().put("revision", prefs.getString("config_revision", "")).put("domain", prefs.getString("domain", "0"))
                    .put("namespace", prefs.getString("namespace", BuildConfig.PROJECT_NAMESPACE))
                    .put("peer", prefs.getString("peer", "")).put("local", prefs.getString("local", ""))
                    .put("ros_args", prefs.getString("ros_args", "[]"))
            }
        /** Additive status object: absent from older runners, so readers must treat it as optional. */
        private fun health(context: Context): JSONObject = pauses.snapshot.let { pause ->
            JSONObject().put("monitoring", pauses.monitoring).put("pauses", pause.pauses)
                .put("longestPauseMs", pause.longestMs).put("totalPausedMs", pause.totalMs).put("lastPauseEndedAt", pause.lastEndedAt)
                .put("batteryOptimized", BackgroundPolicy.batteryOptimized(context) ?: JSONObject.NULL)
                .put("backgroundRestricted", BackgroundPolicy.backgroundRestricted(context) ?: JSONObject.NULL)
        }
    }
}
