package dev.ros2android.android

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real lifecycle/state coverage for navigation, drafts and the optional live camera preview. */
@RunWith(AndroidJUnit4::class)
class NavigationUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    private fun find(root: View, key: String): View? {
        if (root.tag == key || root.contentDescription == key) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                find(root.getChildAt(i), key)?.let {
                    return it
                }
            }
        }

        return null
    }

    private fun click(key: String) {
        activity.scenario.onActivity { find(it.window.decorView, key)!!.performClick() }
    }

    private fun waitFor(label: String, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(label, check())
    }

    @Test
    fun robotHelpIsCollapsedAndExplainsIndependentStopScopes() {
        activity.scenario.onActivity { app ->
            find(app.window.decorView, "nav:Connect")!!.performClick()
            find(app.window.decorView, "nav:Help")!!.performClick()
            assertEquals(
                View.GONE,
                find(app.window.decorView, "body:help_robot_operation")!!.visibility,
            )
            find(app.window.decorView, "expand:help_robot_operation")!!.performClick()
            val body = find(app.window.decorView, "body:help_robot_operation") as ViewGroup
            val text =
                (0 until body.childCount)
                    .mapNotNull { (body.getChildAt(it) as? android.widget.TextView)?.text }
                    .joinToString(" ")
            assertTrue(text.contains("Stop project stops that workspace"))
            assertTrue(text.contains("Stop all stops sensors and Dashboard"))
            assertTrue(text.contains("None is a hardware emergency stop"))
            assertTrue(find(app.window.decorView, "nav:Connect")!!.isSelected)
        }
    }

    @Test
    fun sensorDraftAndSelectionSurviveNavigationAndRecreation() {
        click("nav:Sensors")
        click("sensor:imu")
        var old = ""
        activity.scenario.onActivity {
            val field = find(it.window.decorView, "topic:imu") as EditText
            old = field.text.toString()
            field.setText("/phone/draft_imu")
        }

        try {
            click("nav:Connect")
            click("nav:Sensors")
            activity.scenario.recreate()
            activity.scenario.onActivity {
                assertTrue(find(it.window.decorView, "nav:Sensors")!!.isSelected)
                val field = find(it.window.decorView, "topic:imu") as EditText
                assertTrue(field.isShown)
                assertEquals("/phone/draft_imu", field.text.toString())
            }
        } finally {
            activity.scenario.onActivity {
                (find(it.window.decorView, "topic:imu") as EditText).setText(old)
            }

            click("nav:Overview")
        }
    }

    @Test
    fun subscribersBelongInDashboardRatherThanSensors() {
        click("nav:Sensors")
        activity.scenario.onActivity {
            assertNull(find(it.window.decorView, "filter:Display"))
            assertNull(find(it.window.decorView, "topic:display"))
            assertNotNull(find(it.window.decorView, "sensor:battery"))
        }

        click("nav:Dashboard")
        activity.scenario.onActivity {
            assertTrue(find(it.window.decorView, "nav:Dashboard")!!.isSelected)
        }

        click("nav:Sensors")
        activity.scenario.onActivity { assertNull(find(it.window.decorView, "filter:Display")) }
    }

    @Test
    fun cameraPreviewAndPublicationSurviveNavigation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live preview test requires opt-in", args.getString("testUiCamera") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.CAMERA),
        )
        lateinit var session: RobotSessionService
        val bound = CountDownLatch(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    session = (binder as RobotSessionService.LocalBinder).session
                    bound.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {}
            }
        context.bindService(
            Intent(context, RobotSessionService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        assertTrue(bound.await(5, TimeUnit.SECONDS))
        val cap =
            CapabilityCatalog.publishers(context).first {
                it.kind == 8 && it.label.startsWith("Back")
            }

        try {
            click("nav:Sensors")
            click("filter:Cameras")
            click("sensor:${cap.id}")
            activity.scenario.onActivity {
                session.startPublisher(
                    cap,
                    EndpointConfig(cap.defaultTopic, 30.0),
                    RobotSessionService.Profile(
                        SessionConfig("/phone", 0),
                        args.getString("pcPeer", ""),
                        args.getString("localAddress", ""),
                    ),
                )
            }

            waitFor("No decoded live preview") { session.cameraPreview != null }
            var imageShown = false
            activity.scenario.onActivity {
                fun hasImage(view: View): Boolean =
                    (view is ImageView && view.drawable != null) ||
                        (view is ViewGroup &&
                            (0 until view.childCount).any { i -> hasImage(view.getChildAt(i)) })
                imageShown = hasImage(it.window.decorView)
            }

            // Wait for the first animation-frame presentation.
            if (!imageShown) {
                SystemClock.sleep(700)
                activity.scenario.onActivity {
                    fun hasImage(view: View): Boolean =
                        (view is ImageView && view.drawable != null) ||
                            (view is ViewGroup &&
                                (0 until view.childCount).any { i -> hasImage(view.getChildAt(i)) })
                    imageShown = hasImage(it.window.decorView)
                }
            }

            assertTrue("ImageView has no live preview", imageShown)
            SystemClock.sleep(1000) // Exclude camera/decoder startup from the sample window.
            var firstDisplayed = 0L
            activity.scenario.onActivity { firstDisplayed = it.previewFramesDisplayed }
            val firstPublished = NativeRuntime.publishedCount(cap.id)
            val sampleStart = SystemClock.elapsedRealtime()
            val ages = mutableListOf<Long>()
            repeat(100) {
                SystemClock.sleep(100)
                activity.scenario.onActivity { ages += it.previewFrameAgeMs }
            }

            var lastDisplayed = 0L
            activity.scenario.onActivity { lastDisplayed = it.previewFramesDisplayed }
            val seconds = (SystemClock.elapsedRealtime() - sampleStart) / 1000.0
            val displayedFps = (lastDisplayed - firstDisplayed) / seconds
            val publishedFps = (NativeRuntime.publishedCount(cap.id) - firstPublished) / seconds
            val p95Age = ages.sorted()[95]
            android.util.Log.i(
                "ROS2PreviewBenchmark",
                "displayedFps=$displayedFps publishedFps=$publishedFps " +
                    "p95FrameAgeMs=$p95Age maxFrameAgeMs=${ages.maxOrNull()}",
            )
            assertTrue("Preview too slow: $displayedFps fps", displayedFps >= 20.0)
            assertTrue("Preview reduced ROS publishing: $publishedFps fps", publishedFps >= 20.0)
            assertTrue("Preview is stale: p95 age ${p95Age}ms", p95Age < 250)
            val front =
                CapabilityCatalog.publishers(context).firstOrNull {
                    it.kind == 8 && it.label.startsWith("Front")
                }
            if (front != null) {
                click("detail_back")
                click("sensor:${front.id}")
                assertFalse(session.cameraPreviewEnabled)
                click("detail_back")
                click("sensor:${cap.id}")
                waitFor("Preview did not return after camera settings switch") {
                    session.cameraPreview != null
                }
            }

            val initial = NativeRuntime.publishedCount(cap.id)
            SystemClock.sleep(5000)
            val fps = (NativeRuntime.publishedCount(cap.id) - initial) / 5.0
            android.util.Log.i("ROS2UiVerification", "Preview-visible local ROS publish FPS=$fps")
            assertTrue("Preview slows local ROS publication: $fps", fps >= 20)
            click("nav:Overview")
            assertFalse(session.cameraPreviewEnabled)
            val before = NativeRuntime.publishedCount(cap.id)
            waitFor("Navigation stopped camera publication") {
                NativeRuntime.publishedCount(cap.id) > before + 10
            }

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
