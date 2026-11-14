package dev.ros2android.runner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Manifest restricts this debug-only receiver to the system/shell DUMP permission. */
class DebugControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            when (intent.action) {
                ProjectService.START -> {
                    ProjectConfig.from(
                        intent
                    ) // Reject malformed requests before starting a service.
                    context.startForegroundService(
                        Intent(context, ProjectService::class.java)
                            .setAction(ProjectService.START)
                            .putExtras(intent)
                    )
                }
                "dev.ros2android.runner.PREPARE_SHELL" -> {
                    ProjectNative.prepare(context)
                    val pending = goAsync()
                    Thread {
                        runCatching { ProjectNative.prepareShell() }
                            .onSuccess {
                                pending.setResultCode(0)
                                pending.setResultData(it)
                            }
                            .onFailure {
                                pending.setResultCode(1)
                                pending.setResultData(it.message)
                            }
                        pending.finish()
                    }
                        .start()
                    return
                }

                FixtureFailure.CHILD,
                FixtureFailure.RUNNER -> FixtureFailure.schedule(context, intent.action!!)
                ProjectService.STOP -> ProjectService.current?.stopProject()
                "dev.ros2android.runner.STATUS" -> Unit
                else -> error("Unknown development command")
            }

            resultCode = 0
            resultData = ProjectService.statusJson(context)
        }
            .onFailure {
                resultCode = 1
                resultData = "${it.javaClass.simpleName}: ${it.message}"
            }
    }
}
