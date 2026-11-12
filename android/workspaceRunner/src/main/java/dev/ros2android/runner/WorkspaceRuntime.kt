package dev.ros2android.runner

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Owns only the packaged ROS executables launched for this workspace. */
object ProjectNative : RunnerBackend {
    private lateinit var app: Context
    private val lifecycle = ReentrantLock()
    private data class Worker(val name: String, val component: String, val process: Process)
    private val workers = mutableListOf<Worker>()
    private var started = 0L
    private var running = false
    private var error = ""
    override fun prepare(context: Context) { app = context.applicationContext }
    override fun prepareShell(): String = ShellEnvironment.prepare(app)
    override fun requireLoaded() {}
    private fun extract(path: String, destination: File) {
        val children = app.assets.list(path).orEmpty()
        if (children.isNotEmpty()) {
            destination.mkdirs(); children.forEach { extract("$path/$it", File(destination, it)) }
        } else {
            destination.parentFile?.mkdirs()
            app.assets.open(path).use { input -> destination.outputStream().use { input.copyTo(it) } }
        }
    }
    override fun start(config: ProjectConfig, libraryDir: String): Unit = lifecycle.withLock {
        val namespace = config.namespace
        val components = config.nodes.map { it.component }
        val nodeArguments = config.nodes.map { it.arguments }
        check(workers.isEmpty()) { "Stop the current workspace before starting" }
        require(components.size == nodeArguments.size && components.size in 1..16)
        val xml = dev.ros2android.network.CycloneNetwork.current(config.peer, config.local)
        val prefix = File(app.filesDir, "ros2-workspace")
        extract("ros2-workspace", prefix)
        error = ""; started = SystemClock.elapsedRealtime()
        try {
            for (i in components.indices) {
                val component = components[i]
                require(component.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
                val executable = File(libraryDir, "libworkspace_exec_$component.so")
                check(executable.isFile && executable.canExecute()) { "Packaged executable unavailable: $component" }
                val command = listOf(executable.path, "--ros-args") + nodeArguments[i] + listOf("-r", "__ns:=$namespace") + config.arguments
                val builder = ProcessBuilder(command).redirectErrorStream(true)
                builder.environment().apply {
                    put("LD_LIBRARY_PATH", libraryDir); put("AMENT_PREFIX_PATH", prefix.path)
                    put("RMW_IMPLEMENTATION", "rmw_cyclonedds_cpp"); put("ROS_DOMAIN_ID", config.domain.toString())
                    put("CYCLONEDDS_URI", xml); put("ROS_LOG_DIR", File(app.filesDir, "ros-logs").path)
                }
                builder.directory(prefix)
                val child = builder.start()
                val ns = nodeArguments[i].firstOrNull { it.startsWith("__ns:=") }?.substringAfter(":=") ?: namespace
                val name = nodeArguments[i].firstOrNull { it.startsWith("__node:=") }?.substringAfter(":=") ?: component
                workers += Worker(ns.trimEnd('/') + "/" + name, component, child)
                Thread({
                    runCatching { child.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(1024)
                        while (true) { val count = reader.read(buffer); if (count < 0) break
                            val output = "$component: ${String(buffer, 0, count)}"
                            Log.i("ROS2Workspace", output); ProjectLog.append(output) }
                    } }
                }, "ros-workspace-$component").apply { isDaemon = true; start() }
            }
            running = true
        } catch (failure: Exception) {
            error = failure.message ?: "Workspace start failed"
            stopLocked(); throw failure
        }
    }
    private fun stopLocked() {
        running = false
        workers.forEach { it.process.destroy() }
        workers.forEach {
            if (!it.process.waitFor(2, TimeUnit.SECONDS)) {
                it.process.destroyForcibly()
                check(it.process.waitFor(2, TimeUnit.SECONDS)) { "Workspace process did not terminate: ${it.component}" }
            }
        }
        workers.clear()
    }
    override fun stop() = lifecycle.withLock { stopLocked() }
    override fun diagnostic(): String {
        if (!lifecycle.tryLock()) return "{\"transition\":true}"
        try {
            if (running) workers.firstOrNull { !it.process.isAlive }?.let {
                running = false; error = "Node ${it.component} exited with code ${it.process.exitValue()}"
            }
            return JSONObject().put("running", running).put("heartbeat", if (started == 0L) 0 else (SystemClock.elapsedRealtime() - started) / 1000)
                .put("node", workers.firstOrNull()?.name ?: "").put("nodes", JSONArray(workers.map { it.name }))
                .put("error", error).put("runtime", "workspace-processes").toString()
        } finally { lifecycle.unlock() }
    }
}
