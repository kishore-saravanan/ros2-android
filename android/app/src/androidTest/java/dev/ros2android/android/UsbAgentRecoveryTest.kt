package dev.ros2android.android

import android.app.KeyguardManager
import android.content.*
import android.hardware.usb.UsbManager
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Operator-guided USB Agent recovery with the reference serial firmware (battery reply and
 * heartbeat; no motion topics). Toasts and ROS2Guided logs tell the operator when to unplug,
 * replug, deny and allow USB access. Checks: clear Disconnected state, the sensor/Dashboard
 * session survives, denial is reported without a crash, and only an explicit start after
 * Allow brings fresh MCU replies. Opt in with usbRecoveryGuided=true; keep motor power off.
 */
@RunWith(AndroidJUnit4::class)
class UsbAgentRecoveryTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun detachReattachDenyAndAllowRecover() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Operator-guided; opt in with usbRecoveryGuided=true", InstrumentationRegistry.getArguments().getString("usbRecoveryGuided") == "true")
        val context = instrumentation.targetContext.applicationContext
        val usb = context.getSystemService(UsbManager::class.java)
        val evidence = JSONObject(); val phases = JSONArray(); evidence.put("phases", phases)
        val file = File(context.cacheDir, "usb-recovery.json")
        fun record(phase: String, detail: String) {
            phases.put(JSONObject().put("phase", phase).put("detail", detail).put("at", SystemClock.elapsedRealtime()))
            file.writeText(evidence.toString(2)); Log.i("ROS2Guided", "result $phase: $detail")
        }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun guide(step: String) { Log.i("ROS2Guided", "ACTION $step"); main { Toast.makeText(context, step, Toast.LENGTH_LONG).show() } }
        lateinit var session: RobotSessionService
        val bound = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { session = (binder as RobotSessionService.LocalBinder).session; bound.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        activity.scenario.onActivity {
            check(it.hasWindowFocus() && !it.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Open and unlock ROS2 Android first" }
            context.bindService(Intent(context, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        assertTrue(bound.await(5, TimeUnit.SECONDS))
        val heartbeat = ControlWidget("usb_recovery_heartbeat", "Heartbeat", "Subscribed value", "/esp32/heartbeat", messageType = "std_msgs/msg/Int32")
        val reply = ControlWidget("usb_recovery_reply", "MCU reply", "Subscribed value", "/phone/display/text", messageType = "std_msgs/msg/String")
        fun inspect(check: () -> Boolean): Boolean { var result = false; main { result = check() }; return result }
        fun waitFor(label: String, seconds: Int, reminder: String? = null, check: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + seconds * 1000L
            var nextReminder = SystemClock.elapsedRealtime() + 8000
            while (SystemClock.elapsedRealtime() < end) {
                if (inspect(check)) return
                if (reminder != null && SystemClock.elapsedRealtime() >= nextReminder) { guide(reminder); nextReminder += 8000 }
                SystemClock.sleep(200)
            }
            fail("$label; agent=${MicroRosAgentService.state}; errors=${session.errors}")
        }
        fun startAgent() { context.startForegroundService(Intent(context, MicroRosAgentService::class.java).putExtra(MicroRosAgentService.USB, true)) }
        fun fresh(after: Long) = listOf(heartbeat, reply).all { (session.dashboardReadings[it.id]?.receivedAt ?: 0) > after } &&
            session.dashboardReadings[reply.id]?.value?.contains("ESP32 received phone battery:") == true
        fun sessionAlive() = session.connected && "battery" in session.active && session.controlsEnabled
        /** Start the Agent; when Android asks for access, guide the operator to [answer] it. Returns whether a prompt appeared. */
        fun startExpectingPrompt(answer: String): Boolean {
            startAgent()
            val end = SystemClock.elapsedRealtime() + 15000
            while (SystemClock.elapsedRealtime() < end) {
                val state = MicroRosAgentService.state
                if (state.startsWith("Allow USB access")) { guide(answer); return true }
                if (state.startsWith("Running") || state.startsWith("Starting")) return false
                SystemClock.sleep(200)
            }
            return false
        }
        try {
            context.stopService(Intent(context, MicroRosAgentService::class.java))
            waitFor("Previous Agent did not stop", 30) { !MicroRosAgentService.serviceActive }
            main { session.stopSession() }
            waitFor("Previous main session did not stop", 30) { !session.busy && !session.connected }
            waitFor("Connect the ESP32 to the phone with USB OTG", 120, "Connect the ESP32 to the phone with the USB OTG adapter") { usb.deviceList.isNotEmpty() }
            val profile = RobotSessionService.Profile(SessionConfig("/phone", 0), "", "")
            activity.scenario.onActivity { app ->
                session.startPublisher(CapabilityCatalog.publishers(app).single { it.id == "battery" }, EndpointConfig("/phone/battery_percent", 1.0), profile)
            }
            waitFor("Battery did not start", 30) { !session.busy && "battery" in session.active }
            main { session.startControls(listOf(heartbeat, reply), profile) }
            waitFor("Dashboard did not start", 30) { session.controlsEnabled && !session.busy }
            startExpectingPrompt("Tap ALLOW on the USB permission dialog")
            waitFor("No initial MCU round trip", 120, null) { fresh(0) }
            record("baseline", "fresh heartbeat and battery reply via USB Agent")

            guide("UNPLUG the ESP32 USB cable from the phone now")
            waitFor("Agent did not report the detach", 120, "UNPLUG the ESP32 USB cable from the phone now") {
                MicroRosAgentService.state.startsWith("Disconnected")
            }
            assertTrue("Sensor/Dashboard session must survive the detach", inspect { sessionAlive() })
            val detachedAt = SystemClock.elapsedRealtime()
            waitFor("MCU readings did not become Stale", 15) {
                listOf(heartbeat, reply).all { session.dashboardReadings[it.id]?.state(SystemClock.elapsedRealtime()) == "Stale" }
            }
            record("detach", "state='${MicroRosAgentService.state}', session alive, MCU readings Stale")

            guide("PLUG the ESP32 back into the phone")
            waitFor("ESP32 not reattached", 120, "PLUG the ESP32 back into the phone") { usb.deviceList.isNotEmpty() }
            SystemClock.sleep(2000)
            main { assertFalse("Agent must not restart by itself", MicroRosAgentService.running) }
            record("reattach", "device present, Agent stayed stopped until asked")

            val prompted = startExpectingPrompt("Tap CANCEL (deny) on the USB permission dialog")
            if (prompted) {
                waitFor("Denial was not reported", 120, "Tap CANCEL (deny) on the USB permission dialog") {
                    MicroRosAgentService.state == "Failed: USB permission denied"
                }
                assertTrue("Sensor/Dashboard session must survive the denial", inspect { sessionAlive() })
                record("deny", "state='${MicroRosAgentService.state}', session alive, no crash")
                startExpectingPrompt("Tap ALLOW on the USB permission dialog")
            } else record("deny", "not exercised: Android did not ask (access already granted for this device)")

            val start = SystemClock.elapsedRealtime()
            waitFor("No fresh MCU data after allowing access", 120, null) { fresh(start) }
            record("recover", "fresh heartbeat and battery reply ${SystemClock.elapsedRealtime() - start} ms after explicit start")
            assertTrue("Recovery must follow the detach", start > detachedAt)
            guide("USB recovery check finished")
        } finally {
            runCatching { context.stopService(Intent(context, MicroRosAgentService::class.java)) }
            runCatching { main { session.stopSession() } }
            runCatching { context.unbindService(connection) }
        }
    }
}
