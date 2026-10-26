package dev.ros2android.android

import android.content.*
import android.os.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in: the PC uploads reference firmware once the private ready marker appears. */
@RunWith(AndroidJUnit4::class)
class Esp32ProgrammingTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun usbAgentAndDashboardRecoverAfterPcProgramming() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("programmingRoundTrip") == "true")
        lateinit var robot: RobotSessionService
        lateinit var context: Context
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { robot = (binder as RobotSessionService.LocalBinder).session; latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        activity.scenario.onActivity { app ->
            check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            context = app.applicationContext
            context.bindService(Intent(app, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
            app.startForegroundService(Intent(app, MicroRosAgentService::class.java).putExtra(MicroRosAgentService.USB, true))
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        fun inspect(check: () -> Boolean): Boolean { var result = false; activity.scenario.onActivity { result = check() }; return result }
        fun waitFor(message: String, seconds: Int = 60, check: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
            while (SystemClock.elapsedRealtime() < deadline) { if (check()) return; SystemClock.sleep(100) }
            fail("$message; programming=${Esp32ProgrammingService.state}; agent=${MicroRosAgentService.state}")
        }
        val marker = java.io.File(context.cacheDir, "esp32-programming-ready")
        try {
            val profile = RobotSessionService.Profile(SessionConfig("/phone", 0), "", "")
            activity.scenario.onActivity { app -> robot.startPublisher(CapabilityCatalog.publishers(app).single { it.id == "battery" }, EndpointConfig("/phone/battery_percent", 1.0), profile) }
            waitFor("Battery publisher") { inspect { !robot.busy && "battery" in robot.active } }
            val reply = ControlWidget("programming_reply", "ESP32 reply", "Subscribed value", "/phone/display/text", messageType = "std_msgs/msg/String")
            val heartbeat = ControlWidget("programming_heartbeat", "ESP32 heartbeat", "Subscribed value", "/esp32/heartbeat", messageType = "std_msgs/msg/Int32")
            activity.scenario.onActivity { robot.stopControls(); robot.startControls(listOf(reply, heartbeat), profile) }
            waitFor("Pre-upload phone→ESP32→Dashboard acknowledgement") { inspect {
                robot.dashboardReadings[reply.id]?.value?.contains("ESP32 received phone battery:") == true && robot.dashboardReadings[heartbeat.id] != null
            } }
            marker.writeText("ready")
            waitFor("PC did not begin programming", 90) { Esp32ProgrammingService.active }
            waitFor("Agent did not release USB") { !MicroRosAgentService.running }
            assertTrue(inspect { robot.connected && "battery" in robot.active && robot.controlsEnabled })
            waitFor("PC programming did not complete", 240) { !Esp32ProgrammingService.active }
            val finished = SystemClock.elapsedRealtime()
            waitFor("USB Agent/Dashboard did not recover") { inspect {
                MicroRosAgentService.running && MicroRosAgentService.usbTransport &&
                    robot.dashboardReadings[reply.id]?.let { it.receivedAt > finished && it.value.contains("ESP32 received phone battery:") } == true &&
                    robot.dashboardReadings[heartbeat.id]?.receivedAt?.let { it > finished } == true
            } }
            android.util.Log.i("ROS2ProgrammingVerification", "USB Agent and fresh typed MCU reply/heartbeat recovered after PC programming; sensors and Dashboard remained active")
        } finally {
            marker.delete()
            context.unbindService(connection)
        }
    }
}
