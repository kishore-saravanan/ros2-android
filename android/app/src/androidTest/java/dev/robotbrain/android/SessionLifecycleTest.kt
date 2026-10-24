package dev.robotbrain.android

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SessionLifecycleTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun cameraAndImuSurviveActivityRecreationAndBackgroundThenStop() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Camera lifecycle test requires explicit opt-in", args.getString("testCameraLifecycle") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, ctx.checkSelfPermission(Manifest.permission.CAMERA))
        val bound = CountDownLatch(1)
        lateinit var session: RobotSessionService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                session = (binder as RobotSessionService.LocalBinder).session; bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        ctx.bindService(Intent(ctx, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue("Service bind timed out", bound.await(5, TimeUnit.SECONDS))
        val camera = CapabilityCatalog.publishers(ctx).first { it.kind == 8 && it.label.startsWith("Back") }
        val imu = CapabilityCatalog.publishers(ctx).first { it.id == "imu" }
        val profile = RobotSessionService.Profile(SessionConfig("/phone", 0), args.getString("pcPeer", ""), args.getString("localAddress", ""))
        fun waitFor(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue(message, condition())
        }
        try {
            activity.scenario.onActivity { session.startPublisher(camera, EndpointConfig(camera.defaultTopic, 30.0), profile) }
            waitFor("Camera did not publish") { NativeRuntime.publishedCount(camera.id) >= 10 }
            activity.scenario.onActivity { session.startPublisher(imu, EndpointConfig(imu.defaultTopic, 50.0), profile) }
            waitFor("IMU did not publish") { NativeRuntime.publishedCount(imu.id) >= 5 }
            activity.scenario.recreate()
            val beforeRecreate = NativeRuntime.publishedCount(camera.id)
            waitFor("Camera stopped after recreation") { NativeRuntime.publishedCount(camera.id) > beforeRecreate + 10 }
            activity.scenario.moveToState(Lifecycle.State.CREATED)
            val beforeBackground = NativeRuntime.publishedCount(camera.id)
            val imuBefore = NativeRuntime.publishedCount(imu.id)
            SystemClock.sleep(args.getString("backgroundSeconds", "10").toLong() * 1000)
            assertTrue("Camera stopped in background", NativeRuntime.publishedCount(camera.id) > beforeBackground + 50)
            assertTrue("IMU stopped in background", NativeRuntime.publishedCount(imu.id) > imuBefore + 50)
            activity.scenario.moveToState(Lifecycle.State.RESUMED)
            activity.scenario.onActivity {
                assertTrue(session.active.containsAll(listOf(camera.id, imu.id)))
                assertTrue("Camera failed: ${session.errors}", session.errors.isEmpty())
                session.stopSession()
            }
            waitFor("Session did not stop") { NativeRuntime.status().contains("stopped") }
            assertEquals(0L, NativeRuntime.publishedCount(camera.id))
            assertEquals(0L, NativeRuntime.publishedCount(imu.id))
            // Start again after a full stop, then exercise the exact notification Stop intent.
            activity.scenario.onActivity { session.startPublisher(camera, EndpointConfig(camera.defaultTopic, 15.0), profile) }
            waitFor("Camera did not restart after full stop") { NativeRuntime.publishedCount(camera.id) >= 5 }
            val stop = PendingIntent.getService(ctx, 1,
                Intent(ctx, RobotSessionService::class.java).setAction("dev.robotbrain.android.STOP_SESSION"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
            assertNotNull("Notification Stop action missing", stop)
            stop.send()
            waitFor("Notification Stop did not release ROS") { NativeRuntime.status().contains("stopped") }
        } finally {
            instrumentation.runOnMainSync { session.stopSession() }
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (!NativeRuntime.status().contains("stopped") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            ctx.unbindService(connection)
        }
    }
}
