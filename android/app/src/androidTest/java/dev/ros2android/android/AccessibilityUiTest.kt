package dev.ros2android.android

import android.os.SystemClock
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AccessibilityUiTest {
    private fun find(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), tag)?.let { return it }
        return null
    }
    @Test fun unchangedStatusDoesNotRewriteTextOnEveryPoll() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(2000) // Allow service binding and initial state reconciliation.
            val updates = AtomicInteger()
            scenario.onActivity { app ->
                (find(app.window.decorView, "session_status") as TextView).addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updates.incrementAndGet() }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }
            SystemClock.sleep(1600)
            assertEquals("Stable state should not repeatedly replace text", 0, updates.get())
        }
    }
    @Test fun largeTextNavigationAndBatteryShortcutRemainReachable() {
        ActivityScenario.launch(LargeTextPreviewActivity::class.java).use { scenario ->
            SystemClock.sleep(1200)
            scenario.onActivity { app ->
                assertEquals(2f, app.resources.configuration.fontScale, 0f)
                val root = app.window.decorView
                if (InstrumentationRegistry.getArguments().getString("captureLayout") == "true") {
                    assertTrue("Preview must be foreground before capture", root.hasWindowFocus())
                    assertFalse("Lock screen must be absent", app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    File(app.cacheDir, "large-text-portrait.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                val navigation = find(root, "navigation_scroll") as HorizontalScrollView
                assertTrue("Large text needs scrollable navigation", navigation.isHorizontalScrollBarEnabled)
                for (name in listOf("Overview", "Sensors", "Projects", "Connect")) {
                    val target = find(root, "nav:$name") as Button
                    assertTrue(target.contentDescription == null || target.contentDescription.toString().isNotBlank())
                    assertTrue("Navigation touch height too small", target.height >= (48 * app.resources.displayMetrics.density).toInt())
                    assertTrue("Navigation label clipped", target.layout?.let { it.getEllipsisCount(0) == 0 } ?: false)
                }
                (find(root, "nav:Connect") as Button).performClick()
                find(app.window.decorView, "nav:Help")!!.performClick()
                (find(app.window.decorView, "expand:help_battery_guide") as Button).performClick()
                val commands = find(app.window.decorView, "battery_pc_commands") as TextView
                assertTrue(commands.text.contains("source /opt/ros/jazzy/setup.bash"))
                assertTrue(commands.text.contains("--qos-reliability best_effort"))
                (find(app.window.decorView, "help_battery") as Button).performClick()
                assertTrue(find(app.window.decorView, "nav:Sensors")!!.isSelected)
                assertNotNull(find(app.window.decorView, "sensor:battery"))
                assertNotNull(find(app.window.decorView, "toggle:battery"))
            }
        }
    }
}
