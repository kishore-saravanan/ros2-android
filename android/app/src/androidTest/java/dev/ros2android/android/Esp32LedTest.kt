package dev.ros2android.android

import android.content.*
import android.os.*
import android.view.View
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical LED command and telemetry trial; saves the requested ESP32 LED switch. */
@RunWith(AndroidJUnit4::class)
class Esp32LedTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun dashboardSwitchHoldsLedUntilTurnedOff() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("usbLedConnected") == "true")
        lateinit var robot: RobotSessionService
        lateinit var context: Context
        val latch = CountDownLatch(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    robot = (binder as RobotSessionService.LocalBinder).session
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {}
            }
        activity.scenario.onActivity { app ->
            check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            context = app.applicationContext
            context.bindService(
                Intent(app, RobotSessionService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            app.startForegroundService(
                Intent(app, MicroRosAgentService::class.java)
                    .putExtra(MicroRosAgentService.USB, true)
            )
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        fun inspect(check: () -> Boolean): Boolean {
            var result = false
            activity.scenario.onActivity { result = check() }
            return result
        }
        fun waitFor(message: String, check: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 45000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (inspect(check)) return
                SystemClock.sleep(50)
            }

            fail("$message; agent=${MicroRosAgentService.state}; errors=${robot.errors}")
        }

        val prefs = context.getSharedPreferences("control_dashboard_v1", Context.MODE_PRIVATE)
        val original = prefs.getString("widgets", null)
        var passed = false

        try {
            val saved = JSONArray(original ?: "[]")
            var id = "esp32_led"
            val matches =
                (0 until saved.length())
                    .map { saved.getJSONObject(it) }
                    .filter { it.optString("topic") in listOf("/esp32/led_flash", "/esp32/led") }
            require(matches.size <= 1) { "Resolve duplicate LED widgets before migrating" }
            val item =
                if (matches.isEmpty()) {
                    JSONObject().put("id", id).also { saved.put(it) }
                } else {
                    matches.single().also { id = it.getString("id") }
                }
            item
                .put("label", "ESP32 LED")
                .put("type", "Switch")
                .put("topic", "/esp32/led")
                .put("value", "true")
                .put("min", 0.0)
                .put("max", 1.0)
                .put("linear", 0.3)
                .put("angular", 0.8)
                .put("messageType", "std_msgs/msg/String")
            prefs.edit().putString("widgets", saved.toString()).commit()
            val command = ControlWidget(id, "ESP32 LED", "Switch", "/esp32/led", "true")
            val state =
                ControlWidget(
                    "led_test_state",
                    "LED state",
                    "Subscribed value",
                    "/esp32/led_state",
                    messageType = "std_msgs/msg/Bool",
                )
            val heartbeat =
                ControlWidget(
                    "led_test_heartbeat",
                    "Heartbeat",
                    "Subscribed value",
                    "/esp32/heartbeat",
                    messageType = "std_msgs/msg/Int32",
                )
            activity.scenario.onActivity {
                robot.stopControls()
                robot.startControls(
                    listOf(command, state, heartbeat),
                    RobotSessionService.Profile(SessionConfig("/phone", 0), "", ""),
                )
            }

            waitFor("Dashboard not running") {
                robot.controlsEnabled && !robot.busy && MicroRosAgentService.running
            }

            waitFor("MCU heartbeat did not reach phone") {
                robot.dashboardReadings[heartbeat.id] != null
            }

            activity.scenario.onActivity { app ->
                app.findViewById<View>(android.R.id.content)
                    .findViewWithTag<View>("nav:Dashboard")
                    .performClick()
            }

            SystemClock.sleep(6000) // XRCE entity creation and PC discovery.
            activity.scenario.onActivity { app ->
                check(
                    app.hasWindowFocus() &&
                        !app.getSystemService(android.app.KeyguardManager::class.java)
                            .isKeyguardLocked
                )
                val button =
                    app.findViewById<View>(android.R.id.content)
                        .findViewWithTag<View>("control:$id")
                assertTrue("Button is disabled", button.isEnabled)
                button.performClick()
                assertTrue("Switch did not turn on", (button as android.widget.Switch).isChecked)
            }

            waitFor("Phone did not publish Bool command") {
                NativeRuntime.publishedCount("control:$id") > 0
            }

            waitFor("ESP32 did not acknowledge LED on") {
                robot.dashboardReadings[state.id]?.value == "true"
            }

            SystemClock.sleep(3000)
            var onAt = 0L
            var writes = 0L
            activity.scenario.onActivity { app ->
                assertEquals(
                    "LED switched off without an off command",
                    "true",
                    robot.dashboardReadings.getValue(state.id).value,
                )
                onAt = robot.dashboardReadings.getValue(state.id).receivedAt
                writes = NativeRuntime.publishedCount("control:$id")
                app.findViewById<View>(android.R.id.content)
                    .findViewWithTag<View>("nav:Overview")
                    .performClick()
                app.findViewById<View>(android.R.id.content)
                    .findViewWithTag<View>("nav:Dashboard")
                    .performClick()
                val toggle =
                    app.findViewById<View>(android.R.id.content)
                        .findViewWithTag<android.widget.Switch>("control:$id")
                assertTrue("Navigation lost the requested switch value", toggle.isChecked)
            }

            activity.scenario.recreate()
            lateinit var restoredSwitch: android.widget.Switch
            activity.scenario.onActivity { app ->
                restoredSwitch =
                    app.findViewById<View>(android.R.id.content).findViewWithTag("control:$id")
            }

            waitFor("Recreation lost the requested switch value") { restoredSwitch.isChecked }
            SystemClock.sleep(600)
            activity.scenario.onActivity { app ->
                assertEquals(
                    "Navigation published an unsolicited value",
                    writes,
                    NativeRuntime.publishedCount("control:$id"),
                )
                app.findViewById<View>(android.R.id.content)
                    .findViewWithTag<View>("control:$id")
                    .performClick()
            }

            waitFor("ESP32 did not turn LED off after the second press") {
                robot.dashboardReadings[state.id]?.let {
                    it.value == "false" && it.receivedAt > onAt
                } == true
            }

            android.util.Log.i(
                "ROS2LedVerification",
                "Dashboard switch ON held for 3 seconds, survived navigation/recreation " +
                    "without another publish, then OFF returned from MCU; optical LED " +
                    "confirmation separate",
            )
            passed = true
        } finally {
            activity.scenario.onActivity { robot.stopControls() }

            if (!passed) {
                if (original == null) {
                    prefs.edit().remove("widgets").commit()
                } else {
                    prefs.edit().putString("widgets", original).commit()
                }
            }

            context.unbindService(connection)
        }
    }
}
