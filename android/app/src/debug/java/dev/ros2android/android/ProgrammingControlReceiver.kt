package dev.ros2android.android

import android.content.*
import org.json.JSONObject

/** Debug APK only; manifest requires DUMP, held by authorized ADB shell. */
class ProgrammingControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = Intent(context, Esp32ProgrammingService::class.java)
        when (intent.getStringExtra("command")) {
            "start" -> {
                val token = intent.getStringExtra("token").orEmpty()
                if (Esp32ProgrammingService.active || !token.matches(Regex("[a-f0-9]{64}"))) { resultCode = 1; resultData = "Busy or invalid token"; return }
                context.startForegroundService(service.putExtra("token", token))
            }
            "stop" -> if (Esp32ProgrammingService.active) context.startService(service.setAction(Esp32ProgrammingService.STOP).putExtra("success", intent.getBooleanExtra("success", false)))
            "start-usb-agent" -> {
                if (Esp32ProgrammingService.active) { resultCode = 1; return }
                context.startForegroundService(Intent(context, MicroRosAgentService::class.java).putExtra(MicroRosAgentService.USB, true))
            }
            "status" -> Unit
            else -> { resultCode = 1; return }
        }
        resultCode = 0
        resultData = JSONObject().put("active", Esp32ProgrammingService.active).put("state", Esp32ProgrammingService.state).put("port", Esp32ProgrammingService.port).put("agentRunning", MicroRosAgentService.running).put("agentState", MicroRosAgentService.state).put("agentLogs", MicroRosAgentService.logs()).put("usbDiagnostics", MicroRosAgentService.usbDiagnostics()).toString()
    }
}
