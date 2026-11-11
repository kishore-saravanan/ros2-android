package dev.ros2android.android

import android.content.*
import android.os.*
import org.json.JSONObject
import dev.ros2android.protocol.ProjectProtocol

/** Bound only while the operator UI is resumed. Runtime survives unbinding. */
class ProjectController(context: Context, val project: InstalledProjects.Project, private val changed: () -> Unit) {
    private val context = context.applicationContext
    val trust = ProjectTrust(this.context, project)
    var status: JSONObject? = null; private set
    var error: String? = null; private set
    var commandError: String? = null; private set
    private var remote: Messenger? = null
    private var bound = false
    private var lastReply = 0L
    private var pending = false
    private var boundAt = 0L
    private var capabilities: Set<Int>? = null
    fun supports(command: Int): Boolean = capabilities?.contains(command) == true
    private val reply = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!bound || remote == null || !trust.accepts(msg.sendingUid)) return
            val compatibility = runCatching {
                @Suppress("DEPRECATION")
                ProjectProtocol.negotiate(msg.data.containsKey(ProjectProtocol.VERSION_KEY),
                    msg.data.get(ProjectProtocol.VERSION_KEY), msg.data.get(ProjectProtocol.COMMANDS_KEY))
            }
            if (compatibility.isFailure) {
                pending = false; status = null; capabilities = null
                error = compatibility.exceptionOrNull()?.message ?: "Incompatible runner"
                changed(); return
            }
            capabilities = compatibility.getOrThrow()
            pending = false; lastReply = SystemClock.elapsedRealtime()
            if (msg.what == 1) error = msg.data.getString("error")
            else commandError = msg.data.getString("error")
            msg.data.getString("status")?.let { raw -> runCatching { JSONObject(raw) }.onSuccess { status = it }.onFailure { error = "Invalid runner response" } }
            changed()
        }
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote = Messenger(binder); capabilities = null; poll() }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; capabilities = null; status = null; error = "Runner disconnected"; changed() }
        override fun onNullBinding(name: ComponentName) { error = "Rebuild this runner to enable app controls"; changed() }
        override fun onBindingDied(name: ComponentName) { close(); error = "Runner updated. Tap Refresh."; changed() }
    }
    fun connect() {
        if (bound) return
        if (!ProjectProtocol.compatible(project.protocolVersion)) {
            status = null; capabilities = null
            error = "Runner protocol ${project.protocolVersion} is incompatible. Rebuild with a compatible SDK."
            changed(); return
        }
        if (!trust.authorized()) {
            status = null
            error = if (!trust.compatible()) "Rebuild runner for this controller APK" else "Authorize this runner in Details"
            changed(); return
        }
        error = null
        val service = if (trust.paired) "PairedProjectControlService" else "ProjectControlService"
        runCatching { context.bindService(Intent().setComponent(ComponentName(project.packageName, "dev.ros2android.runner.$service")), connection, Context.BIND_AUTO_CREATE) }
            .onSuccess { bound = it; boundAt = SystemClock.elapsedRealtime(); if (!it) { error = "Rebuild this runner to enable app controls"; changed() } }
            .onFailure { error = it.message; changed() }
    }
    fun poll() {
        if (bound && remote == null && SystemClock.elapsedRealtime() - boundAt >= 4000) {
            if (error == null) { error = "Runner connection timed out. Tap Refresh."; changed() }
            return
        }
        if (pending && SystemClock.elapsedRealtime() - lastReply < 4000) return
        if (pending) { status = null; error = "Runner is not responding"; changed() }
        send(1)
    }
    fun send(command: Int, config: Bundle = Bundle()) {
        val target = remote ?: return
        if (command != ProjectProtocol.STATUS && !supports(command)) {
            commandError = "Runner does not support this command or has not connected yet"
            changed(); return
        }
        if (!trust.authorized()) { close(); status = null; error = "Runner authorization changed. Refresh."; changed(); return }
        if (command != 1) commandError = null
        runCatching { target.send(Message.obtain(null, command).apply { replyTo = reply
            data = Bundle(config).apply { putInt(ProjectProtocol.VERSION_KEY, ProjectProtocol.VERSION) } }) }
            .onSuccess { pending = true; lastReply = SystemClock.elapsedRealtime() }
            .onFailure { status = null; error = "Runner disconnected. Tap Refresh."; changed() }
    }
    fun close() { if (bound) context.unbindService(connection); bound = false; remote = null; pending = false; status = null; capabilities = null }
}
