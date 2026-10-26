package dev.ros2android.android

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.usb.*
import android.os.*
import java.net.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** One authenticated, loopback-only raw UART tunnel, carried by authorized ADB. */
class Esp32ProgrammingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var device: UsbDevice? = null
    private var uart: Cp2102Port? = null
    private var server: ServerSocket? = null
    private var client: Socket? = null
    private var token = ""
    private var resumeAgent = false
    private var previousUsb = false
    private var successful = false
    private var wake: PowerManager.WakeLock? = null
    private val manager by lazy { getSystemService(UsbManager::class.java) }
    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val selected = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (selected.deviceId != device?.deviceId) return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) fail("USB disconnected")
            else if (intent.action == PERMISSION) {
                if (manager.hasPermission(selected)) openPort(selected) else fail("USB permission denied")
            }
        }
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(PERMISSION).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(events, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(events, filter)
    }
    override fun onBind(intent: Intent): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { successful = intent.getBooleanExtra("success", false); stopSelf(); return START_NOT_STICKY }
        if (active) return START_NOT_STICKY
        token = intent?.getStringExtra("token").orEmpty()
        if (!token.matches(Regex("[a-f0-9]{64}"))) { fail("Invalid session token"); return START_NOT_STICKY }
        active = true; state = "Preparing USB programming"; port = 0
        val channel = "esp32_programming"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, "ESP32 programming", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 31, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_ros_notification)
            .setContentTitle("ROS2 Android · ESP32 programming").setContentText("Exclusive USB access from authorized PC")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(4, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(4, notification)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ROS2Android:Esp32Programming").apply { acquire(15 * 60 * 1000L) }
        resumeAgent = MicroRosAgentService.running; previousUsb = MicroRosAgentService.usbTransport
        stopService(Intent(this, MicroRosAgentService::class.java))
        val deadline = SystemClock.elapsedRealtime() + 10000
        val wait = object : Runnable {
            override fun run() {
                if (closed.get()) return
                if (MicroRosAgentService.serviceActive) {
                    if (SystemClock.elapsedRealtime() > deadline) fail("Agent did not release USB") else main.postDelayed(this, 100)
                    return
                }
                val devices = manager.deviceList.values.filter(UsbSerialBridge::supported)
                if (devices.size != 1) { fail("Connect exactly one CP2102 ESP32 using USB OTG"); return }
                val selected = devices.single(); device = selected
                if (manager.hasPermission(selected)) openPort(selected)
                else {
                    state = "Allow USB access on the phone"
                    val request = PendingIntent.getBroadcast(this@Esp32ProgrammingService, 32, Intent(PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    runCatching { manager.requestPermission(selected, request) }.onFailure { fail(it.message ?: "USB permission failed") }
                }
            }
        }
        main.post(wait)
        main.postDelayed({ if (!closed.get()) fail("Programming session timed out") }, 15 * 60 * 1000L)
        return START_NOT_STICKY
    }
    private fun openPort(selected: UsbDevice) {
        if (uart != null || closed.get()) return
        runCatching {
            uart = Cp2102Port(manager, selected)
            server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
            port = server!!.localPort; state = "Ready for PC · USB programming"
            Thread { serve() }.apply { name = "esp32-programming"; isDaemon = true; start() }
        }.onFailure { fail(it.message ?: "Cannot open USB") }
    }
    private fun serve() {
        try {
            val deadline = SystemClock.elapsedRealtime() + 60000
            while (!closed.get()) {
                if (SystemClock.elapsedRealtime() > deadline) throw java.io.IOException("PC did not connect")
                val socket = try { server!!.accept() } catch (_: SocketTimeoutException) { continue }
                socket.soTimeout = 3000
                // A fixed-size token precedes UART bytes. Reject unauthenticated local clients.
                val supplied = ByteArray(64)
                try { java.io.DataInputStream(socket.getInputStream()).readFully(supplied) }
                catch (_: Exception) { socket.close(); continue }
                if (!MessageDigest.isEqual(token.toByteArray(Charsets.US_ASCII), supplied)) { socket.close(); continue }
                client = socket; server?.close(); socket.soTimeout = 60000; socket.tcpNoDelay = true
                val serial = uart ?: error("USB closed")
                serial.bootloader(); state = "Programming · USB 115200 baud"
                val usbReader = Thread {
                    try {
                        val bytes = ByteArray(4096)
                        while (!closed.get() && !socket.isClosed) {
                            val count = serial.read(bytes)
                            if (count > 0) socket.getOutputStream().write(bytes, 0, count)
                        }
                    } catch (_: Exception) { runCatching { socket.close() } }
                }.apply { isDaemon = true; name = "esp32-programming-usb-read"; start() }
                try {
                    val bytes = ByteArray(4096)
                    while (!closed.get()) {
                        val count = socket.getInputStream().read(bytes)
                        if (count < 0) break
                        var offset = 0
                        while (offset < count && !closed.get()) offset += serial.write(bytes, offset, count - offset)
                    }
                } finally { socket.close(); usbReader.join(1500) }
                if (!closed.get()) { state = "PC disconnected · awaiting completion"; main.postDelayed({ if (!closed.get()) fail("PC did not finish session") }, 10000) }
                return
            }
        } catch (error: Exception) { if (!closed.get()) fail(error.message ?: "Programming connection failed") }
    }
    private fun fail(message: String) { state = "Failed: $message"; main.post { stopSelf() } }
    override fun onDestroy() {
        closed.set(true); main.removeCallbacksAndMessages(null); unregisterReceiver(events)
        wake?.let { if (it.isHeld) it.release() }
        runCatching { client?.close() }; runCatching { server?.close() }
        val serial = uart; uart = null; port = 0
        Thread {
            runCatching { serial?.reboot() }; runCatching { serial?.close() }
            main.post {
                active = false
                if (!state.startsWith("Failed")) state = "Stopped"
                if (successful && resumeAgent) startForegroundService(Intent(this, MicroRosAgentService::class.java).putExtra(MicroRosAgentService.USB, previousUsb))
            }
        }.start()
        super.onDestroy()
    }
    companion object {
        const val STOP = "dev.ros2android.android.STOP_ESP32_PROGRAMMING"
        private const val PERMISSION = "dev.ros2android.android.USB_PROGRAMMING_PERMISSION"
        @Volatile var active = false; private set
        @Volatile var state = "Stopped"; private set
        @Volatile var port = 0; private set
    }
}
