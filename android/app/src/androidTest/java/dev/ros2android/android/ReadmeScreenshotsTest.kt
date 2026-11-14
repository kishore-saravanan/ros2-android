package dev.ros2android.android

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit documentation capture only; never starts sensors, projects or Dashboard. */
@RunWith(AndroidJUnit4::class)
class ReadmeScreenshotsTest {
    private fun views(root: View): List<View> =
        listOf(root) +
            if (root is ViewGroup) {
                (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
            } else {
                emptyList()
            }

    @Test
    fun captureAppContentForReadme() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureReadme") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs =
            context.getSharedPreferences(
                "control_dashboard_v1",
                android.content.Context.MODE_PRIVATE,
            )
        val original = prefs.getString("widgets", null)
        val examples = org.json.JSONArray()

        for (widget in
            listOf(
                ControlWidget("readme_drive", "Drive", "Joystick", "/demo/cmd_vel"),
                ControlWidget("readme_enable", "Enable", "Switch", "/demo/enabled"),
            )) {
            examples.put(
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
            )
        }

        prefs.edit().putString("widgets", examples.toString()).commit()

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for ((tab, name) in
                    listOf(
                        "Overview" to "home",
                        "Sensors" to "sensors",
                        "Dashboard" to "dashboard",
                        "Projects" to "projects",
                    )) {
                    var focused = false
                    val deadline = SystemClock.elapsedRealtime() + 5000
                    while (!focused && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { focused = it.hasWindowFocus() }
                        if (!focused) SystemClock.sleep(100)
                    }

                    scenario.onActivity { app ->
                        assertTrue(app.hasWindowFocus())
                        assertFalse(
                            app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
                        )
                        val root = app.findViewById<View>(android.R.id.content)
                        val button = views(root).single { it.tag == "nav:$tab" }
                        button.performClick()
                    }

                    SystemClock.sleep(1000)
                    scenario.onActivity { app ->
                        assertTrue(app.hasWindowFocus())
                        assertFalse(
                            app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
                        )
                        val root = app.findViewById<View>(android.R.id.content)

                        if (tab == "Dashboard") {
                            assertTrue(
                                "Documentation must not arm Dashboard",
                                views(root).filterIsInstance<TextView>().any {
                                    it.text.toString() == "Dashboard stopped"
                                },
                            )
                        }

                        // Redact addresses before drawing; raw device captures remain private.
                        val changed =
                            views(root).filterIsInstance<TextView>().mapNotNull { label ->
                                val original = label.text
                                val sanitized =
                                    original
                                        .toString()
                                        .replace(
                                            Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),
                                            "[address hidden]",
                                        )
                                if (sanitized == original.toString()) {
                                    null
                                } else {
                                    (label to original).also { label.text = sanitized }
                                }
                            }

                        try {
                            val bitmap =
                                Bitmap.createBitmap(
                                    root.width,
                                    root.height,
                                    Bitmap.Config.ARGB_8888,
                                )
                            root.draw(Canvas(bitmap))
                            File(app.cacheDir, "readme-$name.png").outputStream().use {
                                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }

                            bitmap.recycle()
                        } finally {
                            changed.forEach { (label, original) -> label.text = original }
                        }
                    }
                }
            }
        } finally {
            if (original == null) {
                prefs.edit().remove("widgets").commit()
            } else {
                prefs.edit().putString("widgets", original).commit()
            }
        }
    }
}
