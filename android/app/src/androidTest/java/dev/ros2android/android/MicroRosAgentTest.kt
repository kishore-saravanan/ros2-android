package dev.ros2android.android

import android.content.*
import android.os.IBinder
import android.os.SystemClock
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

@RunWith(AndroidJUnit4::class)
class MicroRosAgentTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private fun waitFor(label: String, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
        assertTrue(label + "\n" + MicroRosAgentService.state + "\n" + MicroRosAgentService.logs(), check())
    }
    private fun agentIntent(context: Context) = Intent(context, MicroRosAgentService::class.java)
        .putExtra(MicroRosAgentService.USB, InstrumentationRegistry.getArguments().getString("usbConnected") == "true")
    @Test fun agentProcessStarts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Build the optional Android Agent first", java.io.File(context.applicationInfo.nativeLibraryDir, "libmicro_ros_agent_exec.so").isFile)
        activity.scenario.onActivity { it.startForegroundService(agentIntent(it)) }
        SystemClock.sleep(3000)
        assertTrue(MicroRosAgentService.state + "\n" + MicroRosAgentService.logs(), MicroRosAgentService.running)
    }
    @Test fun esp32ReceivesPhoneBatteryAndRepliesAfterAgentRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("esp32Connected") == "true" || InstrumentationRegistry.getArguments().getString("usbConnected") == "true")
        lateinit var robot: RobotSessionService
        lateinit var context: Context
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                robot = (binder as RobotSessionService.LocalBinder).session; latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        activity.scenario.onActivity { app ->
            context = app
            app.bindService(Intent(app, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
            app.startForegroundService(agentIntent(app))
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        try {
            val profile = RobotSessionService.Profile(SessionConfig("/phone", 0), "", "")
            activity.scenario.onActivity { app -> robot.startPublisher(CapabilityCatalog.publishers(app).single { it.id == "battery" }, EndpointConfig("/phone/battery_percent", 1.0), profile) }
            fun inspect(check: () -> Boolean): Boolean { var result = false; activity.scenario.onActivity { result = check() }; return result }
            waitFor("Battery publisher did not start") { inspect { !robot.busy && "battery" in robot.active } }
            activity.scenario.onActivity { robot.subscribe("/phone/display/text", profile) }
            waitFor("ESP32 acknowledgement did not reach the phone") { inspect { robot.received.contains("ESP32 received phone battery:") && robot.receivedCount >= 2 } }
            activity.scenario.onActivity { app -> app.stopService(Intent(app, MicroRosAgentService::class.java)) }
            waitFor("Agent did not stop") { !MicroRosAgentService.running }
            var oldCount = 0L
            activity.scenario.onActivity {
                assertTrue("Stopping the Agent stopped sensors", robot.connected && "battery" in robot.active)
                oldCount = robot.receivedCount
            }
            SystemClock.sleep(1500) // Drain any messages delivered before the stop completed.
            activity.scenario.onActivity { oldCount = robot.receivedCount; it.startForegroundService(agentIntent(it)) }
            waitFor("ESP32 did not reconnect after Agent restart") { inspect { robot.receivedCount > oldCount + 2 && robot.received.contains("ESP32 received phone battery:") } }
        } finally { context.unbindService(connection) }
    }
}
