package dev.ros2android.android

import android.app.KeyguardManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Changes only this Activity's orientation, never a global display setting. */
@RunWith(AndroidJUnit4::class)
class ResponsiveNavigationTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun coreTabsRemainReachableInPortraitAndLandscape() {
        var original = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity.scenario.onActivity { app ->
            check(
                app.hasWindowFocus() &&
                    !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            )
            original = app.requestedOrientation
        }

        try {
            for ((requested, expected) in
                listOf(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to
                        Configuration.ORIENTATION_LANDSCAPE,
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
                )) {
                activity.scenario.onActivity { it.requestedOrientation = requested }
                val end = SystemClock.elapsedRealtime() + 10000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < end) {
                    activity.scenario.onActivity {
                        ready =
                            it.hasWindowFocus() &&
                                it.resources.configuration.orientation == expected
                    }

                    if (!ready) SystemClock.sleep(100)
                }

                assertTrue("Requested app orientation unavailable", ready)

                for (tab in listOf("Overview", "Sensors", "Dashboard", "Projects", "Connect")) {
                    SystemClock.sleep(200)
                    activity.scenario.onActivity { app ->
                        check(
                            app.hasWindowFocus() &&
                                !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
                        )
                        val root = app.findViewById<View>(android.R.id.content)
                        val button = checkNotNull(root.findViewWithTag<TextView>("nav:$tab"))
                        assertTrue("Tab hidden: $tab", button.isShown)
                        val minimum = (48 * app.resources.displayMetrics.density).toInt()
                        assertTrue(
                            "Tab touch target too small: $tab",
                            button.width >= minimum && button.height >= minimum,
                        )
                        assertTrue(
                            "Tab label clipped: $tab",
                            button.layout?.let { layout ->
                                (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 }
                            } ?: false,
                        )
                        assertTrue(button.performClick())
                        assertTrue(
                            "Tab did not select: $tab",
                            app.findViewById<View>(android.R.id.content)
                                .findViewWithTag<View>("nav:$tab")
                                .isSelected,
                        )
                    }
                }
            }
        } finally {
            activity.scenario.onActivity { it.requestedOrientation = original }
        }
    }
}
