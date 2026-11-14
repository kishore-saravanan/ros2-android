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
 * Headless phone-local DDS to an opt-in harmless peer in a separate runner process. No Activity
 * launch, UI input, capture or personal phone data; no foreground-screen prerequisite.
 */
@RunWith(AndroidJUnit4::class)
class DashboardPeerRecoveryTest {
    @Test fun cppLateSubscriberAndPeerRecovery() = qualify("cpp")

    @Test fun pythonLateSubscriberAndPeerRecovery() = qualify("python")

    private fun qualify(backend: String) {
        val target = "dev.ros2android.android.runner.fixture_$backend"
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Install the disposable fixture and opt in",
            arguments.getString("dashboardRecoveryPackage") in setOf(target, "both"),
        )
        // Diagnostic runs may keep battery optimization to reproduce vendor pauses; qualification
        // may not.
        val requireBackgroundAllowed =
            arguments.getString("dashboardRecoveryAllowBatteryOptimized") != "true"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(
            "Fresh offline startup requires Wi-Fi off",
            0,
            Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, -1),
        )

        if (requireBackgroundAllowed) {
            for (pkg in listOf(context.packageName, target)) {
                assertEquals(
                    "Allow background activity (battery optimization off) for $pkg; " +
                        "otherwise Android may pause it",
                    false,
                    dev.ros2android.health.BackgroundPolicy.batteryOptimized(context, pkg),
                )
            }
        }

        lateinit var session: RobotSessionService
        lateinit var control: ProjectController
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
        fun waitFor(label: String, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 20000
            var passed = false
            while (!passed && SystemClock.elapsedRealtime() < end) {
                main { passed = predicate() }
                if (!passed) SystemClock.sleep(100)
            }

            assertTrue(label, passed)
        }

        fun command(value: Int, arguments: Bundle = Bundle()) {
            var previous: JSONObject? = null
            main {
                previous = control.status
                control.send(value, arguments)
            }

            waitFor("No authenticated reply for $value") {
                check(control.error == null) { control.error.orEmpty() }
                check(control.commandError == null) { control.commandError.orEmpty() }
                control.status != null && control.status !== previous
            }
        }

        fun state(expected: String) {
            var last: JSONObject? = null

            try {
                waitFor("Fixture did not become $expected") {
                    control.poll()
                    last = control.status
                    val observed = control.status?.optString("state").orEmpty()
                    check(!observed.startsWith("Failed")) { control.status.toString() }
                    observed == expected
                }
            } catch (error: AssertionError) {
                throw AssertionError("${error.message}; last status=$last", error)
            }
        }

        fun freshStatus(): JSONObject {
            var previous: JSONObject? = null
            main {
                previous = control.status
                control.poll()
            }

            var status: JSONObject? = null
            waitFor("Fixture status unavailable") {
                status = control.status
                status != null && status !== previous
            }

            return status!!
        }

        // Executor liveness (the native heartbeat timer shares the project's executor) and
        // runner-reported pauses.
        fun heartbeat(): String =
            freshStatus().let {
                "state=${it.optString("state")} native=${it.optJSONObject("native")} " +
                    "health=${it.optJSONObject("health")}"
            }
        // A process pause means the peer did not keep running, even if a short observation window
        // looked fine.
        fun assertNoPauses(label: String) {
            val health = freshStatus().optJSONObject("health")
            assertNotNull("Rebuild the fixture: runner health status is missing", health)
            assertEquals(
                "Android paused the runner during $label: $health",
                0,
                health!!.getInt("pauses"),
            )
            var mainPauses: dev.ros2android.health.PauseDetector.Snapshot? = null
            main { mainPauses = session.pauses }
            assertEquals(
                "Android paused the main app during $label: $mainPauses",
                0,
                mainPauses!!.pauses,
            )
        }

        val toggle =
            ControlWidget("recovery_request", "Test request", "Switch", "/qualification/request")
        val observed =
            ControlWidget(
                "recovery_observed",
                "Peer observation",
                "Subscribed value",
                "/qualification/observed",
            )
        val widgets = listOf(toggle, observed)
        val profile = RobotSessionService.Profile(SessionConfig("/qualification", 87), "", "")
        var trial = ""
        fun reading(): JSONObject? =
            session.dashboardReadings[observed.id]
                ?.let { JSONObject(it.value) }
                ?.takeIf { it.optString("trial") == trial }
        // Diagnostics only: fixture stamp_ms and receivedAt both use the boot clock, so a
        // gap can be attributed to publishing (stamps pause) or delivery (stamps continue).
        var phase = "setup"
        val history = ArrayDeque<String>()
        var lastSample = ""
        var samples = 0
        var stamped = 0
        var largestGap = 0L
        var largestDelivery = 0L
        var deliveryTotal = 0L
        var previousReceipt: Pair<String, Long>? =
            null // trial and receivedAt of the previous new tick
        fun mark(name: String) {
            phase = name
            android.util.Log.i(
                "ROS2Qualification",
                "$backend phase=$name at=${SystemClock.elapsedRealtime()}",
            )
        }

        fun record() {
            val sample = session.dashboardReadings[observed.id] ?: return
            val value = reading() ?: return
            val tick = value.getLong("tick")
            if ("$trial/$tick" == lastSample) return
            lastSample = "$trial/$tick"
            val stamp = value.optLong("stamp_ms", -1)
            samples++
            previousReceipt
                ?.takeIf { it.first == trial }
                ?.let { largestGap = maxOf(largestGap, sample.receivedAt - it.second) }
            previousReceipt = trial to sample.receivedAt

            if (stamp >= 0) {
                stamped++
                largestDelivery = maxOf(largestDelivery, sample.receivedAt - stamp)
                deliveryTotal += sample.receivedAt - stamp
            }

            history.addLast(
                "$phase tick=$tick count=${value.getInt("count")} stamp=$stamp " +
                    "received=${sample.receivedAt}" +
                    (if (stamp >= 0) " delivery=${sample.receivedAt - stamp}ms" else "")
            )

            while (history.size > 40) history.removeFirst()
        }

        fun startPeer() {
            trial = java.util.UUID.randomUUID().toString()
            command(
                4,
                Bundle().apply {
                    putString("domain", "87")
                    putString("namespace", "/qualification")
                    putString("peer", "")
                    putString("local", "")
                    putString(
                        "ros_args",
                        org.json.JSONArray(listOf("-p", "trial:=$trial")).toString(),
                    )
                },
            )
            command(2)
            state("Running")
        }

        fun request(on: Boolean) {
            main { session.control(toggle, if (on) 1.0 else 0.0) }
            waitFor("No local write") {
                session
                    .switchDescription(toggle.id)
                    .contains("Requested ${if (on) "ON" else "OFF"} · written locally")
            }

            main {
                assertTrue(session.switchDescription(toggle.id).contains("Remote state unknown"))
            }
        }

        fun observeCount(count: Int, last: Boolean?, milliseconds: Long = 2000) {
            waitFor("Expected peer count $count, last=$last") {
                record()
                reading()?.let {
                    it.getInt("count") == count &&
                        (if (last == null) {
                            it.isNull("last")
                        } else {
                            !it.isNull("last") && it.getBoolean("last") == last
                        })
                } == true
            }

            var firstTick = 0L
            main {
                firstTick = reading()!!.getLong("tick")
                record()
            }

            val end = SystemClock.elapsedRealtime() + milliseconds
            while (SystemClock.elapsedRealtime() < end) {
                main {
                    val value = checkNotNull(reading())
                    assertEquals("Unexpected command replay", count, value.getInt("count"))
                    assertEquals(
                        "Live",
                        session.dashboardReadings[observed.id]!!.state(
                            SystemClock.elapsedRealtime()
                        ),
                    )
                    record()
                }

                SystemClock.sleep(100)
            }

            var advanced = false
            main { advanced = reading()!!.getLong("tick") > firstTick }
            if (advanced) return
            // The assertion below always fails here; first observe whether and how telemetry
            // resumes.
            val stalledAt = SystemClock.elapsedRealtime()
            val failedPhase = phase
            val diagnostics =
                mutableListOf(
                    "fixture at stall: ${runCatching { heartbeat() }.getOrElse { "status error $it" }}"
                )
            fun resume(label: String, after: Long): Boolean {
                val start = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() < start + 10000) {
                    var next: JSONObject? = null
                    main {
                        record()
                        next = reading()?.takeIf { it.getLong("tick") > after }
                    }

                    next?.let {
                        diagnostics +=
                            "$label: resumed after " +
                                "${SystemClock.elapsedRealtime() - start}ms with " +
                                "tick=${it.getLong("tick")}"
                        return true
                    }

                    SystemClock.sleep(50)
                }

                diagnostics += "$label: no newer tick within 10 s"
                return false
            }

            if (!resume("same reader", firstTick)) {
                diagnostics +=
                    "fixture after 10 s: ${runCatching { heartbeat() }.getOrElse { "status error $it" }}"
                // Diagnostic only: does a fresh Dashboard reader receive the same publisher again?
                mark("$phase-diagnostic-restart")
                main {
                    session.stopControls()
                    session.startControls(widgets, profile)
                }

                runCatching {
                    waitFor("Diagnostic Dashboard restart not ready") {
                        session.controlsEnabled && !session.busy
                    }
                }

                resume("fresh reader", firstTick)
            }

            val report =
                "No advancing peer telemetry in phase $failedPhase (count=$count, " +
                    "first=$firstTick, " +
                    "stalled ${SystemClock.elapsedRealtime() - stalledAt}ms ago)\n" +
                    "${diagnostics.joinToString("\n")}\n" +
                    history.joinToString("\n")
            android.util.Log.e("ROS2Qualification", report)
            fail(report)
        }

        var controllerReady = false
        var ownsFixture = false
        var ownsSession = false
        var original: JSONObject? = null
        var failure: Throwable? = null
        main {
            assertTrue(
                context.bindService(
                    Intent(context, RobotSessionService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            )
        }

        try {
            assertTrue(bound.await(5, TimeUnit.SECONDS))
            main {
                assertFalse(
                    "Stop the main session before qualification",
                    session.connected || session.busy || session.controlsEnabled,
                )
                control =
                    ProjectController(
                        context,
                        InstalledProjects.list(context).single { it.packageName == target },
                    ) {}
                controllerReady = true
                control.connect()
            }

            waitFor("Fixture unavailable") {
                control.poll()
                control.status != null
            }

            main {
                assertEquals(
                    "Stop disposable fixture first",
                    "Stopped",
                    control.status!!.getString("state"),
                )
                original = JSONObject(control.status!!.getJSONObject("config").toString())
                ownsFixture = true
            }

            command(
                4,
                Bundle().apply {
                    putString("domain", "87")
                    putString("namespace", "/qualification")
                    putString("peer", "")
                    putString("local", "")
                    putString("ros_args", "[]")
                },
            )
            main {
                ownsSession = true
                session.startControls(widgets, profile)
            }

            waitFor("Dashboard not ready") { session.controlsEnabled && !session.busy }
            var mismatch: Result<List<String>>? = null
            main {
                session.discover(
                    "std_msgs/msg/String",
                    RobotSessionService.Profile(SessionConfig("/different_profile", 88), "", ""),
                ) {
                    mismatch = it
                }
            }

            waitFor("Conflicting profile was not rejected") { mismatch != null && !session.busy }
            main {
                assertTrue(
                    mismatch!!
                        .exceptionOrNull()
                        ?.message
                        .orEmpty()
                        .contains("Stop the sensor and Dashboard session")
                )
                assertTrue(
                    "Existing Dashboard was disturbed",
                    session.connected && session.controlsEnabled,
                )
            }

            mark("write-before-peer")
            request(true) // Written before any subscriber exists: never a remote acknowledgement.
            main { assertTrue(session.dashboardReadings.isEmpty()) }
            mark("first-peer-start")
            startPeer()
            mark("first-peer-late-subscriber")
            observeCount(0, null) // Volatile late subscriber must not get the old ON request.
            mark("first-peer-off")
            request(false)
            observeCount(1, false)
            mark("first-peer-on")
            request(true)
            observeCount(2, true)
            assertNoPauses("the first peer run")
            mark("peer-stop")
            command(3)
            state("Stopped")
            waitFor("Lost peer must become Stale") {
                session.dashboardReadings[observed.id]?.state(SystemClock.elapsedRealtime()) ==
                    "Stale"
            }

            main { assertTrue(session.connected && session.controlsEnabled) }
            mark("write-while-peer-absent")
            request(false) // Local success with absent peer must still say remote state unknown.
            mark("second-peer-start")
            startPeer()
            mark("second-peer-late-subscriber")
            observeCount(0, null)
            mark("second-peer-on")
            request(true)
            observeCount(1, true)
            mark("dashboard-restart")
            main {
                session.stopControls()
                session.startControls(widgets, profile)
            }

            waitFor("Dashboard restart not ready") { session.controlsEnabled && !session.busy }
            mark("after-dashboard-restart")
            observeCount(1, true) // Restart cannot replay the last requested ON.
            mark("after-restart-off")
            request(false)
            observeCount(2, false)
            assertNoPauses("the second peer run and Dashboard restart")
            android.util.Log.i(
                "ROS2Qualification",
                "$backend: offline late subscriber, peer loss/Stale, restart/no replay " +
                    "and explicit fresh commands passed",
            )
            // Observation statistics, not thresholds: sampled every 100 ms by the test, so gaps
            // include that polling.
            android.util.Log.i(
                "ROS2Qualification",
                "$backend delivery: observedTicks=$samples largestReceiptGapMs=$largestGap " +
                    "largestPublishToReceiptMs=$largestDelivery meanPublishToReceiptMs=${if (stamped > 0) deliveryTotal / stamped else -1}",
            )
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // Try every cleanup even if one resource fails; preserve the original test failure.
            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }

            val testFailed = failure != null
            cleanup {
                if (ownsSession) {
                    main { session.stopSession() }
                    waitFor("Main test session did not stop") {
                        !session.connected && !session.busy
                    }
                }
            }

            cleanup {
                if (ownsFixture) {
                    command(3)
                    state("Stopped")
                    command(
                        4,
                        Bundle().apply {
                            for (key in
                                listOf(
                                    "domain",
                                    "namespace",
                                    "peer",
                                    "local",
                                    "ros_args",
                                )) {
                                putString(key, original!!.getString(key))
                            }
                        },
                    )
                    main {
                        val restored = control.status!!.getJSONObject("config")

                        for (key in
                            listOf(
                                "domain",
                                "namespace",
                                "peer",
                                "local",
                                "ros_args",
                            )) {
                            assertEquals(
                                "Fixture profile not restored",
                                original!!.getString(key),
                                restored.getString(key),
                            )
                        }
                    }
                }
            }

            cleanup { if (controllerReady) main { control.close() } }
            cleanup { main { context.unbindService(connection) } }

            if (!testFailed) failure?.let { throw it }
        }
    }
}
