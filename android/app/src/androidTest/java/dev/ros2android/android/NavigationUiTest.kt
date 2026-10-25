package dev.ros2android.android

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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

/** Real lifecycle/state coverage for navigation, drafts and the optional live camera preview. */
@RunWith(AndroidJUnit4::class)
class NavigationUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private fun find(root: View, key: String): View? {
        if (root.tag == key || root.contentDescription == key) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), key)?.let { return it }
        return null
    }
    private fun click(key: String) { activity.scenario.onActivity { find(it.window.decorView, key)!!.performClick() } }
    private fun waitFor(label: String, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(label, check())
    }
    @Test fun sensorDraftAndSelectionSurviveNavigationAndRecreation() {
        click("nav:Sensors"); click("sensor:imu")
        var old = ""
        activity.scenario.onActivity {
            val field = find(it.window.decorView, "topic:imu") as EditText
            old = field.text.toString(); field.setText("/phone/draft_imu")
        }
        try {
            click("nav:Connect"); click("nav:Sensors")
            activity.scenario.recreate()
            activity.scenario.onActivity {
                assertTrue(find(it.window.decorView, "nav:Sensors")!!.isSelected)
                val field = find(it.window.decorView, "topic:imu") as EditText
                assertTrue(field.isShown); assertEquals("/phone/draft_imu", field.text.toString())
            }
        } finally {
            activity.scenario.onActivity { (find(it.window.decorView, "topic:imu") as EditText).setText(old) }
            click("nav:Overview")
        }
    }
    @Test fun displaySubscriberControlsRemainUsableAfterNavigation() {
        click("nav:Sensors"); click("filter:Display")
        fun enabled(): Boolean {
            var result = false
            activity.scenario.onActivity { result = (find(it.window.decorView, "toggle:display") as Button).isEnabled }
            return result
        }
        waitFor("Display controls did not bind", ::enabled)
        try {
            click("toggle:display")
            waitFor("Display subscription did not start") {
                var result = false
                activity.scenario.onActivity {
                    val toggle = find(it.window.decorView, "toggle:display") as Button
                    result = toggle.isEnabled && toggle.text.toString() == "Stop" &&
                        !(find(it.window.decorView, "topic:display") as EditText).isEnabled
                }
                result
            }
            click("nav:Connect"); click("nav:Help"); click("nav:Sensors")
            activity.scenario.onActivity {
                assertEquals("Stop", (find(it.window.decorView, "toggle:display") as Button).text.toString())
            }
            click("toggle:display")
            activity.scenario.onActivity { assertTrue((find(it.window.decorView, "topic:display") as EditText).isEnabled) }
        } finally {
            click("stop_session")
            waitFor("Display session did not stop") {
                var result = false
                activity.scenario.onActivity { result = !(find(it.window.decorView, "stop_session") as Button).isEnabled }
                result && NativeRuntime.status().contains("stopped")
            }
        }
    }

    @Test fun cameraPreviewAndPublicationSurviveNavigation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live preview test requires opt-in", args.getString("testUiCamera") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        lateinit var session: RobotSessionService
        val bound = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                session = (binder as RobotSessionService.LocalBinder).session; bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        context.bindService(Intent(context, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue(bound.await(5, TimeUnit.SECONDS))
        val cap = CapabilityCatalog.publishers(context).first { it.kind == 8 && it.label.startsWith("Back") }
        try {
            click("nav:Sensors"); click("filter:Cameras"); click("sensor:${cap.id}")
            activity.scenario.onActivity {
                session.startPublisher(cap, EndpointConfig(cap.defaultTopic, 30.0), RobotSessionService.Profile(
                    SessionConfig("/phone", 0), args.getString("pcPeer", ""), args.getString("localAddress", "")))
            }
            waitFor("No decoded live preview") { session.cameraPreview != null }
            var imageShown = false
            activity.scenario.onActivity {
                fun hasImage(view: View): Boolean = (view is ImageView && view.drawable != null) ||
                    (view is ViewGroup && (0 until view.childCount).any { i -> hasImage(view.getChildAt(i)) })
                imageShown = hasImage(it.window.decorView)
            }
            // The bitmap is polled into the UI every 500 ms.
            if (!imageShown) { SystemClock.sleep(700); activity.scenario.onActivity {
                fun hasImage(view: View): Boolean = (view is ImageView && view.drawable != null) ||
                    (view is ViewGroup && (0 until view.childCount).any { i -> hasImage(view.getChildAt(i)) })
                imageShown = hasImage(it.window.decorView)
            } }
            assertTrue("ImageView has no live thumbnail", imageShown)
            val front = CapabilityCatalog.publishers(context).firstOrNull { it.kind == 8 && it.label.startsWith("Front") }
            if (front != null) {
                click("detail_back"); click("sensor:${front.id}")
                assertFalse(session.cameraPreviewEnabled)
                click("detail_back"); click("sensor:${cap.id}")
                waitFor("Preview did not return after camera settings switch") { session.cameraPreview != null }
            }
            val initial = NativeRuntime.publishedCount(cap.id)
            SystemClock.sleep(5000)
            val fps = (NativeRuntime.publishedCount(cap.id) - initial) / 5.0
            android.util.Log.i("ROS2UiVerification", "Preview-visible local ROS publish FPS=$fps")
            assertTrue("Preview slows local ROS publication: $fps", fps >= 20)
            click("nav:Overview")
            assertFalse(session.cameraPreviewEnabled)
            val before = NativeRuntime.publishedCount(cap.id)
            waitFor("Navigation stopped camera publication") { NativeRuntime.publishedCount(cap.id) > before + 10 }
            click("nav:Sensors")
            activity.scenario.recreate()
            waitFor("Preview missing after recreation") { session.cameraPreview != null }
            assertTrue(session.active.contains(cap.id))
        } finally {
            instrumentation.runOnMainSync { session.stopSession() }
            waitFor("Camera session did not stop") { !session.busy && !session.connected }
            context.unbindService(connection)
        }
    }
}
