package dev.ros2android.android

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectControlUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    private fun find(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                find(view.getChildAt(i), tag)?.let {
                    return it
                }
            }
        }

        return null
    }

    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(150)
        assertTrue(label, condition())
    }

    @Test
    fun installedIconsContainTheCustomRobotMark() {
        val target = InstrumentationRegistry.getArguments().getString("expectedProjectPackage")
        assumeTrue(target != null)
        activity.scenario.onActivity { app ->
            for (pkg in listOf(app.packageName, target!!)) {
                val icon = app.packageManager.getApplicationIcon(pkg)
                val bitmap =
                    android.graphics.Bitmap.createBitmap(
                        216,
                        216,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                icon.setBounds(0, 0, 216, 216)
                icon.draw(android.graphics.Canvas(bitmap))
                var teal = 0

                for (y in 0 until 216) {
                    for (x in 0 until 216) {
                        val pixel = bitmap.getPixel(x, y)

                        if (
                            android.graphics.Color.green(pixel) >
                                android.graphics.Color.red(pixel) + 40 &&
                                android.graphics.Color.blue(pixel) >
                                    android.graphics.Color.red(pixel) + 40
                        ) {
                            teal++
                        }
                    }
                }

                assertTrue("Custom robot mark missing from installed icon: $pkg", teal > 20)
                bitmap.recycle()
            }
        }
    }

    @Test
    fun invalidDomainIsRejectedWithoutChangingTheRunnerProfile() {
        val target = InstrumentationRegistry.getArguments().getString("expectedProjectPackage")
        assumeTrue(target != null)
        lateinit var control: ProjectController
        activity.scenario.onActivity { app ->
            control =
                ProjectController(
                    app,
                    InstalledProjects.list(app).single { it.packageName == target },
                ) {}
            control.connect()
        }

        try {
            fun snapshot(): Pair<String?, String?> {
                var result: Pair<String?, String?> = null to null
                activity.scenario.onActivity {
                    result =
                        control.status?.optJSONObject("config")?.optString("domain") to
                            control.commandError
                }

                return result
            }

            waitFor("No runner profile") { snapshot().first != null }
            val original = snapshot().first
            activity.scenario.onActivity {
                control.send(4, android.os.Bundle().apply { putString("domain", "102") })
            }

            waitFor("Invalid domain was not rejected") { snapshot().second != null }
            activity.scenario.onActivity { control.poll() }
            SystemClock.sleep(600)
            assertEquals(original, snapshot().first)
            assertNotNull("Polling erased the command error", snapshot().second)
        } finally {
            activity.scenario.onActivity { control.close() }
        }
    }

    @Test
    fun mainAppControlsRunnerAndReconnectsAfterRecreation() {
        val target = InstrumentationRegistry.getArguments().getString("expectedProjectPackage")
        assumeTrue(target != null)
        var id = ""
        activity.scenario.onActivity { app ->
            id = InstalledProjects.list(app).single { it.packageName == target }.id
            (find(app.window.decorView, "nav:Projects") as Button).performClick()
        }

        fun state(): String {
            var value = ""
            activity.scenario.onActivity {
                value = (find(it.window.decorView, "project_state:$id") as TextView).text.toString()
            }

            return value
        }

        fun toggle() =
            activity.scenario.onActivity {
                (find(it.window.decorView, "toggle_project:$id") as Button).performClick()
            }
        waitFor("No authenticated runner response") { state() in setOf("Stopped", "Running") }
        if (state() == "Running") {
            toggle()
            waitFor("Stop failed") { state() == "Stopped" }
        }

        toggle()
        waitFor("Start failed: ${state()}") { state() == "Running" }
        activity.scenario.onActivity { app ->
            (find(app.window.decorView, "expand:logs:$id") as Button).performClick()
        }

        waitFor("Workspace stdout did not reach the main app") {
            var output = ""
            activity.scenario.onActivity {
                output = (find(it.window.decorView, "project_logs:$id") as TextView).text.toString()
            }

            output.contains("talker") || output.contains("processor")
        }

        activity.scenario.recreate()
        waitFor("Did not reconnect to running project") { state() == "Running" }
        activity.scenario.onActivity { app ->
            assertTrue(find(app.window.decorView, "nav:Projects")!!.isSelected)
            assertEquals(
                "Stop project",
                (find(app.window.decorView, "toggle_project:$id") as Button).text.toString(),
            )
        }

        toggle()
        waitFor("Stop after recreation failed") { state() == "Stopped" }
        toggle()
        waitFor("Restart failed") { state() == "Running" }
        // Home names running projects so the operator need not open Projects.
        activity.scenario.onActivity { app ->
            (find(app.window.decorView, "nav:Overview") as Button).performClick()
        }

        waitFor("Home does not name the running project") {
            var summary = ""
            activity.scenario.onActivity {
                summary =
                    (find(it.window.decorView, "home_endpoints") as? TextView)
                        ?.text
                        ?.toString()
                        .orEmpty()
            }

            summary.contains("Running: ${id.replace('_', ' ')} · ")
        }
    }
}
