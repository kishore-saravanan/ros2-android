package dev.ros2android.android

import android.content.*
import android.os.*
import android.view.View
import android.widget.Switch
import android.widget.TextView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Requires an independent ROS peer on domain 87 echoing request counts to /observed. */
@RunWith(AndroidJUnit4::class)
class DashboardSwitchTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun switchReportsLocalWriteAndPeerObservesOffAcrossNavigationAndRestart() {
        assumeTrue("Requires independent switch peer", InstrumentationRegistry.getArguments().getString("dashboardSwitchPeer") == "true")
        lateinit var service: RobotSessionService
        lateinit var context: Context
        val bound = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as RobotSessionService.LocalBinder).session; bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        activity.scenario.onActivity {
            check(!it.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked && it.hasWindowFocus())
            context = it; it.bindService(Intent(it, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        assertTrue(bound.await(5, TimeUnit.SECONDS))
        fun waitFor(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 20000
            var passed = false
            while (!passed && SystemClock.elapsedRealtime() < deadline) {
                activity.scenario.onActivity { passed = condition() }; SystemClock.sleep(50)
            }
            assertTrue(label, passed)
        }
        val toggle = ControlWidget("switch_regression", "Test switch", "Switch", "/ros2android/switch_test/request")
        val observed = ControlWidget("switch_observed", "Test peer", "Subscribed value", "/ros2android/switch_test/observed", messageType = "std_msgs/msg/String")
        val widgets = listOf(toggle, observed)
        val profile = RobotSessionService.Profile(SessionConfig("/phone", 87), "", "")
        val prefs = context.getSharedPreferences("control_dashboard_v1", Context.MODE_PRIVATE)
        val original = prefs.getString("widgets", null)
        fun navigate(tab: String) = activity.scenario.onActivity {
            it.findViewById<View>(android.R.id.content).findViewWithTag<View>("nav:$tab").performClick()
        }
        fun switch(app: MainActivity): Switch = app.findViewById<View>(android.R.id.content).findViewWithTag("control:${toggle.id}")
        fun click() = activity.scenario.onActivity { switch(it).performClick() }
        try {
            activity.scenario.onActivity { service.stopSession() }
            waitFor("Clean runtime") { !service.busy && !service.connected }
            val saved = JSONArray()
            widgets.forEach { w -> saved.put(JSONObject().put("id", w.id).put("label", w.label).put("type", w.type)
                .put("topic", w.topic).put("value", w.value).put("min", w.minimum).put("max", w.maximum)
                .put("linear", w.linear).put("angular", w.angular).put("messageType", w.messageType)) }
            prefs.edit().putString("widgets", saved.toString()).commit()
            navigate("Overview"); navigate("Dashboard")
            activity.scenario.onActivity { service.startControls(widgets, profile) }
            waitFor("Dashboard ready") { !service.busy && service.controlsEnabled }
            waitFor("Peer discovered and no command sent at startup") { service.dashboardReadings[observed.id]?.value == "0:none" }
            click()
            waitFor("ON reached independent peer") { service.dashboardReadings[observed.id]?.value == "1:true" }
            navigate("Overview"); navigate("Dashboard")
            activity.scenario.onActivity { assertTrue(switch(it).isChecked) }
            click()
            waitFor("OFF reached independent peer") { service.dashboardReadings[observed.id]?.value == "2:false" }
            waitFor("Local status does not claim robot acknowledgement") {
                service.switchDescription(toggle.id).contains("Requested OFF · written locally")
            }
            activity.scenario.onActivity {
                val root = it.findViewById<View>(android.R.id.content)
                val text = root.findViewWithTag<TextView>("control-status:${toggle.id}").text.toString()
                assertTrue(text.contains("Remote state unknown")); assertFalse(switch(it).isChecked)
            }
            click()
            waitFor("Second ON reached peer") { service.dashboardReadings[observed.id]?.value == "3:true" }
            activity.scenario.onActivity { service.stopControls() }
            assertTrue(service.switchDescription(toggle.id).contains("inactive"))
            activity.scenario.onActivity { service.startControls(widgets, profile) }
            waitFor("Restart ready") { !service.busy && service.controlsEnabled }
            waitFor("Restart does not replay ON") { service.dashboardReadings[observed.id]?.value == "3:true" }
            SystemClock.sleep(1000)
            activity.scenario.onActivity { assertEquals("3:true", service.dashboardReadings[observed.id]?.value) }
            click()
            waitFor("OFF after restart reached peer") { service.dashboardReadings[observed.id]?.value == "4:false" }
            activity.scenario.onActivity { app ->
                check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked && app.hasWindowFocus())
                val root = app.findViewById<View>(android.R.id.content)
                val toggleView = switch(app)
                root.findViewWithTag<android.widget.ScrollView>("page_scroll").scrollTo(0,
                    (toggleView.parent as View).top + toggleView.top)
            }
            SystemClock.sleep(200)
            activity.scenario.onActivity { app ->
                check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked && app.hasWindowFocus())
                val root = app.findViewById<View>(android.R.id.content)
                val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                java.io.File(app.cacheDir, "dashboard-switch-proof.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally {
            activity.scenario.onActivity { service.stopSession() }
            if (original == null) prefs.edit().remove("widgets").commit() else prefs.edit().putString("widgets", original).commit()
            context.unbindService(connection)
        }
    }
}
