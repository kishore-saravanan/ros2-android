package dev.ros2android.android

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit diagnostic of this project's reference MCU UART, not phone data. */
@RunWith(AndroidJUnit4::class)
class Esp32BootDiagnosticTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun captureReferenceMcuBoot() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("usbBootDiagnostic") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        activity.scenario.onActivity {
            it.stopService(Intent(it, MicroRosAgentService::class.java))
        }

        val end = SystemClock.elapsedRealtime() + 10000
        while (MicroRosAgentService.serviceActive && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(100)
        }

        assertFalse(MicroRosAgentService.serviceActive)
        val manager = context.getSystemService(UsbManager::class.java)
        val device = manager.deviceList.values.single(UsbSerialBridge::supported)
        assertTrue(manager.hasPermission(device))
        val data = java.io.ByteArrayOutputStream()
        Cp2102Port(manager, device).use { serial ->
            serial.reboot()
            val deadline = SystemClock.elapsedRealtime() + 8000
            val bytes = ByteArray(4096)

            while (SystemClock.elapsedRealtime() < deadline) {
                val size = serial.read(bytes)
                if (size > 0) data.write(bytes, 0, size)
            }
        }

        java.io.File(context.cacheDir, "esp32-reference-boot.bin").writeBytes(data.toByteArray())
    }
}
