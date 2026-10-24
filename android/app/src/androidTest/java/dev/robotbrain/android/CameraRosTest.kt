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
                NativeRuntime.removePublisher(cap.id)
            }
        } finally {
            instrumentation.runOnMainSync { capture.stop() }
            if (NativeRuntime.available) NativeRuntime.stop()
        }
    }
}
