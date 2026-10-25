package dev.ros2android.android

import android.hardware.SensorManager
import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RosRuntimeTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun nativeSessionCreatesEndpointsAndRestarts() {
        assertTrue(NativeRuntime.diagnostic(), NativeRuntime.available)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            repeat(2) {
                NativeRuntime.start(88, "/phone", "", "", ctx.applicationInfo.nativeLibraryDir)
                NativeRuntime.createPublisher("probe", "/phone/probe", 1)
                val graphDeadline = SystemClock.elapsedRealtime() + 3000
                var discovered = NativeRuntime.topics()
                val expectedTopic = "/phone/probe|geometry_msgs/msg/Vector3Stamped"
                while (expectedTopic !in discovered && SystemClock.elapsedRealtime() < graphDeadline) {
                    SystemClock.sleep(50)
                    discovered = NativeRuntime.topics()
                }
                assertTrue("Created topic missing from native ROS graph: ${discovered.joinToString()}", expectedTopic in discovered)
                assertTrue(NativeRuntime.publishSample("probe", System.currentTimeMillis() * 1_000_000L, floatArrayOf(1f, 2f, 3f)))
                assertEquals(1L, NativeRuntime.publishedCount("probe"))
                NativeRuntime.subscribeDisplay("/phone/probe_text")
                NativeRuntime.unsubscribeDisplay()
                NativeRuntime.removePublisher("probe")
                NativeRuntime.stop()
            }
        } finally { if (NativeRuntime.available) NativeRuntime.stop() }
    }

    @Test fun actualImuReachesPcAndDisplayMessageReturns() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Run with pcPeer and expectedText to enable the physical PC round-trip test", args.containsKey("expectedText"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val imu = CapabilityCatalog.publishers(ctx).first { it.id == "imu" }
        assertTrue("This test needs a real accelerometer and gyroscope", imu.available)
        val sensors = SensorPublishers(ctx.getSystemService(SensorManager::class.java))
        try {
            NativeRuntime.start(0, "/phone", args.getString("pcPeer", ""), args.getString("localAddress", ""), ctx.applicationInfo.nativeLibraryDir)
            NativeRuntime.createPublisher("imu", "/phone/imu", 0)
            NativeRuntime.subscribeDisplay("/phone/display/text")
            instrumentation.runOnMainSync { sensors.start(imu, EndpointConfig("/phone/imu", 50.0)) }
            val deadline = SystemClock.elapsedRealtime() + 25_000
            var received: String? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                val message = NativeRuntime.pollDisplay()
                if (message == args.getString("expectedText")) { received = message; break }
                SystemClock.sleep(100)
            }
            assertEquals("No expected PC-to-phone ROS message arrived; ${NativeRuntime.status()}", args.getString("expectedText"), received)
            assertTrue("No actual IMU data published", NativeRuntime.publishedCount("imu") > 0)
        } finally {
            instrumentation.runOnMainSync { sensors.stopAll() }
            NativeRuntime.stop()
        }
    }
}
