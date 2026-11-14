package dev.ros2android.android

import android.content.*
import android.graphics.*
import android.os.*
import android.view.View
import android.widget.*
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

@RunWith(AndroidJUnit4::class)
class DashboardImageTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun receivesJpegPngStaleInvalidRecoveryAndStop() {
        assumeTrue(
            "Run pc-dashboard-image-probe.py with dashboardImagePeer=true",
            InstrumentationRegistry.getArguments().getString("dashboardImagePeer") == "true",
        )
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
            check(
                !app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
                    app.hasWindowFocus()
            )
            context = app
            app.bindService(
                Intent(app, RobotSessionService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val prefs = context.getSharedPreferences("control_dashboard_v1", Context.MODE_PRIVATE)
        val original = prefs.getString("widgets", null)
        val widget =
            ControlWidget(
                "image_test",
                "Camera test",
                "Subscribed value",
                "/phone/dashboard_image_test",
                messageType = "sensor_msgs/msg/CompressedImage",
            )
        val drive =
            ControlWidget(
                "image_drive_test",
                "Test drive",
                "Joystick",
                "/phone/dashboard_image_test/cmd_vel",
            )
        val entry =
            org.json
                .JSONObject()
                .put("id", widget.id)
                .put("label", widget.label)
                .put("type", widget.type)
                .put("topic", widget.topic)
                .put("value", widget.value)
                .put("min", widget.minimum)
                .put("max", widget.maximum)
                .put("linear", widget.linear)
                .put("angular", widget.angular)
                .put("messageType", widget.messageType)
        prefs.edit().putString("widgets", org.json.JSONArray().put(entry).toString()).commit()
        lateinit var root: View
        fun waitFor(label: String, condition: () -> Boolean) {
            var passed = false
            val deadline = SystemClock.elapsedRealtime() + 25000
            while (!passed && SystemClock.elapsedRealtime() < deadline) {
                activity.scenario.onActivity { passed = condition() }
                SystemClock.sleep(50)
            }

            assertTrue(label, passed)
        }

        fun rendered() =
            (root.findViewWithTag<DashboardImageView>("reading-image:image_test").getChildAt(0)
                    as ImageView)
                .drawable != null
        fun colorMatches(width: Int, channel: Int): Boolean {
            val b = robot.dashboardReadings[widget.id]?.bitmap ?: return false
            if (b.width != width) return false
            val c = b.getPixel(0, 0)
            return when (channel) {
                0 -> Color.red(c) > 200 && Color.green(c) < 30
                1 -> Color.green(c) > 200 && Color.blue(c) < 30
                else -> Color.blue(c) > 200 && Color.red(c) < 30
            }
        }

        try {
            activity.scenario.onActivity { app ->
                robot.stopControls()
                app.findViewById<View>(android.R.id.content)
                    .findViewWithTag<View>("nav:Dashboard")
                    .performClick()
                root = app.findViewById(android.R.id.content)
                robot.startControls(
                    listOf(widget, drive),
                    RobotSessionService.Profile(SessionConfig("/phone", 0), "", ""),
                )
            }

            waitFor("Dashboard startup") { robot.controlsEnabled && !robot.busy }
            activity.scenario.onActivity {
                assertEquals(
                    "Waiting",
                    root.findViewWithTag<TextView>("reading-state:image_test").text.toString(),
                )
            }

            waitFor("Rendered JPEG red frame") { colorMatches(32, 0) && rendered() }
            // Reserved test topic only: verify motion publishing coexists with image receipt.
            repeat(10) {
                activity.scenario.onActivity { robot.control(drive, .1, .2) }
                SystemClock.sleep(100)
            }

            activity.scenario.onActivity { robot.control(drive) }
            waitFor("Stale image remains visible") {
                robot.dashboardReadings[widget.id]?.state(SystemClock.elapsedRealtime()) ==
                    "Stale" && rendered()
            }

            waitFor("PNG green recovers to Live") {
                colorMatches(48, 1) &&
                    robot.dashboardReadings[widget.id]?.state(SystemClock.elapsedRealtime()) ==
                        "Live" &&
                    rendered() &&
                    root.findViewWithTag<TextView>("reading-state:image_test").text.toString() ==
                        "Live"
            }

            activity.scenario.onActivity { app ->
                check(
                    !app.getSystemService(android.app.KeyguardManager::class.java)
                        .isKeyguardLocked && app.hasWindowFocus()
                )
                val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(image))
                java.io.File(app.cacheDir, "dashboard-image-proof.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }

                image.recycle()
            }

            waitFor("Malformed image handled") {
                robot.dashboardReadings[widget.id]?.value == "Cannot display image" &&
                    robot.dashboardReadings[widget.id]?.bitmap == null
            }

            waitFor("Valid frame recovers after malformed data") {
                colorMatches(64, 2) && rendered()
            }

            activity.scenario.onActivity { robot.stopControls() }
            waitFor("Stop clears image and endpoints") {
                robot.dashboardReadings.isEmpty() && !rendered() && !robot.controlsEnabled
            }

            assertNull(DashboardImageDecoder.decode(ByteArray(2 * 1024 * 1024 + 1)))
            assertNull(DashboardImageDecoder.decode(byteArrayOf(1, 2, 3)))
        } finally {
            activity.scenario.onActivity { robot.stopControls() }

            if (original == null) {
                prefs.edit().remove("widgets").commit()
            } else {
                prefs.edit().putString("widgets", original).commit()
            }

            context.unbindService(connection)
        }
    }
}
