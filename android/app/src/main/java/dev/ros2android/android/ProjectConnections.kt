package dev.ros2android.android

import android.content.Context

/** UI-scoped management bindings only; closing these never stops runner execution. */
class ProjectConnections(context: Context, private val changed: () -> Unit) {
    private val context = context.applicationContext
    private val controllers = linkedMapOf<String, ProjectController>()
    var discoveryError: Throwable? = null
        private set

    val values: List<ProjectController>
        get() = controllers.values.toList()

    val size: Int
        get() = controllers.size

    operator fun get(packageName: String): ProjectController? = controllers[packageName]

    fun discover(): List<InstalledProjects.Project> {
        val result = runCatching { InstalledProjects.list(context) }
        discoveryError = result.exceptionOrNull()
        val projects = result.getOrDefault(emptyList())
        val installed = projects.associateBy { it.packageName }
        // Replacement metadata invalidates a binding just as removal does.
        controllers.entries
            .toList()
            .filter { installed[it.key] != it.value.project }
            .forEach {
                controllers.remove(it.key)?.close()
            }
        return projects
    }

    fun controller(project: InstalledProjects.Project): ProjectController {
        if (controllers[project.packageName]?.project != project) {
            controllers.remove(project.packageName)?.close()
        }

        return controllers.getOrPut(project.packageName) {
            ProjectController(context, project, changed)
        }
    }

    fun poll() = values.forEach { it.poll() }

    fun close() {
        values.forEach { it.close() }
        controllers.clear()
    }
}
