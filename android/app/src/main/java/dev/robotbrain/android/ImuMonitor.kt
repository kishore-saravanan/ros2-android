package dev.robotbrain.android

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

data class ImuReading(val x: Float, val y: Float, val z: Float, val timestampNanos: Long)

class ImuMonitor(private val manager: SensorManager) : SensorEventListener {
    var active = false
        private set
    var accelerometer: ImuReading? = null
        private set
    var gyroscope: ImuReading? = null
        private set
    var samples = 0L
        private set
    var registeredSensors = 0
        private set

    fun start() {
        if (active) return
        accelerometer = null
        gyroscope = null
        samples = 0
        registeredSensors = listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE).count { type ->
            manager.getDefaultSensor(type)?.let { manager.registerListener(this, it, 20_000) } ?: false
        }
        active = registeredSensors > 0
    }

    fun stop() {
        manager.unregisterListener(this)
        active = false
        registeredSensors = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return
        val reading = ImuReading(event.values[0], event.values[1], event.values[2], event.timestamp)
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accelerometer = reading
            Sensor.TYPE_GYROSCOPE -> gyroscope = reading
        }
        samples++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

