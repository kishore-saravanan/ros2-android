package dev.ros2android.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.os.Build
import java.net.NetworkInterface

object DeviceDiagnostics {
    fun phone(): String = "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\nABI: ${Build.SUPPORTED_ABIS.joinToString()}"

    fun power(context: Context): String {
        val manager = context.getSystemService(android.os.PowerManager::class.java)
        return "Power saving: ${if (manager.isPowerSaveMode) "on" else "off"} · Device idle: ${if (manager.isDeviceIdleMode) "yes" else "no"}"
    }

    fun sensors(context: Context): String {
        val manager = context.getSystemService(SensorManager::class.java)
        val names = listOf(
            "Accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "Gyroscope" to Sensor.TYPE_GYROSCOPE,
            "Magnetometer" to Sensor.TYPE_MAGNETIC_FIELD,
            "Light" to Sensor.TYPE_LIGHT,
            "Proximity" to Sensor.TYPE_PROXIMITY,
            "Pressure" to Sensor.TYPE_PRESSURE
        ).map { (label, type) -> "$label: ${if (manager.getDefaultSensor(type) != null) "available" else "not present"}" }
        val cameras = runCatching { context.getSystemService(CameraManager::class.java).cameraIdList.size.toString() }
            .getOrDefault("unavailable")
        return (names + "Cameras reported: $cameras").joinToString("\n")
    }

    fun networks(ipv4Only: Boolean = false): String = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface -> iface.inetAddresses.toList().filterNot { it.isLoopbackAddress || it.isLinkLocalAddress || (ipv4Only && it !is java.net.Inet4Address) }
                .map { "${iface.name}: ${it.hostAddress}" } }
            .joinToString("\n").ifBlank { "No active IP interface found." }
    }.getOrElse { "Network inspection unavailable: ${it.javaClass.simpleName}" }

    fun usb(context: Context): String {
        val manager = context.getSystemService(UsbManager::class.java)
        val devices = manager.deviceList.values
        return if (devices.isEmpty()) "No USB peripherals attached in phone host mode.\nThe PC debugging cable is a different USB role."
        else devices.joinToString("\n") {
            "USB %04x:%04x · permission %s".format(it.vendorId, it.productId, if (manager.hasPermission(it)) "granted" else "required")
        }
    }
}

