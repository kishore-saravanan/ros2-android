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
    private var connection: ServiceConnection? = null
    private var boundAt = 0L
    private var capabilities: Set<Int>? = null
    private val requests = ProjectRequests()
    fun supports(command: Int): Boolean = capabilities?.contains(command) == true

    // A distinct reply endpoint captures each ticket. Old services already reply to
    // replyTo, so even their unversioned replies can be correlated without wire changes.
    private fun replyFor(ticket: ProjectRequests.Ticket) = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (connection == null || remote == null || !trust.accepts(msg.sendingUid)) return
            if (!requests.accepts(ticket, msg.what)) return
            if (requests.expired(SystemClock.elapsedRealtime())) { failConnection("Runner is not responding. Tap Refresh."); return }
            val response = runCatching {
                @Suppress("DEPRECATION")
                val commands = ProjectProtocol.negotiate(msg.data.containsKey(ProjectProtocol.VERSION_KEY),
                    msg.data.get(ProjectProtocol.VERSION_KEY), msg.data.get(ProjectProtocol.COMMANDS_KEY))
                val reportedError = msg.data.getString("error")
                val snapshot = msg.data.getString("status")?.let { JSONObject(it) }
                require(snapshot != null || !reportedError.isNullOrBlank()) { "Invalid runner response" }
                Triple(commands, reportedError, snapshot)
            }
            if (response.isFailure) {
                failConnection(response.exceptionOrNull()?.message ?: "Invalid runner response"); return
            }
            val (commands, reportedError, snapshot) = response.getOrThrow()
            requests.finish(ticket)
            capabilities = commands
            if (msg.what == ProjectProtocol.STATUS) error = reportedError else commandError = reportedError
            status = snapshot
            changed()
        }
    })

    private fun failConnection(reason: String) { close(); error = reason; changed() }

    fun connect() {
        if (connection != null) return
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
        error = null; commandError = null; requests.reset()
        val candidate = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) return
                remote = Messenger(binder); capabilities = null; poll()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (connection !== this) return
                remote = null; capabilities = null; status = null; requests.reset()
                boundAt = SystemClock.elapsedRealtime(); error = "Runner disconnected"; changed()
            }
            override fun onNullBinding(name: ComponentName) {
                if (connection === this) failConnection("Rebuild this runner to enable app controls")
            }
            override fun onBindingDied(name: ComponentName) {
                if (connection === this) failConnection("Runner updated. Tap Refresh.")
            }
        }
        connection = candidate; boundAt = SystemClock.elapsedRealtime()
        val service = if (trust.paired) "PairedProjectControlService" else "ProjectControlService"
        runCatching { context.bindService(Intent().setComponent(ComponentName(project.packageName, "dev.ros2android.runner.$service")), candidate, Context.BIND_AUTO_CREATE) }
            .onSuccess { if (!it) failConnection("Rebuild this runner to enable app controls") }
            .onFailure { failConnection(it.message ?: "Cannot connect to runner") }
    }
    fun poll() {
        if (connection == null) return
        val now = SystemClock.elapsedRealtime()
        if (remote == null && now - boundAt >= ProjectRequests.TIMEOUT_MS) {
            failConnection("Runner connection timed out. Tap Refresh."); return
        }
        if (requests.expired(now)) { failConnection("Runner is not responding. Tap Refresh."); return }
        send(ProjectProtocol.STATUS)
    }
    fun send(command: Int, config: Bundle = Bundle()) {
        val target = remote ?: run {
            if (command != ProjectProtocol.STATUS) { commandError = "Runner disconnected. Tap Refresh, then try again."; changed() }
            return
        }
        if (command != ProjectProtocol.STATUS && !supports(command)) {
            commandError = "Runner does not support this command or has not connected yet"
            changed(); return
        }
        if (!trust.authorized()) { failConnection("Runner authorization changed. Refresh."); return }
        val now = SystemClock.elapsedRealtime()
        if (requests.expired(now)) { failConnection("Runner is not responding. Tap Refresh."); return }
        val ticket = requests.begin(command, now)
        if (ticket == null) {
            if (command != ProjectProtocol.STATUS) { commandError = "Waiting for the previous runner command"; changed() }
            return
        }
        if (command != ProjectProtocol.STATUS) commandError = null
        runCatching { target.send(Message.obtain(null, command).apply { replyTo = replyFor(ticket)
            data = Bundle(config).apply { putInt(ProjectProtocol.VERSION_KEY, ProjectProtocol.VERSION) } }) }
            .onFailure { failConnection("Runner disconnected. Tap Refresh.") }
    }
    fun close() {
        val previous = connection
        connection = null; remote = null; requests.reset(); status = null; capabilities = null
        // Android also requires unbinding after a failed or null binding.
        if (previous != null) runCatching { context.unbindService(previous) }
    }
}
