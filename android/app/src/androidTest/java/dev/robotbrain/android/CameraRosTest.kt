package dev.robotbrain.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CameraRosTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun frontAndBackPublishRealJpegs() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Camera test requires explicit opt-in", args.getString("testCameras") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        assertEquals("User camera permission is required", PackageManager.PERMISSION_GRANTED, ctx.checkSelfPermission(Manifest.permission.CAMERA))
        val cameras = CapabilityCatalog.publishers(ctx).filter { it.kind == 8 && (it.label.startsWith("Front") || it.label.startsWith("Back")) }
        assertTrue("Expected front and back camera capabilities", cameras.any { it.label.startsWith("Front") } && cameras.any { it.label.startsWith("Back") })
        val error = AtomicReference<String?>(null)
        val capture = CameraPublisher(ctx) { error.set(it) }
        try {
            NativeRuntime.start(0, "/phone", args.getString("pcPeer", ""), args.getString("localAddress", ""), ctx.applicationInfo.nativeLibraryDir)
            cameras.forEach { cap ->
                error.set(null)
                NativeRuntime.createPublisher(cap.id, cap.defaultTopic, cap.kind)
                instrumentation.runOnMainSync { capture.start(cap, 1.0) }
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (SystemClock.elapsedRealtime() < deadline && NativeRuntime.publishedCount(cap.id) < 5 && error.get() == null) SystemClock.sleep(100)
                assertNull("${cap.label}: ${error.get()}", error.get())
                assertTrue("No real JPEG frames published for ${cap.label}: ${NativeRuntime.status()}", NativeRuntime.publishedCount(cap.id) >= 5)
                instrumentation.runOnMainSync { capture.stop() }
                capture.awaitStopped()
                NativeRuntime.removePublisher(cap.id)
            }
        } finally {
            instrumentation.runOnMainSync { capture.stop() }
            capture.awaitStopped()
            if (NativeRuntime.available) NativeRuntime.stop()
        }
    }
    @Test fun continuousCameraSustainsRequestedRate() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Continuous camera test requires explicit opt-in", args.getString("testContinuousCamera") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, ctx.checkSelfPermission(Manifest.permission.CAMERA))
        val cap = CapabilityCatalog.publishers(ctx).first { it.kind == 8 && it.label.startsWith(args.getString("cameraFacing", "Back")) }
        val rate = args.getString("cameraRate", "30").toDouble()
        val seconds = args.getString("cameraSeconds", "30").toLong()
        val error = AtomicReference<String?>(null)
        val capture = CameraPublisher(ctx) { error.set(it) }
        try {
            NativeRuntime.start(0, "/phone", args.getString("pcPeer", ""), args.getString("localAddress", ""), ctx.applicationInfo.nativeLibraryDir)
            NativeRuntime.createPublisher(cap.id, cap.defaultTopic, cap.kind)
            instrumentation.runOnMainSync { capture.start(cap, rate) }
            val ready = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < ready && (capture.stats()?.published ?: 0) < 5 && error.get() == null) SystemClock.sleep(100)
            assertNull(error.get())
            assertTrue("Camera did not start", (capture.stats()?.published ?: 0) >= 5)
            SystemClock.sleep(3000)
            val initial = requireNotNull(capture.stats())
            val start = SystemClock.elapsedRealtime()
            val deadline = start + seconds * 1000
            while (SystemClock.elapsedRealtime() < deadline && error.get() == null) {
                SystemClock.sleep(500)
                capture.stats()
            }
            val stats = requireNotNull(capture.stats())
            val elapsed = (SystemClock.elapsedRealtime() - start) / 1000.0
            val fps = (stats.published - initial.published) / elapsed
            android.util.Log.i("ROS2CameraBenchmark", "fps=$fps elapsed=$elapsed stats=$stats")
            assertNull(error.get())
            assertTrue("Insufficient published FPS: $fps; $stats", fps >= args.getString("minimumCameraFps", "15").toDouble())
            assertEquals("ROS write failures", 0L, stats.failures)
            instrumentation.runOnMainSync { capture.stop() }
            capture.awaitStopped()
            val stopped = NativeRuntime.publishedCount(cap.id)
            SystemClock.sleep(500)
            assertEquals("Camera still publishes after stop", stopped, NativeRuntime.publishedCount(cap.id))
        } finally {
            instrumentation.runOnMainSync { capture.stop() }
            capture.awaitStopped()
            NativeRuntime.stop()
        }
    }

}
