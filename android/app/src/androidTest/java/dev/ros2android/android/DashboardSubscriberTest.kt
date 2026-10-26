package dev.ros2android.android

import android.content.*
import android.os.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DashboardSubscriberTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun receivesAllTypesAndRecoversFromStaleWithoutStoppingSensors() {
        assumeTrue("Run tools/pc-dashboard-probe.py with dashboardPeer=true", InstrumentationRegistry.getArguments().getString("dashboardPeer") == "true")
        lateinit var robot: RobotSessionService
        lateinit var context: Context
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { robot = (binder as RobotSessionService.LocalBinder).session; latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        activity.scenario.onActivity { context = it; it.bindService(Intent(it, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE) }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        fun waitFor(label: String, check: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 25000; var passed = false
            while (!passed && SystemClock.elapsedRealtime() < deadline) { activity.scenario.onActivity { passed = check() }; SystemClock.sleep(100) }
            assertTrue(label, passed)
        }
        val widgets = ControlWidget.SUB_TYPES.keys.map { type ->
            val id = type.substringAfterLast('/').lowercase()
            ControlWidget(id, type, "Subscribed value", "/phone/dashboard_test/$id", messageType = type)
        }
        val prefs = context.getSharedPreferences("control_dashboard_v1", Context.MODE_PRIVATE)
        val original = prefs.getString("widgets", null)
        val saved = org.json.JSONArray()
        widgets.forEach { w -> saved.put(org.json.JSONObject().put("id", w.id).put("label", w.label).put("type", w.type)
            .put("topic", w.topic).put("value", w.value).put("min", w.minimum).put("max", w.maximum)
            .put("linear", w.linear).put("angular", w.angular).put("messageType", w.messageType)) }
        prefs.edit().putString("widgets", saved.toString()).commit()
        lateinit var root: android.view.View
        fun displayed(id: String, field: String): String = root.findViewWithTag<android.widget.TextView>("reading-$field:$id").text.toString()
        val profile = RobotSessionService.Profile(SessionConfig("/phone", 0), "", "")
        try {
            activity.scenario.onActivity { app -> robot.startPublisher(CapabilityCatalog.publishers(app).single { it.id == "battery" }, EndpointConfig("/phone/battery_percent", 1.0), profile) }
            waitFor("Battery startup") { !robot.busy && "battery" in robot.active }
            activity.scenario.onActivity { robot.subscribe("/phone/dashboard_test/display", profile) }
            waitFor("Legacy display startup") { !robot.busy && robot.subscribed }
            activity.scenario.onActivity { robot.startControls(widgets, profile) }
            waitFor("Dashboard startup") { !robot.busy && robot.controlsEnabled }
            activity.scenario.onActivity { app ->
                assertTrue("Subscribers should wait before receiving", robot.dashboardReadings.isEmpty())
                app.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.view.View>("nav:Dashboard").performClick()
                root = app.findViewById(android.R.id.content)
                assertTrue(root.findViewWithTag<android.view.View>("nav:Dashboard").isSelected)
                assertEquals("Waiting", displayed("string", "state"))
            }
            waitFor("PC→phone typed samples") {
                val r = robot.dashboardReadings
                r["string"]?.value == "Telemetry 🤖" && r["bool"]?.value == "true" &&
                    r["int32"]?.value == "42" && r["int64"]?.value == "9223372036854775806" &&
                    r["float32"]?.value == "-0.25" && r["float64"]?.value == "0.625" &&
                    robot.received == "Legacy display okay"
            }
            activity.scenario.onActivity { app ->
                assertEquals("Telemetry 🤖", displayed("string", "value"))
                assertEquals("● On", displayed("bool", "value"))
                assertEquals("Live", displayed("string", "state"))
                // Render only the app content on the real device; never capture another app.
                check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
                check(app.hasWindowFocus() && app.componentName.packageName == "dev.ros2android.android")
                val scroll = root.findViewWithTag<android.widget.ScrollView>("page_scroll")
                scroll.scrollTo(0, 180)
                val image = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(image))
                java.io.File(app.cacheDir, "dashboard-values-proof.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            waitFor("Old values must become stale") {
                robot.dashboardReadings.size == 6 && robot.dashboardReadings.values.all { it.state(SystemClock.elapsedRealtime()) == "Stale" } && displayed("string", "state") == "Stale"
            }
            var baseline = 0L
            activity.scenario.onActivity { baseline = robot.dashboardReadings.getValue("bool").receivedAt }
            waitFor("New samples must recover to Live") {
                val sample = robot.dashboardReadings["bool"]
                sample != null && sample.value == "false" && sample.receivedAt > baseline && sample.state(SystemClock.elapsedRealtime()) == "Live" && displayed("bool", "value") == "○ Off" && displayed("bool", "state") == "Live"
            }
            activity.scenario.onActivity {
                robot.stopControls()
                assertFalse(robot.controlsEnabled); assertTrue(robot.dashboardReadings.isEmpty())
                assertEquals("Stopped", displayed("string", "state")); assertEquals("—", displayed("string", "value"))
                assertTrue("Dashboard stop affected sensors/display", robot.connected && "battery" in robot.active && robot.subscribed)
            }
        } finally {
            if (original == null) prefs.edit().remove("widgets").commit() else prefs.edit().putString("widgets", original).commit()
            context.unbindService(connection)
        }
    }
}
