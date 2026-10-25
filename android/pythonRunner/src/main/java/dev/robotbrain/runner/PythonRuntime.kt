package dev.robotbrain.runner

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform
import java.io.File

private object PythonEnvironment {
    external fun configure(peer: String, local: String, libraries: String, prefix: String)
}

/** Backend for the same foreground-service lifecycle as the native C++ runner. */
object ProjectNative {
    private val loaded = runCatching { System.loadLibrary("python_environment") }
    private lateinit var app: Context
    @Volatile private var runtime: PyObject? = null
    fun prepare(context: Context) { app = context.applicationContext }
    fun requireLoaded() { loaded.getOrThrow() }
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
    fun start(domain: Int, namespace: String, peer: String, local: String, libraryDir: String,
              arguments: Array<String>, @Suppress("UNUSED_PARAMETER") components: Array<String>,
              @Suppress("UNUSED_PARAMETER") nodeArguments: Array<Array<String>>) {
        requireLoaded()
        val prefix = File(app.filesDir, "ros2-python-sdk")
        extract("ros2-python-sdk", prefix)
        PythonEnvironment.configure(peer, local, libraryDir, prefix.path)
        if (!Python.isStarted()) Python.start(AndroidPlatform(app))
        val module = Python.getInstance().getModule("phone_runtime")
        runtime = module
        module.callAttr("start", domain, namespace, arguments)
    }
    fun stop() { runtime?.callAttr("stop") }
    fun diagnostic(): String = runtime?.callAttr("status")?.toString()
        ?: "{\"running\":false,\"heartbeat\":0,\"node\":\"\",\"nodes\":[],\"error\":\"\",\"runtime\":\"python\"}"
}
