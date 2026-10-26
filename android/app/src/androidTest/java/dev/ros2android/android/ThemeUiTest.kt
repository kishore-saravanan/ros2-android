package dev.ros2android.android

import android.app.KeyguardManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.pow

@RunWith(AndroidJUnit4::class)
class ThemeUiTest {
    private fun find(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), tag)?.let { return it }
        return null
    }
    private fun visibleEditors(view: View): Int = (if (view is EditText && view.isShown) 1 else 0) +
        (if (view is ViewGroup) (0 until view.childCount).sumOf { visibleEditors(view.getChildAt(it)) } else 0)
    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double { val s = value / 255.0; return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
    }
    private fun contrast(a: Int, b: Int): Double { val x = luminance(a); val y = luminance(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
    private fun snapshot(app: MainActivity, name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureLayout") != "true") return
        val root = app.window.decorView
        assertTrue("App must be foreground before capture", root.hasWindowFocus())
        assertFalse(app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(app.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun <T : MainActivity> checkTheme(type: Class<T>, mode: Int, name: String) {
        ActivityScenario.launch(type).use { scenario ->
            val readyDeadline = SystemClock.elapsedRealtime() + 8000
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < readyDeadline) {
                scenario.onActivity { ready = it.hasWindowFocus() && find(it.window.decorView, "session_status") != null }
                if (!ready) SystemClock.sleep(100)
            }
            assertTrue("Theme Activity did not obtain project-window focus", ready)
            scenario.onActivity { app ->
                assertEquals(mode, app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                assertTrue((find(app.window.decorView, "session_status") as android.widget.TextView).text.startsWith("Sensors & dashboard"))
                val surface = app.getColor(R.color.ros_surface)
                for (text in listOf(R.color.ros_text, R.color.ros_muted)) assertTrue("Text contrast below 4.5", contrast(app.getColor(text), surface) >= 4.5)
                assertTrue(contrast(app.getColor(R.color.ros_accent), app.getColor(R.color.ros_on_accent)) >= 4.5)
                assertEquals(View.GONE, find(app.window.decorView, "body:home_session")!!.visibility)
                assertEquals("Home needs no editable sensor fields", 0, visibleEditors(app.window.decorView))
                assertNull("Battery belongs in Sensors", find(app.window.decorView, "sensor:battery"))
                assertTrue((find(app.window.decorView, "home_connection") as android.widget.TextView).text.contains("ROS domain"))
                assertNull("Credit belongs in About", find(app.window.decorView, "home_credit"))
                val activitySummary = (find(app.window.decorView, "home_endpoints") as android.widget.TextView).text.toString()
                assertTrue(activitySummary.contains("Sensor topics:"))
                assertTrue(activitySummary.contains("Dashboard topics:"))
                assertTrue(activitySummary.contains("Projects:"))
                assertTrue(activitySummary.contains("Micro-ROS agent:"))
                snapshot(app, "$name-home")
                find(app.window.decorView, "nav:Sensors")!!.performClick()
                assertEquals("Sensor list must not expose configuration forms", 0, visibleEditors(app.window.decorView))
            }
            SystemClock.sleep(400)
            scenario.onActivity { app ->
                snapshot(app, "$name-sensors")
                find(app.window.decorView, "sensor:battery")!!.performClick()
                assertEquals("Detail screen needs topic and rate", 2, visibleEditors(app.window.decorView))
                assertEquals(View.GONE, find(app.window.decorView, "body:sensor_info:battery")!!.visibility)
                find(app.window.decorView, "nav:Connect")!!.performClick()
                assertEquals(View.GONE, find(app.window.decorView, "body:runtime_info")!!.visibility)
                assertTrue((find(app.window.decorView, "power_diagnostics") as android.widget.TextView).text.contains("Power saving:"))
                assertTrue("About footer must not require expansion", find(app.window.decorView, "about_credit")!!.isShown)
                val credit = (find(app.window.decorView, "about_credit") as android.widget.TextView).text
                assertEquals("Built by Kishore Saravanan", credit.toString())
                assertFalse("Author credit should be plain text", credit is android.text.Spanned)
                assertNull(find(app.window.decorView, "about_repository"))
                assertTrue(find(app.window.decorView, "nav:Connect")!!.isSelected)
            }
            SystemClock.sleep(400)
            scenario.onActivity { app -> (find(app.window.decorView, "page_scroll") as android.widget.ScrollView).fullScroll(View.FOCUS_DOWN) }
            SystemClock.sleep(300)
            scenario.onActivity { snapshot(it, "$name-settings") }
        }
    }
    @Test fun lightModeKeepsControlsMinimalAndReadable() = checkTheme(LightPreviewActivity::class.java, Configuration.UI_MODE_NIGHT_NO, "theme-light")
    @Test fun darkModeKeepsControlsMinimalAndReadable() = checkTheme(DarkPreviewActivity::class.java, Configuration.UI_MODE_NIGHT_YES, "theme-dark")
}
