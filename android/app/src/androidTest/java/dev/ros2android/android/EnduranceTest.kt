package dev.ros2android.android

import android.content.*
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ros2android.health.BackgroundPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opt-in sustained phone-local operation: a disposable runner fixture publishes at 5 Hz while
 * the main app's Dashboard subscribes. Headless (no Activity or UI input). Per-minute summaries
 * are logged under ROS2Endurance for correlation with tests/integration/endurance_monitor.py.
 *
 * Arguments: enduranceRunnerPackage (fixture_cpp or fixture_python), enduranceMinutes (1..240),
 * enduranceAllowWifi=true to run with Wi-Fi on (default requires Wi-Fi off).
 */
@RunWith(AndroidJUnit4::class)
class EnduranceTest {
    @Test fun runnerTelemetryStaysContinuousWithoutPauses() {
        val arguments = InstrumentationRegistry.getArguments()
        val target = arguments.getString("enduranceRunnerPackage")
        assumeTrue("Opt in with enduranceRunnerPackage", target != null)
        assertTrue("Only disposable fixtures are allowed",
            target in setOf("dev.ros2android.android.runner.fixture_cpp", "dev.ros2android.android.runner.fixture_python"))
        val minutes = arguments.getString("enduranceMinutes")?.toIntOrNull() ?: 10
        assertTrue("enduranceMinutes must be 1..240", minutes in 1..240)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (arguments.getString("enduranceAllowWifi") != "true")
            assertEquals("Phone-local endurance requires Wi-Fi off", 0, Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, -1))
        for (pkg in listOf(context.packageName, target!!))
            assertEquals("Allow background activity for $pkg first", false, BackgroundPolicy.batteryOptimized(context, pkg))
        lateinit var session: RobotSessionService
        lateinit var control: ProjectController
        val bound = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { session = (binder as RobotSessionService.LocalBinder).session; bound.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun waitFor(label: String, seconds: Int = 20, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + seconds * 1000L
            var passed = false
            while (!passed && SystemClock.elapsedRealtime() < end) { main { passed = predicate() }; if (!passed) SystemClock.sleep(100) }
            assertTrue(label, passed)
        }
        fun command(value: Int, extras: Bundle = Bundle()) {
            var previous: JSONObject? = null
            main { previous = control.status; control.send(value, extras) }
            waitFor("No reply for $value") { check(control.error == null) { control.error.orEmpty() }; control.status != null && control.status !== previous }
        }
        fun state(expected: String) = waitFor("Runner did not become $expected") { control.poll(); control.status?.optString("state") == expected }
        val observed = ControlWidget("endurance_observed", "Endurance", "Subscribed value", "/endurance/observed")
        val trial = java.util.UUID.randomUUID().toString()
        var original: JSONObject? = null
        var ownsFixture = false; var ownsSession = false; var controllerReady = false
        main { assertTrue(context.bindService(Intent(context, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE)) }
        try {
            assertTrue(bound.await(5, TimeUnit.SECONDS))
            main {
                assertFalse("Stop the main session first", session.connected || session.busy || session.controlsEnabled)
                control = ProjectController(context, InstalledProjects.list(context).single { it.packageName == target }) {}
                controllerReady = true; control.connect()
            }
            waitFor("Runner unavailable") { control.poll(); control.status != null }
            main { assertEquals("Stop the fixture first", "Stopped", control.status!!.getString("state")); original = JSONObject(control.status!!.getJSONObject("config").toString()); ownsFixture = true }
            command(4, Bundle().apply {
                putString("domain", "86"); putString("namespace", "/endurance"); putString("peer", ""); putString("local", "")
                putString("ros_args", org.json.JSONArray(listOf("-p", "trial:=$trial")).toString())
            })
            main { ownsSession = true; session.startControls(listOf(observed), RobotSessionService.Profile(SessionConfig("/endurance", 86), "", "")) }
            waitFor("Dashboard not ready") { session.controlsEnabled && !session.busy }
            command(2); state("Running")
            waitFor("No first sample") { session.dashboardReadings[observed.id]?.let { JSONObject(it.value).optString("trial") == trial } == true }
            // Observation: sample every 100 ms; record the largest receipt gap and publish-to-receipt latency.
            val end = SystemClock.elapsedRealtime() + minutes * 60_000L
            var lastTick = -1L; var lastReceipt = 0L; var samples = 0L; var minuteSamples = 0L
            var largestGap = 0L; var largestLatency = 0L; var latencyTotal = 0L; var minuteGap = 0L
            var nextReport = SystemClock.elapsedRealtime() + 60_000L; var minute = 0
            while (SystemClock.elapsedRealtime() < end) {
                main {
                    val reading = session.dashboardReadings[observed.id] ?: return@main
                    val value = JSONObject(reading.value).takeIf { it.optString("trial") == trial } ?: return@main
                    val tick = value.getLong("tick")
                    if (tick != lastTick) {
                        if (lastReceipt > 0) { val gap = reading.receivedAt - lastReceipt; largestGap = maxOf(largestGap, gap); minuteGap = maxOf(minuteGap, gap) }
                        val latency = reading.receivedAt - value.optLong("stamp_ms", reading.receivedAt)
                        largestLatency = maxOf(largestLatency, latency); latencyTotal += latency
                        lastTick = tick; lastReceipt = reading.receivedAt; samples++; minuteSamples++
                    }
                }
                val now = SystemClock.elapsedRealtime()
                assertTrue("Telemetry stopped for over 5 s at minute $minute", lastReceipt == 0L || now - lastReceipt < 5000)
                if (now >= nextReport) {
                    minute++; var health: JSONObject? = null
                    main { control.poll(); health = control.status?.optJSONObject("health") }
                    Log.i("ROS2Endurance", "minute=$minute at=$now samples=$minuteSamples largestGapMs=$minuteGap runnerPauses=${health?.optInt("pauses")} mainPauses=${session.pauses.pauses}")
                    minuteSamples = 0; minuteGap = 0; nextReport += 60_000L
                }
                SystemClock.sleep(100)
            }
            var runnerHealth: JSONObject? = null
            main { control.poll() }
            waitFor("Final status unavailable") { runnerHealth = control.status?.optJSONObject("health"); runnerHealth != null }
            val summary = "minutes=$minutes samples=$samples largestGapMs=$largestGap largestPublishToReceiptMs=$largestLatency " +
                "meanPublishToReceiptMs=${if (samples > 0) latencyTotal / samples else -1} runnerHealth=$runnerHealth mainPauses=${session.pauses}"
            Log.i("ROS2Endurance", "summary $summary")
            // 5 Hz for the whole window; allow 10% loss for best-effort delivery and polling.
            assertTrue("Too few samples: $summary", samples >= minutes * 60L * 5 * 9 / 10)
            assertEquals("Runner paused: $summary", 0, runnerHealth!!.getInt("pauses"))
            assertEquals("Main app paused: $summary", 0, session.pauses.pauses)
            assertTrue("Receipt gap over 2 s: $summary", largestGap <= 2000)
        } finally {
            runCatching { if (ownsSession) { main { session.stopSession() }; waitFor("Session did not stop") { !session.connected && !session.busy } } }
            runCatching {
                if (ownsFixture) {
                    command(3); state("Stopped")
                    command(4, Bundle().apply { for (key in listOf("domain", "namespace", "peer", "local", "ros_args")) putString(key, original!!.getString(key)) })
                }
            }
            runCatching { if (controllerReady) main { control.close() } }
            runCatching { main { context.unbindService(connection) } }
        }
    }
}
