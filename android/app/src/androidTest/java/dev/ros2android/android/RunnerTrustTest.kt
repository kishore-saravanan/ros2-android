package dev.ros2android.android

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.widget.Button
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: a disposable, independently signed talker workspace, never an actuator project. */
@RunWith(AndroidJUnit4::class)
class RunnerTrustTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun independentSignerNeedsApprovalThenCanConfigureStartStopAndRevoke() {
        val target = InstrumentationRegistry.getArguments().getString("trustTestPackage")
        assumeTrue("Requires a disposable independent signer fixture", target != null)
        require(target == "dev.ros2android.android.runner.ws_auth_workspace")
        lateinit var control: ProjectController
        fun waitFor(label: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            var passed = false
            while (!passed && SystemClock.elapsedRealtime() < deadline) {
                activity.scenario.onActivity { passed = predicate() }
                SystemClock.sleep(100)
            }

            assertTrue(label, passed)
        }

        activity.scenario.onActivity { app ->
            check(
                !app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
                    app.hasWindowFocus()
            )
            val project = InstalledProjects.list(app).single { it.packageName == target }
            assertNotEquals(
                PackageManager.SIGNATURE_MATCH,
                app.packageManager.checkSignatures(app.packageName, target),
            )
            control = ProjectController(app, project) {}
            assertTrue(control.trust.paired)
            assertTrue(control.trust.compatible())
            control.trust.revoke()
            control.connect()
            assertNull(control.status)
            assertTrue(control.error!!.contains("Authorize"))

            try {
                control.trust.approve("00".repeat(32))
                fail("Changed certificate accepted")
            } catch (_: IllegalStateException) {}
            control.trust.approve(control.trust.fingerprint()!!)
            control.connect()
        }

        try {
            waitFor("Approved runner did not reply") { control.status != null }
            // Pass the bound endpoint to an isolated process: even possession of the
            // Binder must not allow a different UID to read status or issue commands.
            var denied = false
            var probeStarted = false
            var runnerBound = false
            var probeBound = false
            lateinit var targetBinder: IBinder
            lateinit var probeContext: Context
            val response =
                Messenger(
                    object : Handler(Looper.getMainLooper()) {
                        override fun handleMessage(msg: Message) {
                            assertNotEquals(android.os.Process.myUid(), msg.data.getInt("uid"))
                            if (msg.what == 0) {
                                probeStarted = true
                                return
                            }

                            assertNull(msg.data.getString("failure"))
                            assertEquals(0, msg.data.getInt("replies"))
                            denied = true
                        }
                    }
                )
            val probeConnection =
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        Messenger(binder)
                            .send(
                                Message.obtain(null, 1).apply {
                                    replyTo = response
                                    data = Bundle().apply { putBinder("target", targetBinder) }
                                }
                            )
                    }

                    override fun onServiceDisconnected(name: ComponentName) {}
                }
            val runnerConnection =
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        targetBinder = binder
                        probeBound =
                            probeContext.bindService(
                                Intent(probeContext, UnauthorizedRunnerProbeService::class.java),
                                probeConnection,
                                Context.BIND_AUTO_CREATE,
                            )
                    }

                    override fun onServiceDisconnected(name: ComponentName) {}
                }
            try {
                activity.scenario.onActivity { app ->
                    probeContext = app.applicationContext
                    runnerBound =
                        probeContext.bindService(
                            Intent()
                                .setComponent(
                                    ComponentName(
                                        target!!,
                                        "dev.ros2android.runner.PairedProjectControlService",
                                    )
                                ),
                            runnerConnection,
                            Context.BIND_AUTO_CREATE,
                        )
                }

                waitFor("Isolated caller did not bind") { probeBound }
                waitFor("Isolated caller did not start") { probeStarted }
                waitFor("Unauthorized caller was not denied or probe failed") { denied }
            } finally {
                activity.scenario.onActivity {
                    if (probeBound) probeContext.unbindService(probeConnection)
                    if (runnerBound) probeContext.unbindService(runnerConnection)
                }
            }

            activity.scenario.onActivity {
                control.send(4, Bundle().apply { putString("domain", "87") })
            }

            waitFor("Accepted profile not reported") {
                control.status?.optJSONObject("config")?.optString("domain") == "87"
            }

            activity.scenario.onActivity { control.send(2) }
            waitFor("Runner did not start") {
                control.poll()
                control.status?.optString("state") == "Running"
            }

            waitFor("Two owned nodes missing") {
                control.poll()
                control.status?.optJSONObject("native")?.optJSONArray("nodes")?.length() == 2
            }

            SystemClock.sleep(8000) // Independent PC peer checks actual processed ROS messages.
            activity.scenario.onActivity { control.send(3) }
            waitFor("Runner did not stop") {
                control.poll()
                control.status?.optString("state") == "Stopped"
            }

            activity.scenario.recreate()
            activity.scenario.onActivity { app ->
                val fresh =
                    ProjectController(
                        app,
                        InstalledProjects.list(app).single { it.packageName == target },
                    ) {}
                assertTrue("Approval lost on Activity recreation", fresh.trust.authorized())
                fresh.close()
                control.trust.revoke()
                control.send(2) // Existing controller must not retain authority after revocation.
                assertNull(control.status)
                assertFalse(control.trust.authorized())
                app.findViewById<View>(android.R.id.content)
                    .findViewWithTag<Button>("nav:Projects")
                    .performClick()
                app.findViewById<View>(android.R.id.content)
                    .findViewWithTag<Button>("expand:project:auth_workspace")
                    .performClick()
            }

            SystemClock.sleep(300)
            activity.scenario.onActivity { app ->
                check(
                    !app.getSystemService(android.app.KeyguardManager::class.java)
                        .isKeyguardLocked && app.hasWindowFocus()
                )
                val root = app.findViewById<View>(android.R.id.content)
                assertNotNull(root.findViewWithTag<View>("authorize_project:auth_workspace"))
                val scroll = root.findViewWithTag<android.widget.ScrollView>("page_scroll")
                val authorization = root.findViewWithTag<View>("authorize_project:auth_workspace")
                val rect = android.graphics.Rect()
                authorization.getDrawingRect(rect)
                scroll.offsetDescendantRectToMyCoords(authorization, rect)
                scroll.scrollTo(0, rect.top - 100)
            }

            SystemClock.sleep(200)
            activity.scenario.onActivity { app ->
                check(
                    !app.getSystemService(android.app.KeyguardManager::class.java)
                        .isKeyguardLocked && app.hasWindowFocus()
                )
                val root = app.findViewById<View>(android.R.id.content)
                val image =
                    android.graphics.Bitmap.createBitmap(
                        root.width,
                        root.height,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                root.draw(android.graphics.Canvas(image))
                java.io.File(app.cacheDir, "runner-trust-proof.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }

                image.recycle()
            }
        } finally {
            activity.scenario.onActivity {
                control.close()
                control.trust.revoke()
            }
        }
    }
}
