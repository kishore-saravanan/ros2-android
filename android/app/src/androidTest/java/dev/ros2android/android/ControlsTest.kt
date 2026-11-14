package dev.ros2android.android

import android.content.*
import android.os.*
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlsTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun widgetEditorValidatesAndPersistsAcrossRecreation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = app.getSharedPreferences("control_dashboard_v1", Context.MODE_PRIVATE)
        val original = prefs.getString("widgets", null)
        prefs.edit().remove("widgets").commit()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun node(
            predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean
        ): android.view.accessibility.AccessibilityNodeInfo? {
            check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            val root = automation.rootInActiveWindow ?: return null
            check(root.packageName?.toString() == app.packageName) {
                "Only project UI may be inspected"
            }

            fun search(
                item: android.view.accessibility.AccessibilityNodeInfo
            ): android.view.accessibility.AccessibilityNodeInfo? {
                if (predicate(item)) return item
                for (i in 0 until item.childCount) {
                    item.getChild(i)?.let { child ->
                        search(child)?.let {
                            return it
                        }
                    }
                }

                return null
            }

            return search(root)
        }

        fun click(text: String) {
            val found =
                node {
                    it.text?.toString()?.equals(text, ignoreCase = true) == true && it.isClickable
                } ?: error("Missing button: $text")
            check(
                found.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            )
            SystemClock.sleep(400)
        }

        /** The field a visible label names for screen readers (labelFor). */
        fun labeled(label: String, className: String) = node {
            it.text?.toString() == label && it.labelFor?.className?.toString() == className
        }
            ?.labelFor
        fun fill(label: String, value: String) {
            val found = labeled(label, "android.widget.EditText") ?: error("Missing field: $label")
            check(
                found.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(
                            android.view.accessibility.AccessibilityNodeInfo
                                .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            value,
                        )
                    },
                )
            )
        }

        try {
            activity.scenario.onActivity {
                it.findViewById<android.view.View>(android.R.id.content)
                    .findViewWithTag<android.view.View>("nav:Dashboard")
                    .performClick()
            }

            SystemClock.sleep(500)
            click("Add widget")
            fill("Label", "UI test button")
            fill("Topic", "/9invalid")
            click("Save")
            assertNotNull(node { it.text?.toString()?.contains("absolute ROS topic") == true })
            fill("Topic", "/phone/control_test/ui_button")
            fill("Button value", "false")
            click("Save")
            assertNotNull(
                node {
                    it.text?.toString() == "UI test button" &&
                        it.className?.toString() == "android.widget.TextView"
                }
            )
            assertNull(
                node {
                    it.text?.toString()?.equals("Save", ignoreCase = true) == true && it.isClickable
                }
            )
            activity.scenario.recreate()
            SystemClock.sleep(1000)
            assertNotNull(
                node {
                    it.text?.toString() == "UI test button" &&
                        it.className?.toString() == "android.widget.TextView"
                }
            )
            assertNotNull(node { it.text?.toString() == "Start dashboard" })
            click("Add widget")
            fill("Label", "Subscribed test value")
            fill("Topic", "/phone/dashboard_test/ui_waiting")
            val direction =
                labeled("Direction", "android.widget.Spinner")
                    ?: error("Direction selector missing")
            check(
                direction.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                )
            )
            SystemClock.sleep(300)
            var subscribe =
                node { it.text?.toString() == "Subscribe" } ?: error("Subscribe option missing")
            while (!subscribe.isClickable) subscribe =
                subscribe.parent ?: error("Subscribe option cannot be selected")
            check(
                subscribe.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                )
            )
            SystemClock.sleep(300)
            assertNull(
                "Publisher value must be hidden for subscribers",
                labeled("Button value", "android.widget.EditText"),
            )
            click("Save")
            val stored = org.json.JSONArray(prefs.getString("widgets", "[]"))
            val subscriber = stored.getJSONObject(1)
            assertEquals("Subscribed value", subscriber.getString("type"))
            assertEquals("std_msgs/msg/String", subscriber.getString("messageType"))
            activity.scenario.recreate()
            SystemClock.sleep(600)
            activity.scenario.onActivity { app ->
                val root = app.findViewById<android.view.View>(android.R.id.content)
                assertEquals(
                    "Stopped",
                    root
                        .findViewWithTag<android.widget.TextView>(
                            "reading-state:" + subscriber.getString("id")
                        )
                        .text
                        .toString(),
                )
            }

            click("Start dashboard")
            SystemClock.sleep(1000)
            activity.scenario.onActivity { app ->
                val root = app.findViewById<android.view.View>(android.R.id.content)
                assertEquals(
                    "Waiting",
                    root
                        .findViewWithTag<android.widget.TextView>(
                            "reading-state:" + subscriber.getString("id")
                        )
                        .text
                        .toString(),
                )
            }
        } finally {
            activity.scenario.onActivity { app ->
                app.findViewById<android.view.View>(android.R.id.content)
                    .findViewWithTag<android.view.View>("stop_session")
                    ?.performClick()
            }

            if (original == null) {
                prefs.edit().remove("widgets").commit()
            } else {
                prefs.edit().putString("widgets", original).commit()
            }
        }
    }

    @Test
    fun joystickReleaseAndFocusLossSendZero() {
        val commands = mutableListOf<Pair<Double, Double>>()
        lateinit var view: JoystickView
        activity.scenario.onActivity { app ->
            view = JoystickView(app) { x, y -> commands += x to y }
            app.setContentView(view)
        }

        SystemClock.sleep(300)
        fun touch(action: Int) {
            activity.scenario.onActivity {
                val now = SystemClock.uptimeMillis()
                val event =
                    android.view.MotionEvent.obtain(
                        now,
                        now,
                        action,
                        view.width / 2f,
                        view.height / 2f - minOf(view.width, view.height) * 0.3f,
                        0,
                    )

                try {
                    view.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }

        touch(android.view.MotionEvent.ACTION_DOWN)
        SystemClock.sleep(250)
        activity.scenario.onActivity { assertTrue(commands.any { it.second > 0.0 }) }
        touch(android.view.MotionEvent.ACTION_UP)
        activity.scenario.onActivity { assertEquals(0.0 to 0.0, commands.last()) }
        touch(android.view.MotionEvent.ACTION_DOWN)
        SystemClock.sleep(200)
        activity.scenario.onActivity {
            view.onWindowFocusChanged(false)
            assertEquals(0.0 to 0.0, commands.last())
        }
    }

    @Test
    fun controlsPersistAcrossNavigationAndPauseWithMotionStopped() {
        lateinit var service: RobotSessionService
        lateinit var context: Context
        val latch = CountDownLatch(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service = (binder as RobotSessionService.LocalBinder).session
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {}
            }
        activity.scenario.onActivity {
            context = it
            it.bindService(
                Intent(it, RobotSessionService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        fun waitFor(message: String, check: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10000
            var passed = false
            while (!passed && SystemClock.elapsedRealtime() < deadline) {
                activity.scenario.onActivity { passed = check() }
                SystemClock.sleep(100)
            }

            assertTrue(message, passed)
        }

        val profile = RobotSessionService.Profile(SessionConfig("/phone", 0), "", "")
        val button =
            ControlWidget("test_bool", "Test Bool", "Button · Bool", "/phone/control_test/bool")
        val string =
            ControlWidget(
                "test_string",
                "Test String",
                "Button · String",
                "/phone/control_test/string",
                "Hello 🤖",
            )
        val slider =
            ControlWidget("test_slider", "Test Slider", "Slider", "/phone/control_test/slider")
        val joystick =
            ControlWidget("test_twist", "Test Joystick", "Joystick", "/phone/control_test/twist")
        val batteryReading =
            ControlWidget(
                "test_battery_reading",
                "Battery",
                "Subscribed value",
                "/phone/battery_percent",
                messageType = "std_msgs/msg/Float64",
            )

        try {
            activity.scenario.onActivity { app ->
                service.startPublisher(
                    CapabilityCatalog.publishers(app).single { it.id == "battery" },
                    EndpointConfig("/phone/battery_percent", 1.0),
                    profile,
                )
            }

            waitFor("Battery startup failed") { !service.busy && "battery" in service.active }
            activity.scenario.onActivity {
                service.startControls(
                    listOf(button, string, slider, joystick, batteryReading),
                    profile,
                )
            }

            waitFor("Control startup failed") { service.controlsEnabled && !service.busy }
            waitFor("Battery dashboard subscription") {
                service.dashboardReadings.containsKey(batteryReading.id)
            }

            activity.scenario.onActivity { app ->
                app.findViewById<android.view.View>(android.R.id.content)
                    .findViewWithTag<android.view.View>("nav:Dashboard")
                    .performClick()
            }

            SystemClock.sleep(
                5000
            ) // Allow the independent PC observer to discover reliable publishers.
            repeat(20) {
                activity.scenario.onActivity {
                    service.control(button, 1.0)
                    service.control(string, text = string.value)
                    service.control(slider, 0.625)
                    service.control(joystick, 0.2, -0.4)
                }

                SystemClock.sleep(100)
            }

            // No renewals: the worker must publish zero after its 300 ms command lease.
            SystemClock.sleep(1500)
            activity.scenario.onActivity { service.control(joystick, 0.2, -0.4) }
            SystemClock.sleep(100)
            activity.scenario.onActivity { app ->
                app.findViewById<android.view.View>(android.R.id.content)
                    .findViewWithTag<android.view.View>("nav:Overview")
                    .performClick()
                assertTrue("Navigation stopped dashboard", service.controlsEnabled)
            }

            SystemClock.sleep(500)
            var beforePause = 0L
            activity.scenario.onActivity {
                beforePause = service.dashboardReadings.getValue(batteryReading.id).receivedAt
                service.control(joystick, 0.2, -0.4)
            }

            SystemClock.sleep(100)
            activity.scenario.moveToState(Lifecycle.State.STARTED)
            SystemClock.sleep(2000)
            assertTrue("Pause stopped dashboard", service.controlsEnabled)
            assertTrue(service.connected && "battery" in service.active)
            activity.scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("Subscription stopped receiving while away") {
                service.dashboardReadings.getValue(batteryReading.id).receivedAt > beforePause
            }

            activity.scenario.onActivity { service.stopControls() }
            assertFalse("Explicit Stop must stop dashboard", service.controlsEnabled)
            assertTrue(service.dashboardReadings.isEmpty())
        } finally {
            activity.scenario.onActivity { service.stopControls() }
            context.unbindService(connection)
        }
    }
}
