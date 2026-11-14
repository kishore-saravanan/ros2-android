package dev.ros2android.android

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import dev.ros2android.protocol.ProjectProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * A disposable runner fixture driven through the app's project controls from the test thread.
 * [claim] requires it Stopped and remembers its saved profile; [release] stops it and restores that
 * profile, failing if the restore does not stick.
 */
class FixtureProject(private val instrumentation: Instrumentation, val packageName: String) {
    companion object {
        val PACKAGES =
            setOf(
                "dev.ros2android.android.runner.fixture_cpp",
                "dev.ros2android.android.runner.fixture_python",
            )
        private val PROFILE_KEYS = listOf("domain", "namespace", "peer", "local", "ros_args")
    }

    init {
        require(packageName in PACKAGES) { "Only disposable fixtures are allowed: $packageName" }
    }

    private val context = instrumentation.targetContext
    private var control: ProjectController? = null
    private var original: JSONObject? = null

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun waitFor(
        label: String,
        seconds: Int = 20,
        predicate: (ProjectController) -> Boolean,
    ) {
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        var passed = false
        while (!passed && SystemClock.elapsedRealtime() < end) {
            main { passed = control?.let(predicate) == true }
            if (!passed) SystemClock.sleep(100)
        }

        assertTrue(label, passed)
    }

    /** (Re)binds a fresh controller, as Refresh does, and waits for the first status. */
    fun connect() {
        main {
            control?.close()
            control =
                ProjectController(
                        context,
                        InstalledProjects.list(context).single { it.packageName == packageName },
                    ) {}
                    .also { it.connect() }
        }

        waitFor("Runner unavailable") {
            it.poll()
            it.status != null
        }
    }

    /** Requests a status and returns the latest one without waiting for the reply. */
    fun status(): JSONObject? {
        var value: JSONObject? = null
        main {
            control?.poll()
            value = control?.status
        }

        return value
    }

    /** Waits until the controls fail closed and returns the reported reason. */
    fun awaitControlError(seconds: Int = 20): String {
        var reason: String? = null
        waitFor("Controls stayed valid", seconds) {
            it.poll()
            reason = it.error
            reason != null
        }

        return reason!!
    }

    private fun command(value: Int, extras: Bundle = Bundle()) {
        var previous: JSONObject? = null
        main {
            previous = control!!.status
            control!!.send(value, extras)
        }

        waitFor("No reply for $value") {
            check(it.error == null) { it.error.orEmpty() }
            check(it.commandError == null) { "Runner rejected $value: ${it.commandError}" }
            it.status != null && it.status !== previous
        }
    }

    private fun state(expected: String, seconds: Int = 30) =
        waitFor("Runner did not become $expected", seconds) {
            it.poll()
            it.status?.optString("state") == expected
        }

    /** A status reply received after this call. */
    fun freshStatus(): JSONObject {
        var previous: JSONObject? = null
        main {
            previous = control!!.status
            control!!.poll()
        }

        var result: JSONObject? = null
        waitFor("Runner status unavailable") {
            val current = it.status
            if (current != null && current !== previous) {
                result = current
                true
            } else {
                it.poll()
                false
            }
        }

        return result!!
    }

    /**
     * Requires the fixture Stopped, remembers its saved profile, then saves an automatic-address
     * profile.
     */
    fun claim(domain: Int, namespace: String, rosArgs: List<String>) {
        connect()
        main {
            val status = control!!.status!!
            assertEquals("Stop the fixture first", "Stopped", status.getString("state"))
            original = JSONObject(status.getJSONObject("config").toString())
        }

        configure(domain, namespace, rosArgs)
    }

    /** Saves an automatic-address profile; the runner validates it. */
    fun configure(domain: Int, namespace: String, rosArgs: List<String>) =
        command(
            ProjectProtocol.CONFIGURE,
            Bundle().apply {
                putString("domain", "$domain")
                putString("namespace", namespace)
                putString("peer", "")
                putString("local", "")
                putString("ros_args", JSONArray(rosArgs).toString())
            },
        )

    fun start() {
        command(ProjectProtocol.START)
        state("Running", 20)
    }

    /**
     * Stops the fixture if needed and restores the remembered profile. Does nothing more when
     * unclaimed.
     */
    fun release() {
        val saved = original ?: return close()

        try {
            var usable = false
            main { usable = control?.status != null && control?.error == null }
            if (!usable) connect()
            waitFor("Runner state unknown", 30) {
                it.poll()
                it.status?.optString("state") in setOf("Stopped", "Running")
            }

            if (freshStatus().optString("state") == "Running") {
                command(ProjectProtocol.STOP)
                state("Stopped")
            }

            command(
                ProjectProtocol.CONFIGURE,
                Bundle().apply { for (key in PROFILE_KEYS) putString(key, saved.getString(key)) },
            )
            main {
                val restored = control!!.status!!.getJSONObject("config")

                for (key in PROFILE_KEYS) {
                    assertEquals(
                        "Runner profile not restored: $key",
                        saved.getString(key),
                        restored.getString(key),
                    )
                }
            }

            original = null
        } finally {
            close()
        }
    }

    fun close() = main {
        control?.close()
        control = null
    }
}
