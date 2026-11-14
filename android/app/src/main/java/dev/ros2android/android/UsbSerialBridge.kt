package dev.ros2android.android

import android.hardware.usb.*
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object UsbPty {
    init {
        System.loadLibrary("ros2android")
    }

    external fun open(): Int

    external fun slave(fd: Int): String

    external fun read(fd: Int, buffer: ByteArray): Int

    external fun write(fd: Int, buffer: ByteArray, size: Int)

    external fun close(fd: Int)
}

/** Reference CP2102 only. Android USB permission grants bulk I/O; no /dev/tty access. */
internal class UsbSerialBridge(
    manager: UsbManager,
    val device: UsbDevice,
    private val failed: (String) -> Unit,
) : Closeable {
    private val port = Cp2102Port(manager, device)
    private var master = -1
    private val workers = mutableListOf<Thread>()
    @Volatile private var active = true
    @Volatile
    var receivedBytes = 0L
        private set

    @Volatile
    var sentBytes = 0L
        private set

    val slave: String

    init {
        try {
            master = UsbPty.open()
            slave = UsbPty.slave(master)
            worker("usb-to-agent") {
                val bytes = ByteArray(4096)

                while (active) {
                    val count = port.read(bytes)
                    if (count > 0) {
                        receivedBytes += count
                        UsbPty.write(master, bytes, count)
                    }
                    // A timed out bulk read returns -1 too; detach is handled by the service
                    // receiver.
                }
            }

            worker("agent-to-usb") {
                val bytes = ByteArray(4096)

                while (active) {
                    val count = UsbPty.read(master, bytes)
                    var offset = 0
                    while (active && offset < count) {
                        val sent = port.write(bytes, offset, count - offset)
                        sentBytes += sent
                        offset += sent
                    }
                }
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun worker(name: String, action: () -> Unit) {
        workers +=
            Thread {
                try {
                    action()
                } catch (error: Exception) {
                    if (active) failed(error.message ?: "USB serial failed")
                }
            }
                .apply {
                    this.name = name
                    isDaemon = true
                    start()
                }
    }

    override fun close() {
        active = false
        // Bounded transfers finish before descriptors are released or reused.
        workers.filter { it !== Thread.currentThread() }.forEach { it.join(1500) }
        port.close()

        if (master >= 0) {
            UsbPty.close(master)
            master = -1
        }
    }

    companion object {
        const val BAUD = 115200

        fun supported(device: UsbDevice) =
            device.vendorId == 0x10c4 && device.productId == 0xea60 && device.interfaceCount == 1
    }
}

/** CP2102 UART and modem controls shared by Agent and exclusive programming mode. */
internal class Cp2102Port(manager: UsbManager, device: UsbDevice) : Closeable {
    private val connection =
        manager.openDevice(device) ?: throw IOException("USB permission missing")
    private val serialInterface = device.getInterface(0)
    private val input: UsbEndpoint
    private val output: UsbEndpoint

    init {
        try {
            check(UsbSerialBridge.supported(device)) { "CP2102 required" }
            check(connection.claimInterface(serialInterface, true)) {
                "Cannot claim CP2102 interface"
            }

            control(0x00, 1)
            modem(false, false)
            val baud = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(115200).array()
            check(
                connection.controlTransfer(0x41, 0x1e, 0, serialInterface.id, baud, 4, 1000) == 4
            ) {
                "Cannot set baud rate"
            }

            control(0x03, 0x0800)
            val flow = ByteArray(16)
            check(
                connection.controlTransfer(
                    0x41,
                    0x13,
                    0,
                    serialInterface.id,
                    flow,
                    flow.size,
                    1000,
                ) == flow.size
            ) {
                "Cannot disable flow control"
            }

            val endpoints =
                (0 until serialInterface.endpointCount).map(serialInterface::getEndpoint).filter {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                }
            input = endpoints.single { it.direction == UsbConstants.USB_DIR_IN }
            output = endpoints.single { it.direction == UsbConstants.USB_DIR_OUT }
        } catch (error: Exception) {
            connection.close()
            throw error
        }
    }

    // A full 4096-byte UART stream takes ~356 ms at 115200 baud. Asking USB
    // for it with a 100 ms timeout can discard a partial transfer. Request one
    // endpoint packet so sustained flash traffic completes within the timeout.
    fun read(bytes: ByteArray): Int =
        connection.bulkTransfer(input, bytes, minOf(bytes.size, input.maxPacketSize), 100)

    fun write(bytes: ByteArray, offset: Int, length: Int): Int {
        val count = connection.bulkTransfer(output, bytes, offset, length, 1000)
        if (count <= 0) throw IOException("USB serial write failed; reconnect the board")
        return count
    }

    private fun control(request: Int, value: Int) {
        check(
            connection.controlTransfer(0x41, request, value, serialInterface.id, null, 0, 1000) == 0
        ) {
            "CP2102 control failed ($request)"
        }
    }

    fun modem(dtr: Boolean, rts: Boolean) =
        control(0x07, 0x0300 or (if (dtr) 1 else 0) or (if (rts) 2 else 0))

    fun bootloader() {
        modem(false, true)
        Thread.sleep(100)
        modem(true, false)
        Thread.sleep(50)
        modem(false, false)
        Thread.sleep(100)
    }

    fun reboot() {
        modem(false, true)
        Thread.sleep(100)
        modem(false, false)
    }

    override fun close() {
        connection.releaseInterface(serialInterface)
        connection.close()
    }
}
