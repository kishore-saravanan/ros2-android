package dev.ros2android.android

import android.content.*
import android.os.*
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opt-in: updating a running runner while the app's controls are bound. The update must end
 * the old process and invalidate controls; after Refresh the runner is Stopped with its saved
 * profile and no replayed start; an explicit Start then produces fresh ROS data.
 *
 * Arguments: runnerUpdatePackage (fixture_cpp or fixture_python) and runnerUpdateApk, an APK of
 * the same package already pushed to /data/local/tmp. Headless; Wi-Fi off; disposable fixtures only.
 */
@RunWith(AndroidJUnit4::class)
class RunnerUpdateTest {
    @Test fun updatedRunnerInvalidatesControlsAndRestartsOnlyWhenAsked() {
        val arguments = InstrumentationRegistry.getArguments()
        val target = arguments.getString("runnerUpdatePackage")
        val apk = arguments.getString("runnerUpdateApk")
        assumeTrue("Opt in with runnerUpdatePackage and runnerUpdateApk", target != null && apk != null)
        assertTrue("Disposable fixtures only", target in setOf("dev.ros2android.android.runner.fixture_cpp", "dev.ros2android.android.runner.fixture_python"))
        assertTrue("Push the update APK to /data/local/tmp first", apk!!.matches(Regex("/data/local/tmp/[A-Za-z0-9_.-]+\\.apk")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Phone-local check requires Wi-Fi off", 0, Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, -1))
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
        fun state() = control.status?.optString("state").orEmpty()
        fun connectAndWait() { main { control.close(); control.connect() }; waitFor("Runner unavailable") { control.poll(); control.status != null } }
        val observed = ControlWidget("update_observed", "Update", "Subscribed value", "/update/observed")
        fun fresh(trial: String) = waitFor("No fresh telemetry for $trial") {
            session.dashboardReadings[observed.id]?.let { JSONObject(it.value).optString("trial") == trial } == true
        }
        var original: JSONObject? = null
        var ownsFixture = false; var ownsSession = false; var controllerReady = false
        main { assertTrue(context.bindService(Intent(context, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE)) }
        try {
            assertTrue(bound.await(5, TimeUnit.SECONDS))
            main {
                assertFalse("Stop the main session first", session.connected || session.busy || session.controlsEnabled)
                control = ProjectController(context, InstalledProjects.list(context).single { it.packageName == target }) {}
                controllerReady = true
            }
            connectAndWait()
            main { assertEquals("Stop the fixture first", "Stopped", state()); original = JSONObject(control.status!!.getJSONObject("config").toString()); ownsFixture = true }
            val before = java.util.UUID.randomUUID().toString()
            val profile = Bundle().apply {
                putString("domain", "85"); putString("namespace", "/update"); putString("peer", ""); putString("local", "")
                putString("ros_args", org.json.JSONArray(listOf("-p", "trial:=$before")).toString())
            }
            command(4, profile)
            main { ownsSession = true; session.startControls(listOf(observed), RobotSessionService.Profile(SessionConfig("/update", 85), "", "")) }
            waitFor("Dashboard not ready") { session.controlsEnabled && !session.busy }
            command(2); waitFor("Runner did not start") { control.poll(); state() == "Running" }
            fresh(before)
            // Update while bound and running, as a PC deploy would.
            val output = instrumentation.uiAutomation.executeShellCommand("pm install -r $apk").let { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
            assertTrue("Update failed: $output", output.contains("Success"))
            waitFor("Controls stayed valid after the update") { control.poll(); control.error != null }
            main { assertTrue("Unexpected controller message: ${control.error}", control.error!!.contains("Runner")) }
            waitFor("Old telemetry must become Stale", 15) { session.dashboardReadings[observed.id]?.state(SystemClock.elapsedRealtime()) == "Stale" }
            connectAndWait()
            main {
                assertEquals("Update must not leave the project running", "Stopped", state())
                val saved = control.status!!.getJSONObject("config")
                assertEquals("Profile not preserved", "85", saved.getString("domain"))
                assertEquals("Profile not preserved", "/update", saved.getString("namespace"))
            }
            SystemClock.sleep(3000) // No replayed START may arrive.
            main { control.poll() }; SystemClock.sleep(500)
            main { assertEquals("Start was replayed after the update", "Stopped", state()) }
            val after = java.util.UUID.randomUUID().toString()
            command(4, Bundle(profile).apply { putString("ros_args", org.json.JSONArray(listOf("-p", "trial:=$after")).toString()) })
            command(2); waitFor("Updated runner did not start") { control.poll(); state() == "Running" }
            fresh(after)
            android.util.Log.i("ROS2Qualification", "runner update: invalidated, Stopped with profile, no replay, explicit restart fresh")
        } finally {
            runCatching { if (ownsSession) { main { session.stopSession() }; waitFor("Session did not stop") { !session.connected && !session.busy } } }
            runCatching {
                if (ownsFixture) {
                    if (control.status == null) connectAndWait()
                    if (state() == "Running") { command(3); waitFor("Fixture did not stop") { control.poll(); state() == "Stopped" } }
                    command(4, Bundle().apply { for (key in listOf("domain", "namespace", "peer", "local", "ros_args")) putString(key, original!!.getString(key)) })
                }
            }
            runCatching { if (controllerReady) main { control.close() } }
            runCatching { main { context.unbindService(connection) } }
        }
    }
}
