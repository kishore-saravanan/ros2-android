package dev.ros2android.android

import android.app.Activity
import android.app.KeyguardManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.Parameters
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
import com.google.android.apps.common.testing.accessibility.framework.utils.contrast.BitmapImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Google Accessibility Test Framework checks (touch targets, labels, contrast and more) on
 * core screens in app-local light and dark previews. Screenshots stay in memory and are taken
 * only while this app's own window has focus without a lock screen.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityChecksTest {
    @Test fun lightCoreScreensPassAccessibilityChecks() = audit(LightPreviewActivity::class.java)
    @Test fun darkCoreScreensPassAccessibilityChecks() = audit(DarkPreviewActivity::class.java)

    private fun find(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), tag)?.let { return it }
        return null
    }

    private fun <T : Activity> audit(type: Class<T>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val checks = AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(AccessibilityCheckPreset.LATEST)
        val errors = mutableListOf<String>()
        ActivityScenario.launch(type).use { scenario ->
            SystemClock.sleep(1500) // Service binding and first render.
            val screens = listOf("Overview" to null, "Sensors" to null, "Sensors" to "sensor:battery",
                "Dashboard" to null, "Projects" to null, "Connect" to null)
            for ((tab, detail) in screens) {
                scenario.onActivity { app ->
                    find(app.window.decorView, "nav:$tab")!!.performClick()
                    detail?.let { find(app.window.decorView, it)!!.performClick() }
                }
                instrumentation.waitForIdleSync(); SystemClock.sleep(600)
                scenario.onActivity { app ->
                    assertTrue("Own window must be focused before capture", app.window.decorView.hasWindowFocus())
                    assertFalse("Lock screen must be absent", app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                }
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                scenario.onActivity { app ->
                    val hierarchy = AccessibilityHierarchyAndroid.newBuilder(app.window.decorView).build()
                    val parameters = Parameters().apply { screenshot?.let { putScreenCapture(BitmapImage(it)) } }
                    val results = checks.flatMap { it.runCheckOnHierarchy(hierarchy, null, parameters) }
                    for (result in results) {
                        val element = result.element
                        val line = "${type.simpleName} $tab${detail?.let { "/$it" } ?: ""}: ${result.sourceCheckClass.simpleName} " +
                            "[${element?.className} '${element?.text ?: element?.contentDescription ?: ""}' ${element?.boundsInScreen}] " +
                            result.getMessage(Locale.ENGLISH)
                        when (result.type) {
                            AccessibilityCheckResultType.ERROR -> errors += line
                            AccessibilityCheckResultType.WARNING -> Log.w("ROS2Accessibility", line)
                            else -> Unit
                        }
                    }
                }
                screenshot?.recycle()
            }
        }
        if (errors.isNotEmpty()) fail("${errors.size} accessibility errors:\n" + errors.joinToString("\n"))
    }
}
