package dev.ros2android.android

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EndpointUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    private fun find(root: View, description: String): View? {
        if (root.contentDescription == description || root.tag == description) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                find(root.getChildAt(i), description)?.let {
                    return it
                }
            }
        }

        return null
    }

    @Test
    fun editedTopicStartsRealPublisherAndLocksUntilStopped() {
        var oldTopic = "/phone/imu"
        var oldRate = "50.0"

        try {
            activity.scenario.onActivity { app ->
                (find(app.window.decorView, "nav:Sensors") as Button).performClick()
                find(app.window.decorView, "sensor:imu")!!.performClick()
            }

            val boundDeadline = SystemClock.elapsedRealtime() + 5000
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < boundDeadline) {
                activity.scenario.onActivity { app ->
                    ready = (find(app.window.decorView, "toggle:imu") as Button).isEnabled
                }

                if (!ready) SystemClock.sleep(50)
            }

            assertTrue("Service did not bind", ready)
            activity.scenario.onActivity { app ->
                val root = app.window.decorView
                val topic = find(root, "topic:imu") as EditText
                val rate = find(root, "rate:imu") as EditText
                oldTopic = topic.text.toString()
                oldRate = rate.text.toString()
                topic.setText("/phone/verification/imu")
                rate.setText("20")
                (find(root, "toggle:imu") as Button).performClick()
            }

            val deadline = SystemClock.elapsedRealtime() + 8000

            while (
                SystemClock.elapsedRealtime() < deadline && NativeRuntime.publishedCount("imu") < 3
            ) {
                SystemClock.sleep(100)
            }

            assertTrue(
                "UI did not start a real publisher: ${NativeRuntime.status()}",
                NativeRuntime.publishedCount("imu") >= 3,
            )
            activity.scenario.onActivity { app ->
                val root = app.window.decorView
                assertFalse((find(root, "topic:imu") as EditText).isEnabled)
                val toggle = find(root, "toggle:imu") as Button
                assertEquals("Stop", toggle.text.toString())
                toggle.performClick()
                assertTrue((find(root, "topic:imu") as EditText).isEnabled)
            }
        } finally {
            activity.scenario.onActivity { app ->
                val root = app.window.decorView
                val button = find(root, "toggle:imu") as Button
                if (button.text.toString() == "Stop") button.performClick()
                (find(root, "topic:imu") as EditText).setText(oldTopic)
                (find(root, "rate:imu") as EditText).setText(oldRate)
                (find(root, "stop_session") as Button).performClick()
            }

            val stoppedDeadline = SystemClock.elapsedRealtime() + 5000
            var stopped = false
            while (!stopped && SystemClock.elapsedRealtime() < stoppedDeadline) {
                activity.scenario.onActivity { app ->
                    stopped = NativeRuntime.status().contains("stopped")
                }

                if (!stopped) SystemClock.sleep(50)
            }

            assertTrue("Session did not finish cleanup", stopped)
        }
    }
}
