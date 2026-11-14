package dev.ros2android.runner

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process

/** Deliberate failures for the disposable qualification fixture, never operational workspaces. */
internal object FixtureFailure {
    const val CHILD = "dev.ros2android.runner.TEST_EXIT_CHILD"
    const val RUNNER = "dev.ros2android.runner.TEST_EXIT_RUNNER"

    fun schedule(context: Context, action: String) {
        check(context.packageName == "dev.ros2android.android.runner.ws_protocol_workspace") {
            "Failure injection is restricted to the disposable protocol fixture"
        }

        check(ProjectService.current?.state == "Running") { "Fixture must be running" }
        val failure: () -> Unit =
            if (action == RUNNER) {
                ({ Process.killProcess(Process.myPid()) })
            } else {
                require(action == CHILD)
                // Test-only access to the workspace supervisor's owned Process objects.
                // This keeps fault hooks out of the release runtime API and avoids /proc scanning.
                val field =
                    ProjectNative::class.java.getDeclaredField("workers").apply {
                        isAccessible = true
                    }
                val workers = field.get(ProjectNative) as List<*>
                val talker =
                    workers.filterNotNull().single { worker ->
                        worker.javaClass
                            .getDeclaredField("component")
                            .apply { isAccessible = true }
                            .get(worker) == "ws_nodes__talker"
                    }
                val child =
                    talker.javaClass
                        .getDeclaredField("process")
                        .apply { isAccessible = true }
                        .get(talker) as java.lang.Process
                ({
                    child.destroyForcibly()
                    Unit
                })
            }
        // Allow the ordered debug broadcast to reply before deliberately ending a process.
        Handler(Looper.getMainLooper()).postDelayed({ failure() }, 250)
    }
}
