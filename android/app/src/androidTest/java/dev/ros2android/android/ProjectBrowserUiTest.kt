package dev.ros2android.android

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectBrowserUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private fun find(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), tag)?.let { return it }
        return null
    }
    @Test fun deployedRunnerAppearsAndProjectTabSurvivesRecreation() {
        val target = InstrumentationRegistry.getArguments().getString("expectedProjectPackage")
        assumeTrue("Pass an installed project package to verify discovery", target != null)
        activity.scenario.onActivity { app ->
            val projects = InstalledProjects.list(app)
            val project = projects.single { it.packageName == target }
            assertEquals("workspace", project.runtime)
            assertEquals("dev.ros2android.runner.MainActivity", project.openIntent().component?.className)
            assertEquals(target, project.openIntent().component?.packageName)
            (find(app.window.decorView, "nav:Projects") as Button).performClick()
            assertNotNull("Project controls absent", find(app.window.decorView, "toggle_project:${project.id}"))
            (find(app.window.decorView, "refresh_projects") as Button).performClick()
        }
        activity.scenario.recreate()
        activity.scenario.onActivity { app ->
            assertTrue(find(app.window.decorView, "nav:Projects")!!.isSelected)
            val project = InstalledProjects.list(app).single { it.packageName == target }
            assertNotNull(find(app.window.decorView, "toggle_project:${project.id}"))
        }
    }
}
