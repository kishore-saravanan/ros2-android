package dev.ros2android.android

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
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
class CameraOptionsDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun persistsAndPublishesSupportedResolutionQualityAndAutomaticFps() {
        assumeTrue(
            "Explicit opt-in required",
            InstrumentationRegistry.getArguments().getString("testCameraOptions") == "true",
        )
        var focused = false
        val focusDeadline = SystemClock.elapsedRealtime() + 5000
        while (!focused && SystemClock.elapsedRealtime() < focusDeadline) {
            activity.scenario.onActivity { app ->
                check(
                    !app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
                )
                focused = app.hasWindowFocus()
            }

            if (!focused) SystemClock.sleep(100)
        }

        assertTrue("Project Activity must have focus before UI input", focused)
        lateinit var robot: RobotSessionService
        lateinit var context: Context
        lateinit var cap: PhoneCapability
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
            assertEquals(
                "Allow camera access before testing",
                PackageManager.PERMISSION_GRANTED,
                app.checkSelfPermission(Manifest.permission.CAMERA),
            )
            context = app.applicationContext
            cap =
                CapabilityCatalog.publishers(app).first {
                    it.kind == 8 && it.label.startsWith("Back")
                }
            context.bindService(
                Intent(app, RobotSessionService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val mainPrefs = context.getSharedPreferences("endpoints_v1", Context.MODE_PRIVATE)
        val sizes = CameraPublisher.resolutions(context, cap.cameraId!!)
        val size =
            sizes.firstOrNull { it.width == 1280 && it.height == 720 }
                ?: sizes.firstOrNull {
                    it.width > 640 && it.width.toLong() * it.height <= 1280L * 960
                }
                ?: sizes.first()
        val resolution = "${size.width}×${size.height}"
        val saved =
            listOf("topic", "rate", "resolution", "quality").associateWith {
                mainPrefs.getString("${cap.id}.$it", null)
            }
        fun waitFor(label: String, condition: () -> Boolean) {
            var passed = false
            val deadline = SystemClock.elapsedRealtime() + 20000
            while (!passed && SystemClock.elapsedRealtime() < deadline) {
                activity.scenario.onActivity { passed = condition() }
                SystemClock.sleep(100)
            }

            assertTrue(label, passed)
        }

        fun openCamera(app: MainActivity) {
            check(
                !app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
                    app.hasWindowFocus()
            )
            var root = app.findViewById<View>(android.R.id.content)
            root.findViewWithTag<View>("nav:Sensors").performClick()
            root = app.findViewById(android.R.id.content)
            if (root.findViewWithTag<View>("rate:${cap.id}") == null) {
                if (root.findViewWithTag<View>("filter:Cameras") == null) {
                    @Suppress("DEPRECATION") app.onBackPressed()
                }

                root = app.findViewById(android.R.id.content)
                root.findViewWithTag<View>("filter:Cameras")?.performClick()
                root = app.findViewById(android.R.id.content)
                root.findViewWithTag<View>("sensor:${cap.id}").performClick()
            }

            root = app.findViewById(android.R.id.content)
            val body = root.findViewWithTag<View>("body:sensor_info:${cap.id}")
            if (body.visibility != View.VISIBLE) {
                root.findViewWithTag<View>("expand:sensor_info:${cap.id}").performClick()
            }
        }

        try {
            activity.scenario.onActivity { app ->
                openCamera(app)
                val root = app.findViewById<View>(android.R.id.content)
                root
                    .findViewWithTag<EditText>("topic:${cap.id}")
                    .setText("/phone/camera_options_test/image/compressed")
                root.findViewWithTag<EditText>("rate:${cap.id}").setText("0")
                val spinner = root.findViewWithTag<Spinner>("resolution:${cap.id}")
                val index =
                    (0 until spinner.count).first {
                        spinner.getItemAtPosition(it).toString() == resolution
                    }
                spinner.setSelection(index)
                root.findViewWithTag<EditText>("quality:${cap.id}").setText("80")
            }

            activity.scenario.recreate()
            SystemClock.sleep(500)
            activity.scenario.onActivity { app ->
                openCamera(app)
                val root = app.findViewById<View>(android.R.id.content)
                assertEquals("0", root.findViewWithTag<EditText>("rate:${cap.id}").text.toString())
                assertEquals(
                    resolution,
                    root.findViewWithTag<Spinner>("resolution:${cap.id}").selectedItem.toString(),
                )
                assertEquals(
                    "80",
                    root.findViewWithTag<EditText>("quality:${cap.id}").text.toString(),
                )
                root.findViewWithTag<View>("toggle:${cap.id}").performClick()
            }

            waitFor("Camera publishes auto FPS with selected resolution/quality") {
                val stats = robot.cameraStats
                stats != null &&
                    stats.width == size.width &&
                    stats.height == size.height &&
                    stats.jpegQuality == 80 &&
                    stats.requestedFps == 0.0 &&
                    stats.published >= 5
            }

            SystemClock.sleep(5000)
            lateinit var autoStats: CameraPublisher.Stats
            activity.scenario.onActivity { app ->
                autoStats = robot.cameraStats!!
                val root = app.findViewById<View>(android.R.id.content)
                assertFalse(root.findViewWithTag<Spinner>("resolution:${cap.id}").isEnabled)
                root.findViewWithTag<View>("toggle:${cap.id}").performClick()
            }

            waitFor("Camera stop") { !robot.busy && cap.id !in robot.active }
            activity.scenario.onActivity { app ->
                val root = app.findViewById<View>(android.R.id.content)
                root.findViewWithTag<EditText>("rate:${cap.id}").setText("120")
                root.findViewWithTag<View>("toggle:${cap.id}").performClick()
            }

            waitFor("120 FPS request is accepted without hard ceiling") {
                robot.cameraStats?.let { it.requestedFps == 120.0 && it.published >= 5 } == true
            }

            activity.scenario.onActivity {
                val stats = robot.cameraStats!!
                java.io
                    .File(context.cacheDir, "camera-options-result.json")
                    .writeText(
                        org.json
                            .JSONObject()
                            .put("width", size.width)
                            .put("height", size.height)
                            .put("jpegQuality", stats.jpegQuality)
                            .put("autoTargetFps", autoStats.sensorFps)
                            .put("autoCaptureFps", autoStats.captureFps)
                            .put("autoPublishFps", autoStats.publishFps)
                            .put("requestedFps", stats.requestedFps)
                            .put("selectedTargetFps", stats.sensorFps)
                            .toString()
                    )
                robot.stopPublisher(cap.id)
            }

            waitFor("Final camera stop") { !robot.busy && cap.id !in robot.active }
            waitFor("Camera preview cleared before app-only proof") {
                robot.cameraPreview == null
            }

            activity.scenario.onActivity { app ->
                check(
                    !app.getSystemService(android.app.KeyguardManager::class.java)
                        .isKeyguardLocked && app.hasWindowFocus()
                )
                val root = app.findViewById<View>(android.R.id.content)
                val preview = root.findViewWithTag<ImageView>("camera_preview:${cap.id}")
                preview.setImageDrawable(
                    null
                ) // Camera is stopped; never capture a real camera frame.
                root.findViewWithTag<ScrollView>("page_scroll").scrollTo(0, 1000)
                val proof = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(proof))
                java.io.File(context.cacheDir, "camera-options-proof.png").outputStream().use {
                    proof.compress(Bitmap.CompressFormat.PNG, 100, it)
                }

                proof.recycle()
            }

            // Actual camera pixels are never saved or displayed on the PC.
            val synthetic = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            val bytes =
                java.io
                    .ByteArrayOutputStream()
                    .apply { synthetic.compress(Bitmap.CompressFormat.PNG, 100, this) }
                    .toByteArray()
            synthetic.recycle()
            val decoded = DashboardImageDecoder.decode(bytes, 2400)
            assertNotNull(decoded)

            if (Runtime.getRuntime().maxMemory() / 64 >= 1920L * 1080) {
                assertEquals(1920, decoded!!.width)
            }

            decoded?.recycle()
        } finally {
            activity.scenario.onActivity { robot.stopPublisher(cap.id) }
            activity.scenario.close()
            val edit = mainPrefs.edit()
            saved.forEach { (key, value) ->
                if (value == null) {
                    edit.remove("${cap.id}.$key")
                } else {
                    edit.putString("${cap.id}.$key", value)
                }
            }

            edit.commit()
            context.unbindService(connection)
        }
    }
}
