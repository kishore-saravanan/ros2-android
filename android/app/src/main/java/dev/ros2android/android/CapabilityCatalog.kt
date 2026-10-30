package dev.ros2android.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

object CapabilityCatalog {
    fun publishers(context: Context): List<PhoneCapability> {
        val manager = context.getSystemService(SensorManager::class.java)
        fun sensor(id: String, label: String, type: Int, kind: Int, message: String, rate: Double, detail: String) =
            PhoneCapability(id, label, kind, message, "/phone/$id", rate, detail,
                sensorType = type, available = manager.getDefaultSensor(type) != null)
        val items = mutableListOf(
            PhoneCapability("battery", "Battery percentage", 9, "std_msgs/msg/Float64", "/phone/battery_percent", 1.0,
                "Battery charge · 0–100 percent · no permission required · up to 1 Hz"),
            PhoneCapability("imu", "IMU · accelerometer + gyroscope", 0, "sensor_msgs/msg/Imu", "/phone/imu", 50.0,
                "Acceleration includes gravity; orientation is marked unavailable. Android device axes.",
                available = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null && manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null),
            sensor("accelerometer", "Accelerometer", Sensor.TYPE_ACCELEROMETER, 1, "geometry_msgs/msg/Vector3Stamped", 50.0, "m/s² · includes gravity"),
            sensor("gyroscope", "Gyroscope", Sensor.TYPE_GYROSCOPE, 2, "geometry_msgs/msg/Vector3Stamped", 50.0, "rad/s"),
            sensor("magnetometer", "Magnetometer", Sensor.TYPE_MAGNETIC_FIELD, 3, "sensor_msgs/msg/MagneticField", 20.0, "Published in tesla"),
            sensor("light", "Ambient light", Sensor.TYPE_LIGHT, 4, "sensor_msgs/msg/Illuminance", 5.0, "lux · changes reported by hardware"),
            sensor("proximity", "Proximity", Sensor.TYPE_PROXIMITY, 5, "std_msgs/msg/Float64", 5.0, "centimetres · often a near/far measurement"),
            sensor("pressure", "Barometer", Sensor.TYPE_PRESSURE, 6, "sensor_msgs/msg/FluidPressure", 10.0, "Published in pascals"),
            sensor("orientation", "Rotation vector · orientation", Sensor.TYPE_ROTATION_VECTOR, 7, "sensor_msgs/msg/Imu", 30.0, "Android fused orientation; acceleration and angular velocity marked unavailable")
        )
        val cameras = context.getSystemService(CameraManager::class.java)
        runCatching {
            cameras.cameraIdList.forEach { id ->
                val direction = when (cameras.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    else -> "External"
                }
                items += PhoneCapability("camera_$id", "$direction camera · $id", 8,
                    "sensor_msgs/msg/CompressedImage", "/phone/camera_${direction.lowercase()}_$id/image/compressed", 0.0,
                    "Continuous camera · JPEG · supported resolutions and FPS", cameraId = id)
            }
        }
        return items
    }
}

