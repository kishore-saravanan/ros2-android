package dev.ros2android.android

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.*
import android.os.*
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real ROS traffic from a disposable two-node workspace, without cameras or actuators. */
@RunWith(AndroidJUnit4::class)
class WorkspaceLocalLifecycleTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun offlineResultsRecoverAfterRestart() = qualify(background = false)

    @Test
    fun offlineResultsContinueInBackgroundAndRecoverAfterRestart() = qualify(background = true)

    @Test fun offlineResultsSurviveOwnTaskRemoval() = qualify(false, Interruption.TASK_REMOVAL)

    @Test fun offlineResultsRecoverAfterNativeChildExit() = qualify(false, Interruption.CHILD_EXIT)

    @Test fun offlineResultsRecoverAfterRunnerDeath() = qualify(false, Interruption.RUNNER_DEATH)

    @Test
    fun offlineResultsRecoverAfterRunnerForceStop() = qualify(false, Interruption.RUNNER_FORCE_STOP)

    @Test
    fun prepareMainProcessDeath() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("workspaceMainDeathPhase") == "prepare"
        )
        qualify(false, Interruption.MAIN_DEATH)
    }

    @Test
    fun recoverAfterMainProcessDeath() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("workspaceMainDeathPhase") == "recover"
        )
        qualify(false, Interruption.MAIN_RECOVERY)
    }

    private enum class Interruption {
        NONE,
        TASK_REMOVAL,
        CHILD_EXIT,
        RUNNER_DEATH,
        RUNNER_FORCE_STOP,
        MAIN_DEATH,
        MAIN_RECOVERY,
    }

    private fun qualify(background: Boolean, interruption: Interruption = Interruption.NONE) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = InstrumentationRegistry.getArguments().getString("workspaceLocalTestPackage")
        assumeTrue("Requires the disposable workspace fixture", target != null)
        require(target == "dev.ros2android.android.runner.ws_protocol_workspace")
        val context = instrumentation.targetContext
        val deathMode =
            InstrumentationRegistry.getArguments().getString("workspaceMainDeathMode", "sigkill")
        require(deathMode in setOf("sigkill", "force-stop"))
        val checkpointFile = File(context.cacheDir, "workspace-main-death.json")
        val checkpoint =
            if (interruption == Interruption.MAIN_RECOVERY) {
                JSONObject(checkpointFile.readText()).also {
                    require(it.optString("mode", "sigkill") == deathMode) {
                        "Checkpoint mode differs from requested trial"
                    }

                    require(it.getInt("schema") == 1 && it.getInt("pid") != Process.myPid()) {
                        "Requires a new main process"
                    }

                    require(SystemClock.elapsedRealtime() - it.getLong("elapsed") in 0..600000) {
                        "Checkpoint expired; restore fixture manually"
                    }
                }
            } else {
                null
            }
        if (interruption == Interruption.MAIN_DEATH) {
            check(!checkpointFile.exists()) { "Resolve the existing death-test checkpoint first" }
        }

        fun savedSettings(): JSONObject =
            JSONObject().apply {
                val endpoints =
                    context.getSharedPreferences("endpoints_v1", Context.MODE_PRIVATE).all
                put(
                    "endpoints",
                    JSONObject().apply {
                        endpoints.toSortedMap().forEach { (key, value) ->
                            put(key, value.toString())
                        }
                    },
                )
                put(
                    "widgets",
                    context
                        .getSharedPreferences("control_dashboard_v1", Context.MODE_PRIVATE)
                        .getString("widgets", null) ?: JSONObject.NULL,
                )
            }
        val screenOff =
            InstrumentationRegistry.getArguments().getString("workspaceScreenOff") == "true"
        require(!screenOff || background) {
            "Screen-off qualification requires the background method"
        }

        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val backgroundSeconds =
            InstrumentationRegistry.getArguments()
                .getString("workspaceBackgroundSeconds", "10")
                .toLong()
        require(backgroundSeconds in 1..300) { "Use 1–300 seconds for the background trial" }
        val keepBound =
            InstrumentationRegistry.getArguments().getString("workspaceKeepControllerBound") ==
                "true"
        val local =
            if (InstrumentationRegistry.getArguments().getString("workspaceLocalOnly") == "true") {
                "127.0.0.1"
            } else {
                ""
            }
        assertEquals(
            "Turn Wi-Fi off before this fresh-start test",
            0,
            Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, -1),
        )
        lateinit var session: RobotSessionService
        lateinit var control: ProjectController
        var controlReady = false
        var disconnected = false
        var replacement: ActivityScenario<MainActivity>? = null
        data class OwnProcess(val pid: Int, val parent: Int, val name: String)
        val fixtureUid = context.packageManager.getApplicationInfo(target, 0).uid
        fun shell(command: String): String {
            if (Build.VERSION.SDK_INT >= 31) {
                val streams = instrumentation.uiAutomation.executeShellCommandRwe(command)
                streams[1].close()
                val output =
                    ParcelFileDescriptor.AutoCloseInputStream(streams[0]).bufferedReader().use {
                        it.readText().trim()
                    }
                val error =
                    ParcelFileDescriptor.AutoCloseInputStream(streams[2]).bufferedReader().use {
                        it.readText().trim()
                    }
                check(error.isEmpty()) { "Fixture shell command failed: $error" }
                return output
            }

            return ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command)
                )
                .bufferedReader()
                .use { it.readText().trim() }
        }

        fun processes(): List<OwnProcess> =
            shell("run-as $target ps -u $fixtureUid -o PID,PPID,NAME")
                .lineSequence()
                .drop(1)
                .filter { it.isNotBlank() }
                .map {
                    val fields = it.trim().split(Regex("\\s+"), limit = 3)
                    OwnProcess(fields[0].toInt(), fields[1].toInt(), fields[2])
                }
                .toList()
        fun inject(action: String) {
            val reply =
                shell(
                    "am broadcast --include-stopped-packages -n " +
                        "$target/dev.ros2android.runner.DebugControlReceiver -a " +
                        "dev.ros2android.runner.$action"
                )
            check(reply.contains("result=0")) { "Fixture rejected failure injection: $reply" }
        }

        var injectedChildren = emptyList<OwnProcess>()
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
        fun waitFor(label: String, timeout: Long = 15000, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            var passed = false
            while (!passed && SystemClock.elapsedRealtime() < deadline) {
                main { passed = condition() }
                if (!passed) SystemClock.sleep(100)
            }

            assertTrue(label, passed)
        }

        fun connect() {
            main {
                control =
                    ProjectController(
                        context,
                        InstalledProjects.list(context).single { it.packageName == target },
                    ) {
                        if (control.error != null) disconnected = true
                    }
                controlReady = true
                control.connect()
            }

            waitFor("No fixture status") { control.status != null }
        }

        fun command(command: Int, config: Bundle = Bundle()) {
            var previous: JSONObject? = null
            main {
                previous = control.status
                control.send(command, config)
            }

            // A matching cached profile/state is not an acknowledgement of this command.
            waitFor("Runner did not acknowledge command $command") {
                check(control.error == null) { control.error.orEmpty() }
                check(control.commandError == null) { control.commandError.orEmpty() }
                control.status != null && control.status !== previous
            }
        }

        fun state(expected: String) =
            waitFor("Fixture did not become $expected") {
                if (control.status?.optString("state") == expected) {
                    true
                } else {
                    control.poll()
                    false
                }
            }
        val localEcho =
            ControlWidget(
                "offline_local_echo",
                "Local probe",
                "Subscribed value",
                "/qualification/local_echo",
            )
        val widget =
            ControlWidget(
                "offline_result_test",
                "Workspace result",
                "Subscribed value",
                "/android_test/result",
            )
        var original: JSONObject? = null
        var ownsFixture = false
        var ownsSession = false
        var recoveryPassed = false
        val serviceBound =
            context.bindService(
                Intent(context, RobotSessionService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        assertTrue(serviceBound)

        try {
            assertTrue("Session did not bind", bound.await(5, TimeUnit.SECONDS))
            activity.scenario.onActivity { app ->
                check(
                    !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                        app.hasWindowFocus()
                )
                assertFalse(
                    "Stop the main session before testing",
                    session.connected || session.busy || session.controlsEnabled,
                )

                if (checkpoint != null) {
                    assertTrue(
                        "Hardware/session state was replayed after death",
                        session.active.isEmpty() && !session.subscribed,
                    )
                    assertEquals(
                        "Saved settings changed across death",
                        checkpoint.getJSONObject("settings").toString(),
                        savedSettings().toString(),
                    )
                }
            }

            connect()
            main {
                assertEquals(
                    "Unexpected disposable fixture state",
                    if (checkpoint == null) "Stopped" else "Running",
                    control.status!!.getString("state"),
                )
                original =
                    if (checkpoint == null) {
                        JSONObject(control.status!!.getJSONObject("config").toString())
                    } else {
                        checkpoint.getJSONObject("runnerProfile")
                    }
                ownsFixture = true
            }

            if (checkpoint == null) {
                command(
                    4,
                    Bundle().apply {
                        putString("domain", "87")
                        putString("namespace", "/android_test")
                        putString("peer", "")
                        putString("local", local)
                        putString("ros_args", "[]")
                    },
                )
                main {
                    assertEquals("87", control.status!!.getJSONObject("config").getString("domain"))
                }

                command(2)
                state("Running")
            }

            main {
                ownsSession = true
                session.startControls(
                    listOf(widget, localEcho),
                    RobotSessionService.Profile(SessionConfig("/qualification", 87), "", local),
                )
            }

            waitFor("No workspace result reached the main app") {
                session.dashboardReadings[widget.id] != null
            }

            main { NativeRuntime.createPublisher("qualification_echo", localEcho.topic, 11) }
            fun observe(label: String, milliseconds: Long): Long {
                val startedAt = SystemClock.elapsedRealtime()
                val end = startedAt + milliseconds
                var lastObservation = startedAt
                var previous = -1L
                var fresh = 0L

                while (SystemClock.elapsedRealtime() < end) {
                    val observedAt = SystemClock.elapsedRealtime()
                    assertTrue(
                        "$label: observer paused for more than two seconds",
                        observedAt - lastObservation < 2000,
                    )

                    if (screenOff && label == "screen-off") {
                        assertFalse(
                            "Keep the screen off for the entire observation interval",
                            power.isInteractive,
                        )
                    }

                    main {
                        assertTrue(
                            "Local probe write failed",
                            NativeRuntime.publishControl(
                                "qualification_echo",
                                doubleArrayOf(0.0, 0.0),
                                SystemClock.elapsedRealtime().toString(),
                            ),
                        )
                        val reading = checkNotNull(session.dashboardReadings[widget.id])
                        assertTrue(
                            "$label: result gap exceeds two seconds; connected=${session.connected}, dashboard=${session.controlsEnabled}, localProbeAge=${session.dashboardReadings[localEcho.id]?.let { SystemClock.elapsedRealtime() - it.receivedAt }}, errors=${session.errors}",
                            SystemClock.elapsedRealtime() - reading.receivedAt < 2000,
                        )
                        val value = JSONObject(reading.value)
                        val sequence = value.getLong("sequence")
                        assertTrue("$label: sequence reversed", sequence >= previous)
                        assertEquals(
                            "$label: incorrect processor result",
                            sequence * sequence * 0.0625,
                            value.getDouble("squared"),
                            0.00001,
                        )

                        if (sequence != previous) {
                            fresh++
                            previous = sequence
                        }
                    }

                    assertTrue(
                        "$label: main-thread observation paused for more than two seconds",
                        SystemClock.elapsedRealtime() - observedAt < 2000,
                    )
                    lastObservation = observedAt
                    SystemClock.sleep(100)
                }

                assertTrue(
                    "$label: trial exceeded its duration by more than two seconds",
                    SystemClock.elapsedRealtime() - startedAt < milliseconds + 2000,
                )
                assertTrue(
                    "$label: insufficient fresh ROS results",
                    fresh >= maxOf(5, milliseconds / 1000),
                )
                android.util.Log.i("ROS2Qualification", "$label: $fresh distinct verified results")
                return previous
            }

            val sequence =
                observe(if (checkpoint == null) "foreground" else "main-death-recovery", 3000)

            if (checkpoint != null) {
                assertTrue(
                    "Workspace sequence did not advance across main-app death",
                    sequence > checkpoint.getLong("sequence"),
                )
            }

            if (interruption == Interruption.MAIN_DEATH) {
                val record =
                    JSONObject()
                        .put("schema", 1)
                        .put("mode", deathMode)
                        .put("pid", Process.myPid())
                        .put("elapsed", SystemClock.elapsedRealtime())
                        .put("sequence", sequence)
                        .put("runnerProfile", original)
                        .put("settings", savedSettings())
                        .put(
                            "processes",
                            org.json.JSONArray(
                                processes()
                                    .filter {
                                        it.name == target ||
                                            it.name.startsWith("libworkspace_exec_")
                                    }
                                    .map {
                                        JSONObject()
                                            .put("pid", it.pid)
                                            .put("parent", it.parent)
                                            .put("name", it.name)
                                    }
                            ),
                        )
                checkpointFile.outputStream().use { stream ->
                    stream.write(record.toString().toByteArray())
                    stream.flush()
                    stream.fd.sync()
                }

                main { control.close() }

                if (deathMode == "force-stop") {
                    // Keep instrumentation alive until the driver stops this active app.
                    android.util.Log.i(
                        "ROS2Qualification",
                        "Checkpoint saved; waiting for external Force stop",
                    )
                    SystemClock.sleep(60000)
                    error("External Force stop did not occur within sixty seconds")
                }

                android.util.Log.i(
                    "ROS2Qualification",
                    "Checkpoint saved; deliberately terminating main process",
                )
                Process.killProcess(Process.myPid())
                error("Main process termination did not occur")
            }

            if (interruption == Interruption.TASK_REMOVAL) {
                var task = -1
                main { control.close() }
                activity.scenario.onActivity { app ->
                    check(app.hasWindowFocus() && !keyguard.isKeyguardLocked)
                    task = app.taskId
                    app.finishAndRemoveTask()
                }

                val removedBy = SystemClock.elapsedRealtime() + 5000

                while (
                    activity.scenario.state != Lifecycle.State.DESTROYED &&
                        SystemClock.elapsedRealtime() < removedBy
                ) {
                    SystemClock.sleep(100)
                }

                assertEquals(
                    "Main Activity was not destroyed",
                    Lifecycle.State.DESTROYED,
                    activity.scenario.state,
                )
                waitFor("Main app task was not removed") {
                    context.getSystemService(ActivityManager::class.java).appTasks.none {
                        it.taskInfo.taskId == task
                    }
                }

                observe("task-removed", 30000)
                replacement = ActivityScenario.launch(MainActivity::class.java)
                connect()
                state("Running")
            }

            if (
                interruption in
                    setOf(
                        Interruption.CHILD_EXIT,
                        Interruption.RUNNER_DEATH,
                        Interruption.RUNNER_FORCE_STOP,
                    )
            ) {
                val parent = processes().single { it.name == target }
                injectedChildren =
                    processes().filter {
                        it.parent == parent.pid && it.name.startsWith("libworkspace_exec_")
                    }
                assertEquals(
                    "Expected only the fixture's two workspace children",
                    2,
                    injectedChildren.size,
                )

                if (interruption == Interruption.CHILD_EXIT) {
                    check(
                        injectedChildren.single { it.name.contains("talker") }.parent == parent.pid
                    )
                    inject("TEST_EXIT_CHILD")
                    waitFor("Supervisor did not report the node exit") {
                        control.poll()
                        control.status?.optString("state")?.let {
                            it.startsWith("Failed:") && it.contains("exited with code")
                        } == true
                    }
                } else {
                    main { disconnected = false }

                    if (interruption == Interruption.RUNNER_FORCE_STOP) {
                        shell("am force-stop $target")
                    } else {
                        inject("TEST_EXIT_RUNNER")
                    }

                    waitFor("Runner death did not invalidate the control connection") {
                        disconnected
                    }

                    main { control.close() }
                    connect()
                    state("Stopped")
                }

                waitFor("Native workspace processes survived failure") {
                    processes().none { it.name.startsWith("libworkspace_exec_") }
                }

                SystemClock.sleep(6000)
                main {
                    assertEquals(
                        "Results continued after failure",
                        "Stale",
                        session.dashboardReadings[widget.id]!!.state(SystemClock.elapsedRealtime()),
                    )
                    val config = control.status!!.getJSONObject("config")
                    assertEquals("87", config.getString("domain"))
                    assertEquals("/android_test", config.getString("namespace"))

                    for (key in listOf("peer", "local")) {
                        assertEquals(
                            if (key == "local") local else "",
                            config.getString(key),
                        )
                    }

                    assertEquals("[]", config.getString("ros_args"))
                }

                command(2)
                state("Running")
                waitFor("No fresh results after explicit failure recovery") {
                    session.dashboardReadings[widget.id]?.state(SystemClock.elapsedRealtime()) ==
                        "Live"
                }

                observe("${interruption.name.lowercase()}-recovery", 3000)
                return
            }

            if (background) {
                if (!keepBound) main { control.close() }
                activity.scenario.moveToState(Lifecycle.State.CREATED)

                if (screenOff) {
                    // Physical power-button action only: never alter global timeout or lock
                    // settings.
                    android.util.Log.i("ROS2Qualification", "Ready for manual screen-off")
                    waitFor("Turn the screen off manually within 90 seconds", 90000) {
                        !power.isInteractive
                    }

                    observe("screen-off", backgroundSeconds * 1000)
                    android.util.Log.i(
                        "ROS2Qualification",
                        "Screen-off observation complete; unlock and return to ROS2 Android",
                    )
                    waitFor("Unlock the phone after the screen-off interval", 120000) {
                        power.isInteractive && !keyguard.isKeyguardLocked
                    }
                } else {
                    observe("background", backgroundSeconds * 1000)
                }

                activity.scenario.moveToState(Lifecycle.State.RESUMED)

                if (!keepBound) connect()
                state("Running")
            }

            command(3)
            state("Stopped")
            SystemClock.sleep(6000)
            main {
                assertEquals(
                    "Stale",
                    session.dashboardReadings[widget.id]!!.state(SystemClock.elapsedRealtime()),
                )
            }

            command(2)
            state("Running")
            waitFor("Results did not recover after workspace restart") {
                session.dashboardReadings[widget.id]?.state(SystemClock.elapsedRealtime()) == "Live"
            }

            observe("restart", 3000)
            recoveryPassed = checkpoint != null
        } finally {
            if (ownsSession) {
                main { session.stopSession() }
                waitFor("Main test session did not stop") { !session.connected && !session.busy }
            }

            if (ownsFixture) {
                // Reconnect if the test failed while its Activity was backgrounded.
                main { control.close() }
                connect()
                command(3)
                state("Stopped")
                command(
                    4,
                    Bundle().apply {
                        for (key in listOf("domain", "namespace", "peer", "local", "ros_args")) {
                            putString(
                                key,
                                original!!.getString(key),
                            )
                        }
                    },
                )
                waitFor("Fixture configuration was not restored") {
                    val config = control.status?.optJSONObject("config")
                    config != null &&
                        listOf("domain", "namespace", "peer", "local", "ros_args").all {
                            config.getString(it) == original!!.getString(it)
                        }
                }
            }

            if (controlReady) main { control.close() }
            context.unbindService(connection)

            // A timed-out manual trial must not attempt UI work on a locked phone.
            if (
                power.isInteractive &&
                    !keyguard.isKeyguardLocked &&
                    activity.scenario.state != Lifecycle.State.DESTROYED
            ) {
                activity.scenario.moveToState(Lifecycle.State.RESUMED)
            }

            replacement?.close()

            if (recoveryPassed) {
                check(checkpointFile.delete()) { "Could not remove completed test checkpoint" }
            }
        }
    }
}
