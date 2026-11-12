package dev.ros2android.runner

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform
import java.io.File

private object PythonEnvironment {
    external fun configure(networkXml: String, libraries: String, prefix: String)
}

/** Backend for the same foreground-service lifecycle as the native C++ runner. */
object ProjectNative : RunnerBackend {
    private val loaded = runCatching { System.loadLibrary("python_environment") }
    private lateinit var app: Context
    @Volatile private var runtime: PyObject? = null
    override fun prepare(context: Context) { app = context.applicationContext }
    override fun prepareShell(): String = error("Shell requires a workspace built with --with-shell")
    override fun requireLoaded() { loaded.getOrThrow() }
    private fun extract(path: String, destination: File) {
        val children = app.assets.list(path).orEmpty()
        if (children.isNotEmpty()) {
            destination.mkdirs()
            children.forEach { extract("$path/$it", File(destination, it)) }
        } else {
            destination.parentFile?.mkdirs()
            app.assets.open(path).use { input -> destination.outputStream().use { input.copyTo(it) } }
        }
    }
    override fun start(config: ProjectConfig, libraryDir: String) {
        requireLoaded()
        val networkXml = dev.ros2android.network.CycloneNetwork.current(config.peer, config.local)
        val prefix = File(app.filesDir, "ros2-python-sdk")
        extract("ros2-python-sdk", prefix)
        PythonEnvironment.configure(networkXml, libraryDir, prefix.path)
        if (!Python.isStarted()) Python.start(AndroidPlatform(app))
        val module = Python.getInstance().getModule("phone_runtime")
        runtime = module
        module.callAttr("start", config.domain, config.namespace, config.arguments)
    }
    override fun stop() { runtime?.callAttr("stop") }
    override fun diagnostic(): String = runtime?.callAttr("status")?.toString()
        ?: "{\"running\":false,\"heartbeat\":0,\"node\":\"\",\"nodes\":[],\"error\":\"\",\"runtime\":\"python\"}"
}
