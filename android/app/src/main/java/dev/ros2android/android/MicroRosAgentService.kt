package dev.ros2android.android

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.usb.*
import android.net.wifi.WifiManager
import android.os.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Owns one packaged Agent and optional USB bridge, independently from sensors. */
class MicroRosAgentService : Service() {
    private var usb: UsbSerialBridge? = null
    private var selectedDevice: UsbDevice? = null
    private var usbMode = false
    private val manager by lazy { getSystemService(UsbManager::class.java) }
    private val usbEvents =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Exported below API 33: ignore malformed extras instead of crashing.
                val device =
                    runCatching { intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) }
                        .getOrNull() ?: return
                if (device.deviceId != selectedDevice?.deviceId) return

                when (intent.action) {
                    USB_PERMISSION -> {
                        if (child != null || !running) return

                        if (manager.hasPermission(device)) {
                            launchAgent(device)
                        } else {
                            state = "Failed: USB permission denied"
                            stopSelf()
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        state = "Disconnected · reconnect USB and tap Start USB agent"
                        stopSelf()
                    }
                }
            }
        }

    @android.annotation.SuppressLint(
        "UnspecifiedRegisterReceiverFlag"
    ) // API 26–32 branch; package-scoped callback, verified with hasPermission.
    override fun onCreate() {
        super.onCreate()
        serviceActive = true
        instance = this
        val filter =
            IntentFilter(USB_PERMISSION).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbEvents, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbEvents, filter)
        }
    }

    private var child: java.lang.Process? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Started with startForegroundService(): enter the foreground before any early stop,
        // otherwise Android ends the whole app.
        val requestedUsb = if (running) usbMode else intent?.getBooleanExtra(USB, false) == true
        val channel = "micro_ros_agent"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    channel,
                    "Microcontroller connection",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        val open =
            PendingIntent.getActivity(
                this,
                20,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                21,
                Intent(this, MicroRosAgentService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_ros_notification)
                .setContentTitle("ROS2 Android · micro-ROS Agent")
                .setContentText(
                    if (requestedUsb) {
                        "ESP32 USB · ${UsbSerialBridge.BAUD} baud"
                    } else {
                        "ESP32 Wi-Fi · UDP 8888"
                    }
                )
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(3, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(3, notification)
        }

        if (Esp32ProgrammingService.active) {
            state = "Stop USB programming first"
            stopSelf()
            return START_NOT_STICKY
        }

        if (running) return START_NOT_STICKY
        usbMode = requestedUsb
        usbTransport = usbMode
        running = true

        if (usbMode) {
            val devices = manager.deviceList.values.filter(UsbSerialBridge::supported)
            if (devices.size != 1) {
                state =
                    if (devices.isEmpty()) {
                        "Failed: connect one CP2102 ESP32 using USB OTG"
                    } else {
                        "Failed: connect only one CP2102 board"
                    }
                stopSelf()
            } else {
                val device = devices.single()
                selectedDevice = device

                if (manager.hasPermission(device)) {
                    launchAgent(device)
                } else {
                    state = "Allow USB access on the phone"
                    val permission =
                        PendingIntent.getBroadcast(
                            this,
                            22,
                            Intent(USB_PERMISSION).setPackage(packageName),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        )
                    runCatching { manager.requestPermission(device, permission) }
                        .onFailure {
                            state = "Failed: ${it.message}"
                            stopSelf()
                        }
                }
            }
        } else {
            launchAgent(null)
        }

        return START_NOT_STICKY
    }

    private fun launchAgent(device: UsbDevice?) {
        runCatching {
            val libs = applicationInfo.nativeLibraryDir
            val executable = File(libs, "libmicro_ros_agent_exec.so")
            check(executable.isFile && executable.canExecute()) {
                "Agent binary is not bundled; run setup/build-micro-ros-agent.sh"
            }

            wake =
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ROS2Android:MicroRosAgent")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            multicast =
                applicationContext
                    .getSystemService(WifiManager::class.java)
                    .createMulticastLock("ROS2AndroidMicroRosAgent")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            val label = if (device != null) "USB · ${UsbSerialBridge.BAUD} baud" else "UDP 8888"
            val args =
                if (device != null) {
                    usb =
                        UsbSerialBridge(manager, device) { message ->
                            Handler(Looper.getMainLooper()).post {
                                state = "Failed: $message"
                                stopSelf()
                            }
                        }
                    listOf(
                        "serial",
                        "--dev",
                        usb!!.slave,
                        "-b",
                        UsbSerialBridge.BAUD.toString(),
                        "-v",
                        "4",
                    )
                } else {
                    listOf("udp4", "-p", "8888", "-v", "4")
                }
            val builder = ProcessBuilder(listOf(executable.path) + args).redirectErrorStream(true)
            builder.environment()["LD_LIBRARY_PATH"] = libs
            // UDP-only profiles avoid Linux shared-memory transports on Android.
            builder.environment()["FASTDDS_BUILTIN_TRANSPORTS"] = "UDPv4"
            // Keep localhost locators discoverable when the external Wi-Fi link disappears.
            val addresses = linkedSetOf("127.0.0.1")
            java.net.NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.forEach { network ->
                    network.inetAddresses
                        .toList()
                        .filterIsInstance<java.net.Inet4Address>()
                        .forEach { addresses.add(it.hostAddress!!) }
                }
            fun locator(address: String) =
                "<locator><udpv4><address>$address</address></udpv4></locator>"
            val locators = addresses.joinToString("") { locator(it) }
            // Explicit unicast locators suppress Fast DDS default multicast locators.
            // Likewise an explicit initial-peer list replaces its multicast default.
            // Keep localhost discovery AND advertise/listen on the standard subnet group.
            val discoveryMulticast =
                locator("239.255.0.1") // Port 0 selects the domain's well-known port.
            val profile = File(filesDir, "agent-dds-profile.xml")
            profile.writeText(
                """<profiles xmlns="http://www.eprosima.com/XMLSchemas/fastRTPS_Profiles">
                <transport_descriptors><transport_descriptor>
                <transport_id>robot_udp</transport_id><type>UDPv4</type><maxInitialPeersRange>60</maxInitialPeersRange>
                <interfaceWhiteList>${addresses.joinToString("") { "<address>$it</address>" }}</interfaceWhiteList>
                </transport_descriptor></transport_descriptors>
                <participant profile_name="robot_agent" is_default_profile="true"><rtps>
                <defaultUnicastLocatorList>$locators</defaultUnicastLocatorList>
                <builtin><metatrafficUnicastLocatorList>$locators</metatrafficUnicastLocatorList><metatrafficMulticastLocatorList>$discoveryMulticast</metatrafficMulticastLocatorList><initialPeersList>${locator("127.0.0.1")}$discoveryMulticast</initialPeersList></builtin>
                <userTransports><transport_id>robot_udp</transport_id></userTransports><useBuiltinTransports>false</useBuiltinTransports>
                </rtps></participant></profiles>"""
            )
            builder.environment()["FASTRTPS_DEFAULT_PROFILES_FILE"] = profile.absolutePath
            builder.environment()["FASTDDS_DEFAULT_PROFILES_FILE"] = profile.absolutePath
            builder.directory(filesDir)
            val process = builder.start()
            child = process
            running = true
            state = "Starting · $label"
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        reader.forEachLine { line ->
                            synchronized(lines) {
                                val clean =
                                    line
                                        .replace(Regex("\\u001B\\[[0-9;]*m"), "")
                                        .filter { it >= ' ' || it == '\t' }
                                        .take(1024)
                                lines.addLast(clean)
                                while (lines.size > 40) lines.removeFirst()

                                if (child === process && clean.contains("running...")) {
                                    state = "Running · $label"
                                }
                            }
                        }
                    }
                }

                val code = process.waitFor()

                if (child === process) {
                    running = false
                    state = "Agent exited ($code)"
                    Handler(Looper.getMainLooper()).post { stopSelf() }
                }
            }
                .apply {
                    name = "micro-ros-agent-output"
                    isDaemon = true
                    start()
                }
        }
            .onFailure {
                state = "Failed: ${it.message}"
                running = false
                stopSelf()
            }
    }

    override fun onDestroy() {
        unregisterReceiver(usbEvents)
        val process = child
        child = null
        running = false
        usb?.close()
        usb = null
        selectedDevice = null

        if (process != null) {
            Thread {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
                .start()
        }

        wake?.let { if (it.isHeld) it.release() }
        multicast?.let { if (it.isHeld) it.release() }

        if (
            !state.startsWith("Failed") &&
                !state.startsWith("Agent exited") &&
                !state.startsWith("Disconnected")
        ) {
            state = "Stopped"
        }

        instance = null
        serviceActive = false
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: MicroRosAgentService? = null

        fun usbDiagnostics(): String =
            instance?.usb?.let { "RX ${it.receivedBytes} bytes · TX ${it.sentBytes} bytes" }
                ?: "No USB bridge"

        const val USB = "usb_serial"
        private const val USB_PERMISSION = "dev.ros2android.android.USB_AGENT_PERMISSION"
        const val STOP = "dev.ros2android.android.STOP_MICRO_ROS_AGENT"
        @Volatile
        var serviceActive = false
            private set

        @Volatile
        var usbTransport = false
            private set

        @Volatile
        var running = false
            private set

        @Volatile
        var state = "Stopped"
            private set

        private val lines = java.util.ArrayDeque<String>()

        fun logs(): String = synchronized(lines) { lines.joinToString("\n") }
    }
}
