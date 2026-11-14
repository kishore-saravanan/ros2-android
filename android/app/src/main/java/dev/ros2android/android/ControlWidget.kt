package dev.ros2android.android

/** Explicit supported ROS schemas; never treat arbitrary text as executable code. */
data class ControlWidget(
    val id: String,
    val label: String,
    val type: String,
    val topic: String,
    val value: String = "true",
    val minimum: Double = 0.0,
    val maximum: Double = 1.0,
    val linear: Double = 0.3,
    val angular: Double = 0.8,
    val messageType: String = "std_msgs/msg/String",
) {
    val subscriber: Boolean
        get() = type == "Subscribed value"

    val image: Boolean
        get() = subscriber && kind == 8

    val kind: Int
        get() =
            when (type) {
                "Button · Bool",
                "Switch" -> 10
                "Button · String" -> 11
                "Slider" -> 13
                "Joystick" -> 12
                "Subscribed value" -> SUB_TYPES.getValue(messageType)
                else -> error("Unsupported control")
            }

    init {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "Invalid widget ID" }
        require(label.isNotBlank() && label.length <= 60) { "Label must contain 1–60 characters" }
        require(type in TYPES) { "Choose a supported widget" }
        require(!subscriber || messageType in SUB_TYPES) { "Choose a supported message type" }
        EndpointConfig(topic, 1.0)
        require(value.length <= 1024 && !value.contains('\u0000')) { "Button value is too long" }
        require(type != "Button · Bool" || value in listOf("true", "false")) {
            "Bool value must be true or false"
        }

        require(
            minimum.isFinite() &&
                maximum.isFinite() &&
                minimum < maximum &&
                (maximum - minimum).isFinite()
        ) {
            "Slider minimum must be below maximum"
        }

        require(
            linear.isFinite() && linear in 0.01..5.0 && angular.isFinite() && angular in 0.01..10.0
        ) {
            "Use linear limit 0.01–5 m/s and angular limit 0.01–10 rad/s"
        }
    }

    companion object {
        val PUBLISH_TYPES =
            listOf("Button · Bool", "Button · String", "Switch", "Slider", "Joystick")
        val TYPES = PUBLISH_TYPES + "Subscribed value"
        val SUB_TYPES =
            linkedMapOf(
                "std_msgs/msg/String" to 11,
                "std_msgs/msg/Bool" to 10,
                "std_msgs/msg/Int32" to 14,
                "std_msgs/msg/Int64" to 16,
                "std_msgs/msg/Float32" to 15,
                "std_msgs/msg/Float64" to 13,
                "sensor_msgs/msg/CompressedImage" to 8,
            )
    }
}
