package dev.ros2android.android

import android.app.KeyguardManager
import android.content.*
import android.os.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

/** Phone-only publisher -> USB MCU -> phone subscription; no PC ROS process is needed. */
@RunWith(AndroidJUnit4::class)
class RobotReliabilityTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun repeatedUsbAgentRecoveryAndContinuousRoundTrip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit connected reference MCU opt-in", args.getString("robotUsbConnected") == "true")
        val cycles = args.getString("recoveryCycles", "5").toInt().also { require(it in 1..30) }
        val duration = args.getString("soakSeconds", "180").toInt().also { require(it in 30..7200) }
        val context = instrumentation.targetContext.applicationContext
        val evidence = JSONObject().put("cyclesRequested", cycles).put("soakSecondsRequested", duration)
        val results = JSONArray(); evidence.put("recoveries", results)
        evidence.put("powerSavingAtStart", context.getSystemService(PowerManager::class.java).isPowerSaveMode)
        val file = File(context.cacheDir, "robot-reliability.json")
        fun save() { file.writeText(evidence.toString(2)) }
        evidence.put("phase", "starting"); save()
        lateinit var session: RobotSessionService
        val bound = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { session = (binder as RobotSessionService.LocalBinder).session; bound.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        activity.scenario.onActivity {
            check(it.hasWindowFocus() && !it.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            context.bindService(Intent(context, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        assertTrue(bound.await(5, TimeUnit.SECONDS))
        val heartbeat = ControlWidget("qualification_heartbeat", "Heartbeat", "Subscribed value", "/esp32/heartbeat", messageType = "std_msgs/msg/Int32")
        val reply = ControlWidget("qualification_reply", "MCU reply", "Subscribed value", "/phone/display/text", messageType = "std_msgs/msg/String")
        fun inspect(check: () -> Boolean): Boolean { var result = false; instrumentation.runOnMainSync { result = check() }; return result }
        fun waitFor(label: String, check: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 45000
            while (SystemClock.elapsedRealtime() < end) { if (inspect(check)) return; SystemClock.sleep(100) }
            fail("$label; agent=${MicroRosAgentService.state}; errors=${session.errors}")
        }
        fun startAgent() { context.startForegroundService(Intent(context, MicroRosAgentService::class.java).putExtra(MicroRosAgentService.USB, true)) }
        fun fresh(after: Long): Boolean = listOf(heartbeat, reply).all { (session.dashboardReadings[it.id]?.receivedAt ?: 0) > after } &&
            session.dashboardReadings[reply.id]?.value?.contains("ESP32 received phone battery:") == true
        try {
            context.stopService(Intent(context, MicroRosAgentService::class.java))
            waitFor("Previous Agent did not stop") { !MicroRosAgentService.serviceActive }
            instrumentation.runOnMainSync { session.stopSession() }
            waitFor("Previous main session did not stop") { !session.busy && !session.connected }
            val profile = RobotSessionService.Profile(SessionConfig("/phone", 0), "", "")
            activity.scenario.onActivity { app ->
                session.startPublisher(CapabilityCatalog.publishers(app).single { it.id == "battery" }, EndpointConfig("/phone/battery_percent", 1.0), profile)
            }
            waitFor("Battery did not start") { !session.busy && "battery" in session.active }
            activity.scenario.onActivity { session.startControls(listOf(heartbeat, reply), profile); startAgent() }
            waitFor("Initial MCU round trip did not arrive") { fresh(0) }
            repeat(cycles) { index ->
                context.stopService(Intent(context, MicroRosAgentService::class.java))
                waitFor("Agent did not release service") { !MicroRosAgentService.serviceActive }
                assertTrue(inspect { session.connected && "battery" in session.active && session.controlsEnabled })
                // Start as soon as service teardown is reported; no artificial recovery delay.
                val start = SystemClock.elapsedRealtime()
                activity.scenario.onActivity { startAgent() }
                waitFor("No fresh MCU data after recovery ${index + 1}") { fresh(start + 1000) }
                results.put(JSONObject().put("cycle", index + 1).put("recoveryMs", SystemClock.elapsedRealtime() - start))
                save()
            }
            val start = SystemClock.elapsedRealtime()
            evidence.put("phase", "continuous-round-trip"); save()
            var samples = 0; var maxAge = 0L
            var withoutWifiSamples = 0
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            while (SystemClock.elapsedRealtime() - start < duration * 1000L) {
                SystemClock.sleep(1000)
                instrumentation.runOnMainSync {
                    val now = SystemClock.elapsedRealtime()
                    val oldest = listOf(heartbeat, reply).minOf { session.dashboardReadings[it.id]?.receivedAt ?: 0 }
                    val age = now - oldest
                    maxAge = maxOf(maxAge, age)
                    assertTrue("MCU round trip stale for ${age}ms", age < 10000)
                    assertTrue("Sensor session failed", session.errors.isEmpty() && session.connected)
                    assertTrue("Agent stopped", MicroRosAgentService.running)
                }
                if (connectivity.allNetworks.none { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }) withoutWifiSamples++
                samples++
                if (samples % 10 == 0) { evidence.put("samples", samples).put("withoutWifiSamples", withoutWifiSamples).put("elapsedMs", SystemClock.elapsedRealtime() - start); save() }
            }
            evidence.put("samples", samples).put("withoutWifiSamples", withoutWifiSamples).put("maxAgeMs", maxAge).put("elapsedMs", SystemClock.elapsedRealtime() - start).put("result", "passed")
        } catch (error: Throwable) {
            evidence.put("result", "failed").put("failure", error.message); throw error
        } finally {
            save()
            instrumentation.runOnMainSync { session.stopSession() }
            context.unbindService(connection)
        }
    }
}
