package dev.ros2android.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import org.json.JSONObject

/**
 * Conditions recorded during long device runs: battery temperature/level and charging, Android
 * thermal status/headroom, screen and Doze idle state, and this app process's memory (PSS).
 * Observational only; none of it identifies the device or its user.
 */
object RunConditions {
    fun sample(context: Context, second: Int): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = context.getSystemService(PowerManager::class.java)
        val entry = JSONObject().put("s", second)
        battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
            ?.let { entry.put("batteryTempC", it / 10.0) }
        battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }?.let { entry.put("batteryLevel", it) }
        battery?.let { entry.put("charging", it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) }
        entry.put("screenOn", power.isInteractive).put("deviceIdle", power.isDeviceIdleMode).put("appPssKb", Debug.getPss())
        if (Build.VERSION.SDK_INT >= 29) entry.put("thermalStatus", power.currentThermalStatus)
        if (Build.VERSION.SDK_INT >= 30) power.getThermalHeadroom(10).takeIf { !it.isNaN() }?.let { entry.put("thermalHeadroom", it.toDouble()) }
        return entry
    }
}
