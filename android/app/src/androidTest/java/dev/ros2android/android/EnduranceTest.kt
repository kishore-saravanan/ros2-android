package dev.ros2android.android

import android.content.*
import android.os.*
import android.provider.Settings
import android.util.Log
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
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
        val minutes = arguments.getString("enduranceMinutes")?.toIntOrNull() ?: 10
        assertTrue("enduranceMinutes must be 1..240", minutes in 1..240)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val project = FixtureProject(instrumentation, target!!)
        if (arguments.getString("enduranceAllowWifi") != "true")
            assertEquals("Phone-local endurance requires Wi-Fi off", 0, Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, -1))
        for (pkg in listOf(context.packageName, target))
            assertEquals("Allow background activity for $pkg first", false, BackgroundPolicy.batteryOptimized(context, pkg))
        lateinit var session: RobotSessionService
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
        val observed = ControlWidget("endurance_observed", "Endurance", "Subscribed value", "/endurance/observed")
        val trial = java.util.UUID.randomUUID().toString()
        var ownsSession = false
        var ownsBinding = false
        // App-private, crash-safe evidence survives ADB shell loss and logcat rotation.
        // A record left "running" is incomplete, never a pass.
        val record = JSONObject().put("format", 1).put("trial", trial).put("requestedMinutes", minutes)
            .put("startedAtEpochMs", System.currentTimeMillis()).put("outcome", "running")
            .put("checkpoints", JSONArray())
        val evidenceFile = AtomicFile(File(context.filesDir, "endurance.json"))
        fun save(phase: String) {
            record.put("phase", phase).put("updatedAtBootMs", SystemClock.elapsedRealtime())
            val stream = evidenceFile.startWrite()
            try { stream.write(record.toString(2).toByteArray(Charsets.UTF_8)); evidenceFile.finishWrite(stream) }
            catch (error: Throwable) { evidenceFile.failWrite(stream); throw error }
        }
        var failure: Throwable? = null
        try {
            save("preparing")
            main { ownsBinding = context.bindService(Intent(context, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE); assertTrue(ownsBinding) }
            assertTrue(bound.await(5, TimeUnit.SECONDS))
            main { assertFalse("Stop the main session first", session.connected || session.busy || session.controlsEnabled) }
            project.claim(86, "/endurance", listOf("-p", "trial:=$trial"))
            main { ownsSession = true; session.startControls(listOf(observed), RobotSessionService.Profile(SessionConfig("/endurance", 86), "", "")) }
            waitFor("Dashboard not ready") { session.controlsEnabled && !session.busy }
            project.start()
            waitFor("No first sample") { session.dashboardReadings[observed.id]?.let { JSONObject(it.value).optString("trial") == trial } == true }
            // Observation: sample every 100 ms; record the largest receipt gap and publish-to-receipt latency.
            val end = SystemClock.elapsedRealtime() + minutes * 60_000L
            var lastTick = -1L; var lastReceipt = 0L; var samples = 0L; var minuteSamples = 0L
            var largestGap = 0L; var largestLatency = 0L; var latencyTotal = 0L; var minuteGap = 0L
            var nextReport = SystemClock.elapsedRealtime() + 60_000L; var minute = 0
            var screenOffMinutes = 0; var idleMinutes = 0; var hottestC = Double.NaN
            fun conditions(second: Int) = RunConditions.sample(context, second).also { entry ->
                entry.optDouble("batteryTempC").takeIf { !it.isNaN() }?.let { hottestC = if (hottestC.isNaN()) it else maxOf(hottestC, it) }
            }
            val initialConditions = conditions(0)
            record.put("initialConditions", initialConditions).put("observationStartedAtBootMs", SystemClock.elapsedRealtime())
            save("observing")
            Log.i("ROS2Endurance", "start conditions=$initialConditions")
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
                    minute++
                    val health = project.status()?.optJSONObject("health")
                    val conditions = conditions(minute * 60)
                    if (!conditions.optBoolean("screenOn", true)) screenOffMinutes++
                    if (conditions.optBoolean("deviceIdle")) idleMinutes++
                    Log.i("ROS2Endurance", "minute=$minute at=$now samples=$minuteSamples largestGapMs=$minuteGap runnerPauses=${health?.optInt("pauses")} mainPauses=${session.pauses.pauses} conditions=$conditions")
                    record.getJSONArray("checkpoints").put(JSONObject().put("atBootMs", now).put("minute", minute)
                        .put("samples", samples).put("minuteSamples", minuteSamples).put("largestGapMs", largestGap)
                        .put("largestPublishToReceiptMs", largestLatency).put("conditions", conditions)
                        .put("runnerHealth", health ?: JSONObject.NULL).put("mainPauses", session.pauses.pauses))
                    save("observing")
                    minuteSamples = 0; minuteGap = 0; nextReport += 60_000L
                }
                SystemClock.sleep(100)
            }
            val finalConditions = conditions(minutes * 60)
            val runnerHealth = project.freshStatus().optJSONObject("health")
            assertNotNull("Runner health unavailable", runnerHealth)
            val summary = "minutes=$minutes samples=$samples largestGapMs=$largestGap largestPublishToReceiptMs=$largestLatency " +
                "meanPublishToReceiptMs=${if (samples > 0) latencyTotal / samples else -1} runnerHealth=$runnerHealth mainPauses=${session.pauses} " +
                "screenOffMinutes=$screenOffMinutes idleMinutes=$idleMinutes hottestBatteryC=$hottestC end conditions=$finalConditions"
            record.put("summary", JSONObject().put("samples", samples).put("largestGapMs", largestGap)
                .put("largestPublishToReceiptMs", largestLatency).put("meanPublishToReceiptMs", if (samples > 0) latencyTotal / samples else -1)
                .put("runnerHealth", runnerHealth).put("mainPauses", session.pauses.pauses)
                .put("screenOffMinutes", screenOffMinutes).put("idleMinutes", idleMinutes)
                .put("hottestBatteryC", if (hottestC.isNaN()) JSONObject.NULL else hottestC).put("endConditions", finalConditions))
            save("checking")
            Log.i("ROS2Endurance", "summary $summary")
            // 5 Hz for the whole window; allow 10% loss for best-effort delivery and polling.
            assertTrue("Too few samples: $summary", samples >= minutes * 60L * 5 * 9 / 10)
            assertEquals("Runner paused: $summary", 0, runnerHealth!!.getInt("pauses"))
            assertEquals("Main app paused: $summary", 0, session.pauses.pauses)
            assertTrue("Receipt gap over 2 s: $summary", largestGap <= 2000)
        } catch (error: Throwable) {
            failure = error; throw error
        } finally {
            // Try every cleanup; report the first problem unless the test body already failed.
            fun cleanup(label: String, action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    Log.e("ROS2Qualification", "cleanup failed: $label", error)
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            val bodyFailed = failure != null
            cleanup("main session") { if (ownsSession) { main { session.stopSession() }; waitFor("Session did not stop") { !session.connected && !session.busy } } }
            cleanup("runner profile") { project.release() }
            cleanup("binding") { if (ownsBinding) main { context.unbindService(connection) } }
            record.put("outcome", if (failure == null) "passed" else "failed")
            failure?.let { record.put("error", "${it.javaClass.simpleName}: ${it.message}") }
            cleanup("evidence") { save("finished") }
            if (!bodyFailed) failure?.let { throw it }
        }
    }
}
