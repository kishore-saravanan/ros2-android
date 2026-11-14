package dev.ros2android.android

data class EndpointConfig(
    val topic: String,
    val rateHz: Double,
    val camera: CameraOptions? = null,
) {
    init {
        require(topic.matches(Regex("/(?:[a-zA-Z_][a-zA-Z0-9_]*)(?:/[a-zA-Z_][a-zA-Z0-9_]*)*"))) {
            "Use an absolute ROS topic such as /phone/imu; no spaces, trailing slash, or " +
                "numeric-leading names."
        }

        if (camera != null) {
            require(rateHz.isFinite() && (rateHz == 0.0 || rateHz >= 0.2)) {
                "Use 0 for automatic camera FPS, or at least 0.2 fps"
            }
        } else {
            require(rateHz.isFinite() && rateHz in 0.2..100.0) {
                "Rate must be between 0.2 and 100 Hz."
            }
        }
    }
}

data class PhoneCapability(
    val id: String,
    val label: String,
    val kind: Int,
    val messageType: String,
    val defaultTopic: String,
    val defaultRate: Double,
    val detail: String,
    val sensorType: Int? = null,
    val cameraId: String? = null,
    val available: Boolean = true,
)
