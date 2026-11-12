package dev.ros2android.runner

import android.content.Context

/** Execution boundary shared by factory C++, embedded Python and workspace runners.
 *
 * ProjectService owns accepted profiles, serialized start/stop, foreground locks and
 * failure cleanup. Backends own only their executor or child processes. A successful
 * start means initialization completed, not that a remote ROS peer has received data.
 */
interface RunnerBackend {
    fun prepare(context: Context)
    fun requireLoaded()
    fun start(config: ProjectConfig, libraryDir: String)
    /** Release owned execution resources; safe before start and after a failed start. */
    fun stop()
    /** JSON snapshot: running, error and backend telemetry; may throw if unavailable. */
    fun diagnostic(): String
    /** Only workspace builds with a shell may provide a shell environment. */
    fun prepareShell(): String
}
