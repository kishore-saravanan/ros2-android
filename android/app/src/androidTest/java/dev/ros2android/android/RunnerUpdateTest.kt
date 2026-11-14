package dev.ros2android.android

import android.content.*
import android.os.*
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in: updating a running runner while the app's controls are bound. The update must end the old
 * process and invalidate controls; after Refresh the runner is Stopped with its saved profile and
 * no replayed start; an explicit Start then produces fresh ROS data.
 *
 * Arguments: runnerUpdatePackage (fixture_cpp or fixture_python) and runnerUpdateApk, an APK of the
 * same package already pushed to /data/local/tmp. Headless; Wi-Fi off; disposable fixtures only.
 */
@RunWith(AndroidJUnit4::class)
class RunnerUpdateTest {
    @Test
    fun updatedRunnerInvalidatesControlsAndRestartsOnlyWhenAsked() {
        val arguments = InstrumentationRegistry.getArguments()
        val target = arguments.getString("runnerUpdatePackage")
        val apk = arguments.getString("runnerUpdateApk")
        assumeTrue(
            "Opt in with runnerUpdatePackage and runnerUpdateApk",
            target != null && apk != null,
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val project = FixtureProject(instrumentation, target!!)
        assertTrue(
            "Push the update APK to /data/local/tmp first",
            apk!!.matches(Regex("/data/local/tmp/[A-Za-z0-9_.-]+\\.apk")),
        )
        val context = instrumentation.targetContext
        assertEquals(
            "Phone-local check requires Wi-Fi off",
            0,
            Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, -1),
        )
        lateinit var session: RobotSessionService
        val bound = CountDownLatch(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    session = (binder as RobotSessionService.LocalBinder).session
                    bound.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {}
            }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun waitFor(label: String, seconds: Int = 20, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + seconds * 1000L
            var passed = false
            while (!passed && SystemClock.elapsedRealtime() < end) {
                main { passed = predicate() }
                if (!passed) SystemClock.sleep(100)
            }

            assertTrue(label, passed)
        }

        val observed =
            ControlWidget("update_observed", "Update", "Subscribed value", "/update/observed")
        fun fresh(trial: String) =
            waitFor("No fresh telemetry for $trial") {
                session.dashboardReadings[observed.id]?.let {
                    JSONObject(it.value).optString("trial") == trial
                } == true
            }
        var ownsSession = false
        main {
            assertTrue(
                context.bindService(
                    Intent(context, RobotSessionService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            )
        }

        var failure: Throwable? = null

        try {
            assertTrue(bound.await(5, TimeUnit.SECONDS))
            main {
                assertFalse(
                    "Stop the main session first",
                    session.connected || session.busy || session.controlsEnabled,
                )
            }

            val before = java.util.UUID.randomUUID().toString()
            project.claim(85, "/update", listOf("-p", "trial:=$before"))
            main {
                ownsSession = true
                session.startControls(
                    listOf(observed),
                    RobotSessionService.Profile(SessionConfig("/update", 85), "", ""),
                )
            }

            waitFor("Dashboard not ready") { session.controlsEnabled && !session.busy }
            project.start()
            fresh(before)
            // Update while bound and running, as a PC deploy would.
            val output =
                instrumentation.uiAutomation.executeShellCommand("pm install -r $apk").let {
                    descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
                        it.readText()
                    }
                }
            assertTrue("Update failed: $output", output.contains("Success"))
            val reason = project.awaitControlError()
            assertTrue("Unexpected controller message: $reason", reason.contains("Runner"))
            waitFor("Old telemetry must become Stale", 15) {
                session.dashboardReadings[observed.id]?.state(SystemClock.elapsedRealtime()) ==
                    "Stale"
            }

            project.connect()
            project.freshStatus().let { status ->
                assertEquals(
                    "Update must not leave the project running",
                    "Stopped",
                    status.optString("state"),
                )
                val saved = status.getJSONObject("config")
                assertEquals("Profile not preserved", "85", saved.getString("domain"))
                assertEquals("Profile not preserved", "/update", saved.getString("namespace"))
            }

            SystemClock.sleep(3000) // No replayed START may arrive.
            assertEquals(
                "Start was replayed after the update",
                "Stopped",
                project.freshStatus().optString("state"),
            )
            val after = java.util.UUID.randomUUID().toString()
            project.configure(85, "/update", listOf("-p", "trial:=$after"))
            project.start()
            fresh(after)
            android.util.Log.i(
                "ROS2Qualification",
                "runner update: invalidated, Stopped with profile, no replay, explicit " +
                    "restart fresh",
            )
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // Try every cleanup; report the first problem unless the test body already failed.
            fun cleanup(label: String, action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    android.util.Log.e("ROS2Qualification", "cleanup failed: $label", error)
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }

            val bodyFailed = failure != null
            cleanup("main session") {
                if (ownsSession) {
                    main { session.stopSession() }
                    waitFor("Session did not stop") { !session.connected && !session.busy }
                }
            }

            cleanup("runner profile") { project.release() }
            cleanup("binding") { main { context.unbindService(connection) } }

            if (!bodyFailed) failure?.let { throw it }
        }
    }
}
