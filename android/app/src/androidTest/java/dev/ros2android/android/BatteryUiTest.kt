package dev.ros2android.android

import android.os.BatteryManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatteryUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private fun find(view: View, tag: String): View? {
        if (view.tag == tag || view.contentDescription == tag) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), tag)?.let { return it }
        return null
    }
    @Test fun batteryPublishesWithoutSensorPermissionAndSurvivesNavigation() {
        activity.scenario.onActivity { app -> (find(app.window.decorView, "nav:Sensors") as Button).performClick(); find(app.window.decorView, "sensor:battery")!!.performClick() }
        val boundDeadline = SystemClock.elapsedRealtime() + 5000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < boundDeadline) {
            activity.scenario.onActivity { ready = (find(it.window.decorView, "toggle:battery") as Button).isEnabled }
            SystemClock.sleep(50)
        }
        assertTrue("Service did not bind", ready)
        try {
            activity.scenario.onActivity { app ->
                val expected = app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                assertTrue(expected in 0..100)
                android.util.Log.i("ROS2BatteryVerification", "Battery percentage=$expected")
                (find(app.window.decorView, "toggle:battery") as Button).performClick()
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (NativeRuntime.publishedCount("battery") < 3 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("Battery did not publish", NativeRuntime.publishedCount("battery") >= 3)
            activity.scenario.onActivity { app ->
                assertEquals("Stop", (find(app.window.decorView, "toggle:battery") as Button).text.toString())
                (find(app.window.decorView, "nav:Connect") as Button).performClick(); find(app.window.decorView, "nav:Help")!!.performClick()
            }
            val before = NativeRuntime.publishedCount("battery")
            SystemClock.sleep(2500)
            assertTrue("Navigation stopped battery publisher", NativeRuntime.publishedCount("battery") > before)
            activity.scenario.recreate()
            SystemClock.sleep(2000)
            assertTrue("Recreation stopped battery publisher", NativeRuntime.publishedCount("battery") > before + 1)
        } finally {
            activity.scenario.onActivity { (find(it.window.decorView, "stop_session") as Button).performClick() }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!NativeRuntime.status().contains("stopped") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("Battery session did not stop", NativeRuntime.status().contains("stopped"))
        }
    }
}
