package dev.robotbrain.runner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Manifest restricts this debug-only receiver to the system/shell DUMP permission. */
class DebugControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            when (intent.action) {
                ProjectService.START -> {
                    ProjectConfig.from(intent) // Reject malformed requests before starting a service.
                    context.startForegroundService(Intent(context, ProjectService::class.java).setAction(ProjectService.START).putExtras(intent))
                }
                ProjectService.STOP -> ProjectService.current?.stopProject()
                "dev.robotbrain.runner.STATUS" -> Unit
                else -> error("Unknown development command")
            }
            resultCode = 0; resultData = ProjectService.statusJson()
        }.onFailure { resultCode = 1; resultData = "${it.javaClass.simpleName}: ${it.message}" }
    }
}
