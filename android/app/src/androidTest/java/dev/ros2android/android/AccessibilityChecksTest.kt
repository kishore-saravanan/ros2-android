package dev.ros2android.android

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.KeyguardManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
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
import org.junit.Assert.assertEquals
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
    @Test fun lightDialogsPassAccessibilityChecks() = auditDialogs(LightPreviewActivity::class.java)
    @Test fun darkDialogsPassAccessibilityChecks() = auditDialogs(DarkPreviewActivity::class.java)

    private fun find(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), tag)?.let { return it }
        return null
    }

    private fun <T : Activity> waitForOwnFocus(scenario: ActivityScenario<T>, label: String) {
        val end = SystemClock.elapsedRealtime() + 8000
        var focused = false
        while (!focused && SystemClock.elapsedRealtime() < end) {
            scenario.onActivity { app ->
                assertFalse("Lock screen must be absent", app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                focused = app.window.decorView.hasWindowFocus()
            }
            if (!focused) SystemClock.sleep(100)
        }
        assertTrue("Own window did not obtain focus: $label", focused)
    }

    private fun <T : Activity> audit(type: Class<T>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val checks = AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(AccessibilityCheckPreset.LATEST)
        val errors = mutableListOf<String>()
        ActivityScenario.launch(type).use { scenario ->
            waitForOwnFocus(scenario, "initial core screen")
            val screens = listOf("Overview" to null, "Sensors" to null, "Sensors" to "sensor:battery",
                "Dashboard" to null, "Projects" to null, "Connect" to null)
            for ((tab, detail) in screens) {
                waitForOwnFocus(scenario, "before $tab")
                scenario.onActivity { app ->
                    find(app.window.decorView, "nav:$tab")!!.performClick()
                    detail?.let { find(app.window.decorView, it)!!.performClick() }
                }
                instrumentation.waitForIdleSync()
                waitForOwnFocus(scenario, "$tab/${detail.orEmpty()}")
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

    private fun contains(node: AccessibilityNodeInfo?, text: String): Boolean =
        node != null && (node.text?.toString() == text || (0 until node.childCount).any { contains(node.getChild(it), text) })

    /**
     * Dialogs are separate windows, so they are read through the accessibility tree of the active
     * window, which must belong to this app. Each dialog is dismissed with Back afterwards.
     */
    private fun <T : Activity> auditDialogs(type: Class<T>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val checks = AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(AccessibilityCheckPreset.LATEST)
        val errors = mutableListOf<String>()
        var audited = 0
        ActivityScenario.launch(type).use { scenario ->
            waitForOwnFocus(scenario, "initial dialog screen")
            // Tab, opener tag prefix and the dialog title that proves the dialog is showing.
            val dialogs = listOf(Triple("Dashboard", "dashboard_add_widget", "Add widget"),
                Triple("Projects", "project_settings:", "Project connection"),
                Triple("Connect", "open_source_licenses", "Open-source licenses"))
            for ((tab, opener, title) in dialogs) {
                var opened = false
                waitForOwnFocus(scenario, "before $title")
                scenario.onActivity { app -> find(app.window.decorView, "nav:$tab")!!.performClick() }
                instrumentation.waitForIdleSync(); SystemClock.sleep(1000)
                scenario.onActivity { app ->
                    findPrefix(app.window.decorView, opener)?.let { it.performClick(); opened = true }
                }
                if (!opened) { Log.w("ROS2Accessibility", "Skipped $title: no $opener on $tab"); continue }
                instrumentation.waitForIdleSync()
                val end = SystemClock.elapsedRealtime() + 5000
                // Read the package gate before traversing any active-window contents.
                fun ownRoot() = automation.rootInActiveWindow?.takeIf {
                    it.packageName?.toString() == instrumentation.targetContext.packageName
                }
                var root = ownRoot()
                while (!contains(root, title) && SystemClock.elapsedRealtime() < end) {
                    SystemClock.sleep(200); root = ownRoot()
                }
                assertTrue("$title dialog did not appear in an own-app window", contains(root, title))
                assertEquals("Active window must be this app", instrumentation.targetContext.packageName, root!!.packageName?.toString())
                scenario.onActivity { app -> assertFalse("Lock screen must be absent", app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) }
                val screenshot = automation.takeScreenshot()
                val hierarchy = AccessibilityHierarchyAndroid.newBuilder(root, instrumentation.targetContext).build()
                val parameters = Parameters().apply { screenshot?.let { putScreenCapture(BitmapImage(it)) } }
                for (result in checks.flatMap { it.runCheckOnHierarchy(hierarchy, null, parameters) }) {
                    val element = result.element
                    val line = "${type.simpleName} $title: ${result.sourceCheckClass.simpleName} " +
                        "[${element?.className} '${element?.text ?: element?.contentDescription ?: ""}' ${element?.boundsInScreen}] " +
                        result.getMessage(Locale.ENGLISH)
                    when (result.type) {
                        AccessibilityCheckResultType.ERROR -> errors += line
                        AccessibilityCheckResultType.WARNING -> Log.w("ROS2Accessibility", line)
                        else -> Unit
                    }
                }
                screenshot?.recycle()
                audited++
                automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                instrumentation.waitForIdleSync(); SystemClock.sleep(600)
            }
        }
        assertTrue("No dialog could be opened", audited > 0)
        if (errors.isNotEmpty()) fail("${errors.size} accessibility errors:\n" + errors.joinToString("\n"))
    }

    private fun findPrefix(view: View, prefix: String): View? {
        if ((view.tag as? String)?.startsWith(prefix) == true) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findPrefix(view.getChildAt(i), prefix)?.let { return it }
        return null
    }
}
