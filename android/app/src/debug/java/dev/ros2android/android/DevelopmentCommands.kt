package dev.ros2android.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Development builds only: autonomy qualification commands for the ESP32 programming receiver. */
object DevelopmentCommands {
    fun handle(context: Context, intent: Intent, receiver: BroadcastReceiver): Boolean {
        when (intent.getStringExtra("command")) {
            "autonomy-start" -> {
                if (AutonomyQualificationService.active || Esp32ProgrammingService.active) {
                    receiver.resultCode = 1
                    receiver.resultData = "Test or programming already active"
                    return true
                }

                context.startForegroundService(
                    Intent(context, AutonomyQualificationService::class.java)
                        .putExtra("seconds", intent.getIntExtra("seconds", 180))
                        .putExtra(
                            "minimumOfflineSamples",
                            intent.getIntExtra("minimumOfflineSamples", 30),
                        )
                )
            }

            "autonomy-stop" ->
                if (AutonomyQualificationService.active) {
                    context.startService(
                        Intent(context, AutonomyQualificationService::class.java)
                            .setAction(AutonomyQualificationService.STOP)
                    )
                }
            "autonomy-status" -> {
                receiver.resultData =
                    java.io
                        .File(context.cacheDir, AutonomyQualificationService.FILE)
                        .takeIf { it.isFile }
                        ?.readText() ?: "No continuity test recorded"
            }
            else -> return false
        }

        receiver.resultCode = 0
        return true
    }
}
