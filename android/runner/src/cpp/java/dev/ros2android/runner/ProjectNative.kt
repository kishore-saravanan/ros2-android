package dev.ros2android.runner

object ProjectNative {
    fun prepare(@Suppress("UNUSED_PARAMETER") context: android.content.Context) {}
    private val loaded = runCatching { System.loadLibrary("project_runner") }
    fun prepareShell(): String = error("Shell requires a workspace built with --with-shell")
    fun requireLoaded() { loaded.getOrThrow() }
    fun diagnostic(): String = if (loaded.isSuccess) status() else "Native runner unavailable: ${loaded.exceptionOrNull()?.message}"
    external fun start(domain: Int, namespace: String, peer: String, local: String, libraryDir: String, arguments: Array<String>, components: Array<String>, nodeArguments: Array<Array<String>>)
    external fun stop()
    external fun status(): String
}
