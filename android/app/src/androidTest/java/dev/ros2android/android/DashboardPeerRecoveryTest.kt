package dev.ros2android.android

import android.app.KeyguardManager
import android.content.*
import android.os.*
import android.provider.Settings
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

/** Real phone-local DDS to an opt-in harmless peer in a separate runner process. */
@RunWith(AndroidJUnit4::class)
class DashboardPeerRecoveryTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun cppLateSubscriberAndPeerRecovery() = qualify("cpp")
    @Test fun pythonLateSubscriberAndPeerRecovery() = qualify("python")

    private fun qualify(backend: String) {
        val target = "dev.ros2android.android.runner.fixture_$backend"
        assumeTrue("Install the disposable fixture and opt in", InstrumentationRegistry.getArguments().getString("dashboardRecoveryPackage") in setOf(target, "both"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Fresh offline startup requires Wi-Fi off", 0,
            Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, -1))
        lateinit var session: RobotSessionService
        lateinit var control: ProjectController
        val bound = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                session = (binder as RobotSessionService.LocalBinder).session; bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun waitFor(label: String, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 20000
            var passed = false
            while (!passed && SystemClock.elapsedRealtime() < end) {
                main { passed = predicate() }
                if (!passed) SystemClock.sleep(100)
            }
            assertTrue(label, passed)
        }
        fun command(value: Int, arguments: Bundle = Bundle()) {
            var previous: JSONObject? = null
            main { previous = control.status; control.send(value, arguments) }
            waitFor("No authenticated reply for $value") {
                check(control.error == null) { control.error.orEmpty() }
                check(control.commandError == null) { control.commandError.orEmpty() }
                control.status != null && control.status !== previous
            }
        }
        fun state(expected: String) = waitFor("Fixture did not become $expected") {
            control.poll()
            val observed = control.status?.optString("state").orEmpty()
            check(!observed.startsWith("Failed")) { control.status.toString() }
            observed == expected
        }
        val toggle = ControlWidget("recovery_request", "Test request", "Switch", "/qualification/request")
        val observed = ControlWidget("recovery_observed", "Peer observation", "Subscribed value", "/qualification/observed")
        val widgets = listOf(toggle, observed)
        val profile = RobotSessionService.Profile(SessionConfig("/qualification", 87), "", "")
        fun reading(): JSONObject? = session.dashboardReadings[observed.id]?.let { JSONObject(it.value) }
        fun request(on: Boolean) {
            main { session.control(toggle, if (on) 1.0 else 0.0) }
            waitFor("No local write") {
                session.switchDescription(toggle.id).contains("Requested ${if (on) "ON" else "OFF"} · written locally")
            }
            main { assertTrue(session.switchDescription(toggle.id).contains("Remote state unknown")) }
        }
        fun observeCount(count: Int, last: Boolean?, milliseconds: Long = 2000) {
            waitFor("Expected peer count $count, last=$last") {
                reading()?.let { it.getInt("count") == count &&
                    (if (last == null) it.isNull("last") else !it.isNull("last") && it.getBoolean("last") == last) } == true
            }
            var firstTick = 0L
            main { firstTick = reading()!!.getLong("tick") }
            val end = SystemClock.elapsedRealtime() + milliseconds
            while (SystemClock.elapsedRealtime() < end) {
                main {
                    val value = checkNotNull(reading())
                    assertEquals("Unexpected command replay", count, value.getInt("count"))
                    assertEquals("Live", session.dashboardReadings[observed.id]!!.state(SystemClock.elapsedRealtime()))
                }
                SystemClock.sleep(100)
            }
            main { assertTrue("No advancing peer telemetry", reading()!!.getLong("tick") > firstTick) }
        }
        var controllerReady = false
        var ownsFixture = false
        var ownsSession = false
        var original: JSONObject? = null
        main { assertTrue(context.bindService(Intent(context, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE)) }
        try {
            assertTrue(bound.await(5, TimeUnit.SECONDS))
            activity.scenario.onActivity { app ->
                check(app.hasWindowFocus() && !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                assertFalse("Stop the main session before qualification", session.connected || session.busy || session.controlsEnabled)
                control = ProjectController(context, InstalledProjects.list(context).single { it.packageName == target }) {}
                controllerReady = true; control.connect()
            }
            waitFor("Fixture unavailable") { control.poll(); control.status != null }
            main {
                assertEquals("Stop disposable fixture first", "Stopped", control.status!!.getString("state"))
                original = JSONObject(control.status!!.getJSONObject("config").toString())
                ownsFixture = true
            }
            command(4, Bundle().apply {
                putString("domain", "87"); putString("namespace", "/qualification")
                putString("peer", ""); putString("local", ""); putString("ros_args", "[]")
            })
            main { ownsSession = true; session.startControls(widgets, profile) }
            waitFor("Dashboard not ready") { session.controlsEnabled && !session.busy }
            request(true) // Written before any subscriber exists: never a remote acknowledgement.
            main { assertTrue(session.dashboardReadings.isEmpty()) }
            command(2); state("Running")
            observeCount(0, null) // Volatile late subscriber must not get the old ON request.
            request(false); observeCount(1, false)
            request(true); observeCount(2, true)
            command(3); state("Stopped")
            waitFor("Lost peer must become Stale") {
                session.dashboardReadings[observed.id]?.state(SystemClock.elapsedRealtime()) == "Stale"
            }
            main { assertTrue(session.connected && session.controlsEnabled) }
            request(false) // Local success with absent peer must still say remote state unknown.
            command(2); state("Running")
            observeCount(0, null)
            request(true); observeCount(1, true)
            main { session.stopControls(); session.startControls(widgets, profile) }
            waitFor("Dashboard restart not ready") { session.controlsEnabled && !session.busy }
            observeCount(1, true) // Restart cannot replay the last requested ON.
            request(false); observeCount(2, false)
            android.util.Log.i("ROS2Qualification", "$backend: offline late subscriber, peer loss/Stale, restart/no replay and explicit fresh commands passed")
        } finally {
            if (ownsSession) {
                main { session.stopSession() }
                waitFor("Main test session did not stop") { !session.connected && !session.busy }
            }
            if (ownsFixture) {
                command(3); state("Stopped")
                command(4, Bundle().apply {
                    for (key in listOf("domain", "namespace", "peer", "local", "ros_args")) putString(key, original!!.getString(key))
                })
                main {
                    val restored = control.status!!.getJSONObject("config")
                    for (key in listOf("domain", "namespace", "peer", "local", "ros_args"))
                        assertEquals("Fixture profile not restored", original!!.getString(key), restored.getString(key))
                }
            }
            if (controllerReady) main { control.close() }
            main { context.unbindService(connection) }
        }
    }
}
