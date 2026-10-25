package dev.ros2android.runner

import android.content.Context
import android.system.Os
import org.json.JSONObject
import java.io.File

/** Debug shell preparation touches this runner's private files and APK libraries only. */
object ShellEnvironment {
    private fun extract(app: Context, source: String, destination: File) {
        val children = app.assets.list(source).orEmpty()
        if (children.isNotEmpty()) {
            destination.mkdirs(); children.forEach { extract(app, "$source/$it", File(destination, it)) }
        } else {
            destination.parentFile?.mkdirs()
            app.assets.open(source).use { input -> destination.outputStream().use { input.copyTo(it) } }
        }
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun link(target: File, destination: File) {
        destination.parentFile?.mkdirs()
        destination.delete()
        Os.symlink(target.path, destination.path)
    }
    @Synchronized
    fun prepare(app: Context): String {
        val libs = File(app.applicationInfo.nativeLibraryDir)
        check(File(libs, "libros2_python_exec.so").isFile) { "Rebuild workspace with --with-shell and deploy first" }
        val shell = File(app.filesDir, "ros2-shell")
        val prefix = File(app.filesDir, "ros2-workspace")
        @Suppress("DEPRECATION")
        val stamp = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
        val marker = File(shell, ".apk-version")
        if (!marker.isFile || marker.readText() != stamp) {
            extract(app, "ros2-shell", shell); extract(app, "ros2-workspace", prefix)
            marker.writeText(stamp)
        }
        val executables = JSONObject(File(prefix, "executables.json").readText())
        executables.keys().forEach { name ->
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*/[A-Za-z0-9_][A-Za-z0-9_.+-]*")))
            link(File(libs, "libworkspace_exec_${executables.getString(name)}.so"), File(prefix, "lib/$name"))
        }
        for (name in listOf("ros2", "python3")) link(File(libs, "libros2_python_exec.so"), File(shell, "bin/$name"))
        val prefs = app.getSharedPreferences("project_profile", Context.MODE_PRIVATE)
        val peer = prefs.getString("peer", "").orEmpty()
        val local = prefs.getString("local", "").orEmpty()
        for (ip in listOf(peer, local)) require(ip.isEmpty() || ip.matches(Regex("[0-9.]+")))
        val xml = "<CycloneDDS><Domain><General><Interfaces>" +
            (if (local.isEmpty()) "<NetworkInterface autodetermine=\"true\"/>" else "<NetworkInterface address=\"$local\"/>") +
            "</Interfaces></General><Discovery><ParticipantIndex>auto</ParticipantIndex><MaxAutoParticipantIndex>50</MaxAutoParticipantIndex>" +
            (if (peer.isEmpty()) "" else "<Peers><Peer Address=\"$peer\"/></Peers>") + "</Discovery></Domain></CycloneDDS>"
        val values = linkedMapOf(
            "HOME" to app.filesDir.path, "TMPDIR" to app.cacheDir.path,
            "PATH" to "${shell.path}/bin:/system/bin:/system/xbin", "LD_LIBRARY_PATH" to "${libs.path}:${shell.path}/site-packages/chaquopy/lib",
            "PYTHONHOME" to shell.path,
            "PYTHONPATH" to "${shell.path}/stdlib.zip:${shell.path}/lib-dynload:${shell.path}/site-packages",
            "ROS_DISTRO" to "jazzy", "ROS_VERSION" to "2", "ROS_PYTHON_VERSION" to "3",
            "AMENT_PREFIX_PATH" to prefix.path, "RMW_IMPLEMENTATION" to "rmw_cyclonedds_cpp",
            "ROS2CLI_NO_DAEMON" to "1", "ROS_DOMAIN_ID" to prefs.getString("domain", "0").orEmpty(), "CYCLONEDDS_URI" to xml,
            "ROS_LOG_DIR" to File(app.filesDir, "ros-logs").path, "PYTHONDONTWRITEBYTECODE" to "1",
            "PS1" to "android-ros2$ "
        )
        val env = File(shell, "environment.sh")
        env.writeText(values.entries.joinToString("\n", postfix="\n") { (key, value) -> "export $key=${quote(value)}" })
        return env.path
    }
}
