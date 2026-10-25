package dev.ros2android.android

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

class SensorPublishers(private val manager: SensorManager) {
    private val listeners = mutableMapOf<String, SensorEventListener>()
    private val epochOffsetNanos get() = System.currentTimeMillis() * 1_000_000L - SystemClock.elapsedRealtimeNanos()

    fun start(capability: PhoneCapability, config: EndpointConfig) {
        check(capability.id !in listeners) { "Publisher already active" }
        val offset = epochOffsetNanos
        val periodNanos = (1_000_000_000.0 / config.rateHz).toLong()
        var nextDue = 0L
        var gyro: FloatArray? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (capability.kind == 0 && event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    gyro = event.values.copyOf(3)
                    return
                }
                if (event.timestamp < nextDue) return
                val values = if (capability.kind == 0) {
                    val angular = gyro ?: return
                    event.values.copyOf(3) + angular
                } else if (capability.kind == 7) {
                    val q = FloatArray(4)
                    SensorManager.getQuaternionFromVector(q, event.values)
                    floatArrayOf(q[1], q[2], q[3], q[0])
                } else event.values.copyOf()
                if (NativeRuntime.publishSample(capability.id, event.timestamp + offset, values)) {
                    nextDue = if (nextDue == 0L || event.timestamp - nextDue > periodNanos * 3)
                        event.timestamp + periodNanos else nextDue + periodNanos
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val types = if (capability.kind == 0) listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)
                    else listOf(requireNotNull(capability.sensorType))
        try {
            types.forEach { type ->
                val sensor = requireNotNull(manager.getDefaultSensor(type)) { "Sensor is unavailable" }
                check(manager.registerListener(listener, sensor, (1_000_000.0 / config.rateHz).toInt())) { "Sensor registration failed" }
            }
            listeners[capability.id] = listener
        } catch (e: Exception) {
            manager.unregisterListener(listener)
            throw e
        }
    }

    fun stop(id: String) { listeners.remove(id)?.let { manager.unregisterListener(it) } }
    fun stopAll() { listeners.keys.toList().forEach(::stop) }
}

