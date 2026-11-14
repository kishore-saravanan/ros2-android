package dev.ros2android.android

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun nativeLibraryLoadsAndClockAdvances() {
        assertTrue(NativeRuntime.diagnostic(), NativeRuntime.available)
        assertTrue(NativeRuntime.diagnostic().contains("ROS 2 Jazzy"))
        val before = NativeRuntime.monotonicTimeNanos()
        SystemClock.sleep(10)
        assertTrue(NativeRuntime.monotonicTimeNanos() > before)
    }

    @Test
    fun realSensorsDeliverSamplesAndStopReleasesListeners() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = instrumentation.targetContext.getSystemService(SensorManager::class.java)
        val hasSensors =
            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ||
                manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val monitor = ImuMonitor(manager)

        try {
            instrumentation.runOnMainSync {
                monitor.start()
                monitor.start()
            }

            val deadline = SystemClock.elapsedRealtime() + 4000
            var count = 0L
            while (SystemClock.elapsedRealtime() < deadline && count == 0L) {
                SystemClock.sleep(100)
                instrumentation.runOnMainSync { count = monitor.samples }
            }

            instrumentation.runOnMainSync {
                if (hasSensors) {
                    assertTrue("No actual motion sensor events delivered", monitor.samples > 0)
                    assertTrue(monitor.active)
                } else {
                    assertFalse(monitor.active)
                }

                monitor.stop()
                assertFalse(monitor.active)
                assertEquals(0, monitor.registeredSensors)
                count = monitor.samples
            }

            SystemClock.sleep(200)
            instrumentation.runOnMainSync { assertEquals(count, monitor.samples) }
        } finally {
            instrumentation.runOnMainSync { monitor.stop() }
        }
    }
}
