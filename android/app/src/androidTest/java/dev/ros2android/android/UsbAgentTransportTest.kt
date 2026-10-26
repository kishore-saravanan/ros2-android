package dev.ros2android.android

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UsbAgentTransportTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun binaryFramesSurviveBothPtyDirections() {
        val master = UsbPty.open()
        try {
            val slave = Os.open(UsbPty.slave(master), OsConstants.O_RDWR or OsConstants.O_NOCTTY, 0)
            try {
                // Include terminal control, zero and high-bit bytes. Cooked mode corrupts these.
                val frame = byteArrayOf(0x7e, 0, 0x11, 0x13, 0x0a, 0x0d, 0x7d, 0xff.toByte())
                val bytes = ByteArray(4096)
                Os.write(slave, frame, 0, frame.size)
                assertEquals(frame.size, UsbPty.read(master, bytes))
                assertArrayEquals(frame, bytes.copyOf(frame.size))
                UsbPty.write(master, frame, frame.size)
                val poll = android.system.StructPollfd().apply { fd = slave; events = OsConstants.POLLIN.toShort() }
                assertEquals(1, Os.poll(arrayOf(poll), 1000))
                assertEquals(frame.size, Os.read(slave, bytes, 0, bytes.size))
                assertArrayEquals(frame, bytes.copyOf(frame.size))
            } finally { Os.close(slave) }
        } finally { UsbPty.close(master) }
    }
    @Test fun packagedSerialAgentCanOpenAppPty() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val master = UsbPty.open()
        try {
            val builder = ProcessBuilder(File(context.applicationInfo.nativeLibraryDir, "libmicro_ros_agent_exec.so").path,
                "serial", "--dev", UsbPty.slave(master), "-b", "115200", "-v", "4").redirectErrorStream(true)
            builder.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            builder.environment()["FASTDDS_BUILTIN_TRANSPORTS"] = "UDPv4"
            val process = builder.start()
            val log = StringBuffer()
            val reader = Thread { runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { log.append(it).append('\n') } } } }.apply { start() }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (process.isAlive && !log.contains("running...") && System.nanoTime() < deadline) Thread.sleep(100)
                assertTrue(log.toString(), process.isAlive && log.contains("running..."))
            } finally { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); reader.join(2000) }
        } finally { UsbPty.close(master) }
    }
}
