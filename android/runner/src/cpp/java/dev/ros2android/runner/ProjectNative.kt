package dev.ros2android.runner

object ProjectNative : RunnerBackend {
    override fun prepare(@Suppress("UNUSED_PARAMETER") context: android.content.Context) {}
    private val loaded = runCatching { System.loadLibrary("project_runner") }
    override fun prepareShell(): String = error("Shell requires a workspace built with --with-shell")
    override fun requireLoaded() { loaded.getOrThrow() }
    override fun diagnostic(): String {
        requireLoaded()
        return status()
    }
    override fun start(config: ProjectConfig, libraryDir: String) =
        startConfigured(config.domain, config.namespace,
            dev.ros2android.network.CycloneNetwork.current(config.peer, config.local),
            libraryDir, config.arguments, config.nodes.map { it.component }.toTypedArray(),
            config.nodes.map { it.arguments }.toTypedArray())
    private external fun startConfigured(domain: Int, namespace: String, networkXml: String, libraryDir: String, arguments: Array<String>, components: Array<String>, nodeArguments: Array<Array<String>>)
    external override fun stop()
    external fun status(): String
}
