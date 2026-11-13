package dev.ros2android.runner

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import org.json.JSONObject
import dev.ros2android.protocol.ProjectProtocol

/** Narrow, signature-protected operator protocol; never accepts shell commands. */
open class ProjectControlService : Service() {
    protected open fun authorized(uid: Int): Boolean =
        dev.ros2android.security.SigningIdentity.ownsUid(packageManager, "dev.ros2android.android", uid) &&
            packageManager.checkSignatures(uid, applicationInfo.uid) == PackageManager.SIGNATURE_MATCH
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val uid = message.sendingUid
            if (!authorized(uid)) return
            val result = runCatching {
                val envelope = message.data
                @Suppress("DEPRECATION")
                val version = envelope.get(ProjectProtocol.VERSION_KEY)
                ProjectProtocol.requireRequest(envelope.containsKey(ProjectProtocol.VERSION_KEY), version, message.what)
                when (message.what) {
                    1 -> Unit
                    2, 4 -> {
                        val input = message.data
                        val intent = Intent(this@ProjectControlService, ProjectService::class.java).setAction(ProjectService.START)
                        val prefs = getSharedPreferences("project_profile", MODE_PRIVATE)
                        intent.putExtra("domain", input.getString("domain", prefs.getString("domain", "0"))!!.toInt())
                        for ((key, fallback) in mapOf("namespace" to BuildConfig.PROJECT_NAMESPACE, "peer" to "", "local" to "", "ros_args" to "[]"))
                            intent.putExtra(key, input.getString(key, prefs.getString(key, fallback)))
                        ProjectConfig.from(intent)
                        if (message.what == 2) startForegroundService(intent)
                        else {
                            check(ProjectService.current?.let { it.busy || it.state == "Running" } != true) { "Stop the project before editing settings" }
                            val edit = prefs.edit().putString("config_revision", java.util.UUID.randomUUID().toString())
                            edit.putString("domain", intent.getIntExtra("domain", 0).toString())
                            for (key in listOf("namespace", "peer", "local", "ros_args")) edit.putString(key, intent.getStringExtra(key))
                            edit.apply()
                        }
                    }
                    3 -> ProjectService.current?.stopProject()
                    else -> error("Unsupported project control request")
                }
                // statusJson already reports the saved profile as "config".
                JSONObject(ProjectService.statusJson(this@ProjectControlService)).put("logs", ProjectLog.snapshot()).toString()
            }
            runCatching { message.replyTo?.send(Message.obtain(null, message.what).apply {
                data = Bundle().apply {
                    putInt(ProjectProtocol.VERSION_KEY, ProjectProtocol.VERSION)
                    putIntArray(ProjectProtocol.COMMANDS_KEY, ProjectProtocol.commands.toIntArray())
                    putString("status", result.getOrNull()); putString("error", result.exceptionOrNull()?.message)
                }
            }) }
        }
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
}
