package dev.ros2android.android

import android.app.KeyguardManager
import android.content.*
import android.os.*
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in real ROS traffic from a disposable two-node workspace, without cameras or actuators. */
@RunWith(AndroidJUnit4::class)
class WorkspaceLocalLifecycleTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun offlineResultsRecoverAfterRestart() = qualify(background = false)

    @Test fun offlineResultsContinueInBackgroundAndRecoverAfterRestart() = qualify(background = true)

    private fun qualify(background: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = InstrumentationRegistry.getArguments().getString("workspaceLocalTestPackage")
        assumeTrue("Requires the disposable workspace fixture", target != null)
        require(target == "dev.ros2android.android.runner.ws_protocol_workspace")
        val context = instrumentation.targetContext
        val backgroundSeconds = InstrumentationRegistry.getArguments().getString("workspaceBackgroundSeconds", "10").toLong()
        require(backgroundSeconds in 1..300) { "Use 1–300 seconds for the background trial" }
        val keepBound = InstrumentationRegistry.getArguments().getString("workspaceKeepControllerBound") == "true"
        val local = if (InstrumentationRegistry.getArguments().getString("workspaceLocalOnly") == "true") "127.0.0.1" else ""
        assertEquals("Turn Wi-Fi off before this fresh-start test", 0,
            Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, -1))
        lateinit var session: RobotSessionService
        lateinit var control: ProjectController
        var controlReady = false
        val bound = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                session = (binder as RobotSessionService.LocalBinder).session; bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun waitFor(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            var passed = false
            while (!passed && SystemClock.elapsedRealtime() < deadline) {
                main { passed = condition() }; if (!passed) SystemClock.sleep(100)
            }
            assertTrue(label, passed)
        }
        fun connect() {
            main {
                control = ProjectController(context, InstalledProjects.list(context).single { it.packageName == target }) {}
                controlReady = true
                control.connect()
            }
            waitFor("No fixture status") { control.status != null }
        }
        fun command(command: Int, config: Bundle = Bundle()) {
            var previous: JSONObject? = null
            main { previous = control.status; control.send(command, config) }
            // A matching cached profile/state is not an acknowledgement of this command.
            waitFor("Runner did not acknowledge command $command") {
                check(control.error == null) { control.error.orEmpty() }
                check(control.commandError == null) { control.commandError.orEmpty() }
                control.status != null && control.status !== previous
            }
        }
        fun state(expected: String) = waitFor("Fixture did not become $expected") {
            if (control.status?.optString("state") == expected) true
            else { control.poll(); false }
        }
        val localEcho = ControlWidget("offline_local_echo", "Local probe", "Subscribed value", "/qualification/local_echo")
        val widget = ControlWidget("offline_result_test", "Workspace result", "Subscribed value", "/android_test/result")
        var original: JSONObject? = null
        var ownsFixture = false
        var ownsSession = false
        val serviceBound = context.bindService(Intent(context, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue(serviceBound)
        try {
            assertTrue("Session did not bind", bound.await(5, TimeUnit.SECONDS))
            activity.scenario.onActivity { app ->
                check(!app.getSystemService(KeyguardManager::class.java).isKeyguardLocked && app.hasWindowFocus())
                assertFalse("Stop the main session before testing", session.connected || session.busy || session.controlsEnabled)
            }
            connect()
            main {
                assertEquals("Stop the disposable fixture first", "Stopped", control.status!!.getString("state"))
                original = JSONObject(control.status!!.getJSONObject("config").toString())
                ownsFixture = true
            }
            command(4, Bundle().apply {
                putString("domain", "87"); putString("namespace", "/android_test")
                putString("peer", ""); putString("local", local); putString("ros_args", "[]")
            })
            main { assertEquals("87", control.status!!.getJSONObject("config").getString("domain")) }
            command(2); state("Running")
            main {
                ownsSession = true
                session.startControls(listOf(widget, localEcho), RobotSessionService.Profile(SessionConfig("/qualification", 87), "", local))
            }
            waitFor("No workspace result reached the main app") { session.dashboardReadings[widget.id] != null }
            main { NativeRuntime.createPublisher("qualification_echo", localEcho.topic, 11) }
            fun observe(label: String, milliseconds: Long): Long {
                val startedAt = SystemClock.elapsedRealtime()
                val end = startedAt + milliseconds
                var lastObservation = startedAt
                var previous = -1L
                var fresh = 0L
                while (SystemClock.elapsedRealtime() < end) {
                    val observedAt = SystemClock.elapsedRealtime()
                    assertTrue("$label: observer paused for more than two seconds", observedAt - lastObservation < 2000)
                    main {
                        assertTrue("Local probe write failed", NativeRuntime.publishControl("qualification_echo", doubleArrayOf(0.0, 0.0), SystemClock.elapsedRealtime().toString()))
                        val reading = checkNotNull(session.dashboardReadings[widget.id])
                        assertTrue("$label: result gap exceeds two seconds; connected=${session.connected}, dashboard=${session.controlsEnabled}, localProbeAge=${session.dashboardReadings[localEcho.id]?.let { SystemClock.elapsedRealtime() - it.receivedAt }}, errors=${session.errors}", SystemClock.elapsedRealtime() - reading.receivedAt < 2000)
                        val value = JSONObject(reading.value)
                        val sequence = value.getLong("sequence")
                        assertTrue("$label: sequence reversed", sequence >= previous)
                        assertEquals("$label: incorrect processor result", sequence * sequence * 0.0625, value.getDouble("squared"), 0.00001)
                        if (sequence != previous) { fresh++; previous = sequence }
                    }
                    assertTrue("$label: main-thread observation paused for more than two seconds", SystemClock.elapsedRealtime() - observedAt < 2000)
                    lastObservation = observedAt
                    SystemClock.sleep(100)
                }
                assertTrue("$label: trial exceeded its duration by more than two seconds", SystemClock.elapsedRealtime() - startedAt < milliseconds + 2000)
                assertTrue("$label: insufficient fresh ROS results", fresh >= maxOf(5, milliseconds / 1000))
                android.util.Log.i("ROS2Qualification", "$label: $fresh distinct verified results")
                return previous
            }
            observe("foreground", 3000)
            if (background) {
                if (!keepBound) main { control.close() }
                activity.scenario.moveToState(Lifecycle.State.CREATED)
                observe("background", backgroundSeconds * 1000)
                activity.scenario.moveToState(Lifecycle.State.RESUMED)
                if (!keepBound) connect()
                state("Running")
            }
            command(3); state("Stopped")
            SystemClock.sleep(6000)
            main { assertEquals("Stale", session.dashboardReadings[widget.id]!!.state(SystemClock.elapsedRealtime())) }
            command(2); state("Running")
            waitFor("Results did not recover after workspace restart") {
                session.dashboardReadings[widget.id]?.state(SystemClock.elapsedRealtime()) == "Live"
            }
            observe("restart", 3000)
        } finally {
            if (ownsSession) {
                main { session.stopSession() }
                waitFor("Main test session did not stop") { !session.connected && !session.busy }
            }
            if (ownsFixture) {
                // Reconnect if the test failed while its Activity was backgrounded.
                main { control.close() }
                connect()
                command(3); state("Stopped")
                command(4, Bundle().apply {
                    for (key in listOf("domain", "namespace", "peer", "local", "ros_args")) putString(key, original!!.getString(key))
                })
                waitFor("Fixture configuration was not restored") {
                    val config = control.status?.optJSONObject("config")
                    config != null && listOf("domain", "namespace", "peer", "local", "ros_args").all { config.getString(it) == original!!.getString(it) }
                }
            }
            if (controlReady) main { control.close() }
            context.unbindService(connection)
            activity.scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }
}
