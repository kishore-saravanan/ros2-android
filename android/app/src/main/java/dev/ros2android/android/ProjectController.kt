package dev.ros2android.android

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import org.json.JSONObject

/** Bound only while the operator UI is resumed. Runtime survives unbinding. */
class ProjectController(private val context: Context, val project: InstalledProjects.Project, private val changed: () -> Unit) {
    var status: JSONObject? = null; private set
    var error: String? = null; private set
    var commandError: String? = null; private set
    private var remote: Messenger? = null
    private var bound = false
    private var lastReply = 0L
    private var pending = false
    private val reply = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            pending = false; lastReply = SystemClock.elapsedRealtime()
            if (msg.what == 1) error = msg.data.getString("error")
            else commandError = msg.data.getString("error")
            msg.data.getString("status")?.let { raw -> runCatching { JSONObject(raw) }.onSuccess { status = it }.onFailure { error = "Invalid runner response" } }
            changed()
        }
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote = Messenger(binder); poll() }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; status = null; error = "Runner disconnected"; changed() }
        override fun onNullBinding(name: ComponentName) { error = "Rebuild this runner to enable app controls"; changed() }
        override fun onBindingDied(name: ComponentName) { close(); error = "Runner updated. Tap Refresh."; changed() }
    }
    fun connect() {
        if (bound) return
        if (context.packageManager.checkSignatures(context.packageName, project.packageName) != PackageManager.SIGNATURE_MATCH) {
            error = "Runner signature does not match this app"; changed(); return
        }
        runCatching { context.bindService(Intent().setComponent(ComponentName(project.packageName, "dev.ros2android.runner.ProjectControlService")), connection, Context.BIND_AUTO_CREATE) }
            .onSuccess { bound = it; if (!it) { error = "Rebuild this runner to enable app controls"; changed() } }
            .onFailure { error = it.message; changed() }
    }
    fun poll() {
        if (pending && SystemClock.elapsedRealtime() - lastReply < 4000) return
        if (pending) { status = null; error = "Runner is not responding"; changed() }
        send(1)
    }
    fun send(command: Int, config: Bundle = Bundle()) {
        val target = remote ?: return
        if (command != 1) commandError = null
        runCatching { target.send(Message.obtain(null, command).apply { replyTo = reply; data = config }) }
            .onSuccess { pending = true; lastReply = SystemClock.elapsedRealtime() }
            .onFailure { status = null; error = "Runner disconnected. Tap Refresh."; changed() }
    }
    fun close() { if (bound) context.unbindService(connection); bound = false; remote = null }
}
