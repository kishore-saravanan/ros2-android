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
            activity.scenario.onActivity { app ->
                control = ProjectController(app, InstalledProjects.list(app).single { it.packageName == target }) {}
                controlReady = true
                control.connect()
            }
            waitFor("No fixture status") { control.poll(); control.status != null }
        }
        fun state(expected: String) = waitFor("Fixture did not become $expected") {
            control.poll(); control.status?.optString("state") == expected
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
                control.send(4, Bundle().apply {
                    putString("domain", "87"); putString("namespace", "/android_test")
                    putString("peer", ""); putString("local", local); putString("ros_args", "[]")
                })
            }
            waitFor("Fixture profile not applied") {
                control.poll(); control.status?.optJSONObject("config")?.optString("domain") == "87"
            }
            main { control.send(2) }; state("Running")
            main {
                ownsSession = true
                session.startControls(listOf(widget, localEcho), RobotSessionService.Profile(SessionConfig("/qualification", 87), "", local))
            }
            waitFor("No workspace result reached the main app") { session.dashboardReadings[widget.id] != null }
            main { NativeRuntime.createPublisher("qualification_echo", localEcho.topic, 11) }
            fun observe(label: String, milliseconds: Long): Long {
                val end = SystemClock.elapsedRealtime() + milliseconds
                var previous = -1L
                var fresh = 0L
                while (SystemClock.elapsedRealtime() < end) {
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
                    SystemClock.sleep(100)
                }
                assertTrue("$label: insufficient fresh ROS results", fresh >= 5)
                android.util.Log.i("ROS2Qualification", "$label: $fresh distinct verified results")
                return previous
            }
            observe("foreground", 3000)
            if (background) {
                if (!keepBound) main { control.close() }
                activity.scenario.moveToState(Lifecycle.State.CREATED)
                observe("background", 10000)
                activity.scenario.moveToState(Lifecycle.State.RESUMED)
                if (!keepBound) connect()
                state("Running")
            }
            main { control.send(3) }; state("Stopped")
            SystemClock.sleep(6000)
            main { assertEquals("Stale", session.dashboardReadings[widget.id]!!.state(SystemClock.elapsedRealtime())) }
            main { control.send(2) }; state("Running")
            waitFor("Results did not recover after workspace restart") {
                session.dashboardReadings[widget.id]?.state(SystemClock.elapsedRealtime()) == "Live"
            }
            observe("restart", 3000)
        } finally {
            activity.scenario.moveToState(Lifecycle.State.RESUMED)
            if (ownsSession) {
                main { session.stopSession() }
                waitFor("Main test session did not stop") { !session.connected && !session.busy }
            }
            if (ownsFixture) {
                // Reconnect if the test failed while its Activity was backgrounded.
                main { control.close() }
                connect()
                main { control.send(3) }; state("Stopped")
                main { control.send(4, Bundle().apply {
                    for (key in listOf("domain", "namespace", "peer", "local", "ros_args")) putString(key, original!!.getString(key))
                }) }
                waitFor("Fixture configuration was not restored") {
                    control.poll()
                    val config = control.status?.optJSONObject("config")
                    config != null && listOf("domain", "namespace", "peer", "local", "ros_args").all { config.getString(it) == original!!.getString(it) }
                }
            }
            if (controlReady) main { control.close() }
            context.unbindService(connection)
        }
    }
}
