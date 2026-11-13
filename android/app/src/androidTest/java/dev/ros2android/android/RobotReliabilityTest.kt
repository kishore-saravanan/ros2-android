package dev.ros2android.android

import android.app.KeyguardManager
import android.content.*
import android.os.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ros2android.health.BackgroundPolicy
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
 * Phone-only publisher -> USB MCU -> phone subscription; no PC ROS process is needed.
 * Optional robotRunnerPackage (a disposable fixture) also runs a phone-local project on the same
 * domain before the Agent starts and requires its telemetry to stay fresh through every Agent
 * restart and the soak.
 */
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
        val project = args.getString("robotRunnerPackage")?.let { FixtureProject(instrumentation, it) }
        if (project != null) assertEquals("Allow background activity for ${project.packageName} first", false,
            BackgroundPolicy.batteryOptimized(context, project.packageName))
        val evidence = JSONObject().put("cyclesRequested", cycles).put("soakSecondsRequested", duration)
        val results = JSONArray(); evidence.put("recoveries", results)
        evidence.put("powerSavingAtStart", context.getSystemService(PowerManager::class.java).isPowerSaveMode)
        val projectEvidence = JSONObject()
        if (project != null) evidence.put("project", projectEvidence.put("package", project.packageName))
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
        val observed = ControlWidget("qualification_project", "Project", "Subscribed value", "/coexist/observed")
        val trial = java.util.UUID.randomUUID().toString()
        fun inspect(check: () -> Boolean): Boolean { var result = false; instrumentation.runOnMainSync { result = check() }; return result }
        fun waitFor(label: String, check: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 45000
            while (SystemClock.elapsedRealtime() < end) { if (inspect(check)) return; SystemClock.sleep(100) }
            fail("$label; agent=${MicroRosAgentService.state}; errors=${session.errors}")
        }
        fun startAgent() { context.startForegroundService(Intent(context, MicroRosAgentService::class.java).putExtra(MicroRosAgentService.USB, true)) }
        fun fresh(after: Long): Boolean = listOf(heartbeat, reply).all { (session.dashboardReadings[it.id]?.receivedAt ?: 0) > after } &&
            session.dashboardReadings[reply.id]?.value?.contains("ESP32 received phone battery:") == true
        // Project telemetry (main thread only): publisher tick and receipt time of this trial's latest sample.
        fun projectSample(): Pair<Long, Long>? = session.dashboardReadings[observed.id]?.let { reading ->
            runCatching { JSONObject(reading.value) }.getOrNull()?.takeIf { it.optString("trial") == trial }?.let { it.getLong("tick") to reading.receivedAt }
        }
        var firstTick = -1L; var lastTick = -1L; var lastReceipt = 0L; var received = 0L; var largestGap = 0L; var projectMaxAge = 0L
        fun observeProject() {
            val (tick, at) = projectSample() ?: return
            if (tick == lastTick) return
            if (firstTick < 0) firstTick = tick else largestGap = maxOf(largestGap, at - lastReceipt)
            lastTick = tick; lastReceipt = at; received++
        }
        fun checkProject(where: String) {
            if (project == null) return
            val (_, at) = projectSample() ?: throw AssertionError("No project telemetry $where")
            val age = SystemClock.elapsedRealtime() - at
            projectMaxAge = maxOf(projectMaxAge, age)
            assertTrue("Project telemetry stale for ${age}ms $where", age < 5000)
        }
        var failure: Throwable? = null
        try {
            context.stopService(Intent(context, MicroRosAgentService::class.java))
            waitFor("Previous Agent did not stop") { !MicroRosAgentService.serviceActive }
            instrumentation.runOnMainSync { session.stopSession() }
            waitFor("Previous main session did not stop") { !session.busy && !session.connected }
            project?.claim(0, "/coexist", listOf("-p", "trial:=$trial"))
            val profile = RobotSessionService.Profile(SessionConfig("/phone", 0), "", "")
            activity.scenario.onActivity { app ->
                session.startPublisher(CapabilityCatalog.publishers(app).single { it.id == "battery" }, EndpointConfig("/phone/battery_percent", 1.0), profile)
            }
            waitFor("Battery did not start") { !session.busy && "battery" in session.active }
            activity.scenario.onActivity { session.startControls(listOfNotNull(heartbeat, reply, observed.takeIf { project != null }), profile) }
            waitFor("Dashboard did not start") { session.controlsEnabled && !session.busy }
            if (project != null) {
                project.start()
                waitFor("No project telemetry before the Agent started") { projectSample() != null }
            }
            activity.scenario.onActivity { startAgent() }
            waitFor("Initial MCU round trip did not arrive") { fresh(0) }
            instrumentation.runOnMainSync { checkProject("after the Agent started") }
            repeat(cycles) { index ->
                context.stopService(Intent(context, MicroRosAgentService::class.java))
                waitFor("Agent did not release service") { !MicroRosAgentService.serviceActive }
                assertTrue(inspect { session.connected && "battery" in session.active && session.controlsEnabled })
                // Start as soon as service teardown is reported; no artificial recovery delay.
                val start = SystemClock.elapsedRealtime()
                activity.scenario.onActivity { startAgent() }
                waitFor("No fresh MCU data after recovery ${index + 1}") { fresh(start + 1000) }
                instrumentation.runOnMainSync { checkProject("after recovery ${index + 1}") }
                results.put(JSONObject().put("cycle", index + 1).put("recoveryMs", SystemClock.elapsedRealtime() - start))
                save()
            }
            val start = SystemClock.elapsedRealtime()
            evidence.put("phase", "continuous-round-trip"); save()
            var samples = 0; var maxAge = 0L
            var withoutWifiSamples = 0
            var nextCheck = start + 1000
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            while (SystemClock.elapsedRealtime() - start < duration * 1000L) {
                // Sample project telemetry every 100 ms to count deliveries; check everything each second.
                SystemClock.sleep(100)
                if (project != null) instrumentation.runOnMainSync { observeProject() }
                if (SystemClock.elapsedRealtime() < nextCheck) continue
                nextCheck += 1000
                instrumentation.runOnMainSync {
                    val now = SystemClock.elapsedRealtime()
                    val oldest = listOf(heartbeat, reply).minOf { session.dashboardReadings[it.id]?.receivedAt ?: 0 }
                    val age = now - oldest
                    maxAge = maxOf(maxAge, age)
                    assertTrue("MCU round trip stale for ${age}ms", age < 10000)
                    assertTrue("Sensor session failed", session.errors.isEmpty() && session.connected)
                    assertTrue("Agent stopped", MicroRosAgentService.running)
                    checkProject("during the soak")
                }
                if (connectivity.allNetworks.none { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }) withoutWifiSamples++
                samples++
                if (samples % 10 == 0) { evidence.put("samples", samples).put("withoutWifiSamples", withoutWifiSamples).put("elapsedMs", SystemClock.elapsedRealtime() - start); save() }
            }
            evidence.put("samples", samples).put("withoutWifiSamples", withoutWifiSamples).put("maxAgeMs", maxAge).put("elapsedMs", SystemClock.elapsedRealtime() - start)
            if (project != null) {
                val spanned = lastTick - firstTick + 1
                val pauses = project.freshStatus().optJSONObject("health")?.optInt("pauses", -1)
                projectEvidence.put("received", received).put("spannedTicks", spanned).put("largestGapMs", largestGap)
                    .put("maxAgeMs", projectMaxAge).put("runnerPauses", pauses)
                assertTrue("Project delivery below 90%: $projectEvidence", received > 0 && received * 10 >= spanned * 9)
                assertEquals("Project runner paused: $projectEvidence", 0, pauses)
            }
            evidence.put("result", "passed")
            Log.i("ROS2Qualification", "robot reliability: $evidence")
        } catch (error: Throwable) {
            evidence.put("result", "failed").put("failure", error.message); failure = error; throw error
        } finally {
            // Try every cleanup; report the first problem unless the test body already failed.
            fun cleanup(label: String, action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    Log.e("ROS2Qualification", "cleanup failed: $label", error)
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            val bodyFailed = failure != null
            cleanup("evidence") { save() }
            cleanup("agent") { context.stopService(Intent(context, MicroRosAgentService::class.java)) }
            cleanup("main session") { instrumentation.runOnMainSync { session.stopSession() } }
            cleanup("project") { project?.release() }
            cleanup("binding") { context.unbindService(connection) }
            if (!bodyFailed) failure?.let { throw it }
        }
    }
}
