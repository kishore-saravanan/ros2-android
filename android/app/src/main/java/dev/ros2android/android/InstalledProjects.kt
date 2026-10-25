package dev.ros2android.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Queries only activities explicitly advertising the project-runner protocol. */
object InstalledProjects {
    const val ACTION = "dev.ros2android.android.OPEN_PROJECT"
    data class Project(val id: String, val packageName: String, val activity: String, val runtime: String, val sourceId: String) {
        val runtimeLabel get() = when (runtime) { "cpp" -> "C++ runner"; "python" -> "Python runner"; else -> "Colcon workspace" }
        fun openIntent() = Intent(ACTION).setComponent(ComponentName(packageName, activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    fun list(context: Context): List<Project> {
        @Suppress("DEPRECATION")
        val matches = context.packageManager.queryIntentActivities(Intent(ACTION).addCategory(Intent.CATEGORY_DEFAULT), PackageManager.GET_META_DATA)
        return matches.mapNotNull { result ->
            val info = result.activityInfo ?: return@mapNotNull null
            if (!info.exported || !info.enabled || !info.applicationInfo.enabled ||
                !info.packageName.matches(Regex("dev\\.ros2android\\.android\\.runner\\.[a-z][a-z0-9_]*")) ||
                info.name != "dev.ros2android.runner.MainActivity") return@mapNotNull null
            val meta = info.metaData ?: return@mapNotNull null
            val id = meta.getString("dev.ros2android.PROJECT_ID") ?: return@mapNotNull null
            val runtime = meta.getString("dev.ros2android.PROJECT_RUNTIME") ?: return@mapNotNull null
            val source = meta.getString("dev.ros2android.PROJECT_SOURCE")?.removePrefix("sha256:") ?: return@mapNotNull null
            if (meta.getInt("dev.ros2android.PROJECT_PROTOCOL") != 1 || !id.matches(Regex("[a-z][a-z0-9_]{0,63}")) ||
                runtime !in setOf("cpp", "python", "workspace") || !source.matches(Regex("[a-f0-9]{12}"))) return@mapNotNull null
            Project(id, info.packageName, info.name, runtime, source)
        }.distinctBy { it.packageName }.sortedWith(compareBy({ it.id }, { it.packageName }))
    }
}
