package dev.robotbrain.runner

object ProjectNative {
    private val loaded = runCatching { System.loadLibrary("project_runner") }
    fun requireLoaded() { loaded.getOrThrow() }
    fun diagnostic(): String = if (loaded.isSuccess) status() else "Native runner unavailable: ${loaded.exceptionOrNull()?.message}"
    external fun start(domain: Int, namespace: String, peer: String, local: String, libraryDir: String, arguments: Array<String>)
    external fun stop()
    external fun status(): String
}
