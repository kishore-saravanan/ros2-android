package dev.ros2android.android

import org.junit.Assert.*
import org.junit.Test

class ControlWidgetTest {
    private fun widget(type: String = "Joystick", topic: String = "/test/control", linear: Double = 0.3, minimum: Double = 0.0, maximum: Double = 1.0) = ControlWidget("test", "Drive", type, topic, linear = linear, minimum = minimum, maximum = maximum)
    @Test fun schemaMappingIsExplicit() {
        assertEquals(12, widget().kind)
        assertEquals(10, widget("Switch").kind)
        assertEquals(11, widget("Button · String").kind)
        assertEquals(13, widget("Slider").kind)
    }
    @Test fun subscriberSchemasAreExplicitAndInt64StaysDistinct() {
        assertEquals(7, ControlWidget.SUB_TYPES.size)
        val value = ControlWidget("reading", "Counter", "Subscribed value", "/test/counter", messageType = "std_msgs/msg/Int64")
        assertTrue(value.copy(messageType = "sensor_msgs/msg/CompressedImage").image)
        assertEquals(8, value.copy(messageType = "sensor_msgs/msg/CompressedImage").kind)
        assertTrue(value.subscriber); assertEquals(16, value.kind)
        try { value.copy(messageType = "custom_msgs/msg/Anything"); fail("Unsupported schema accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun invalidTopicsAndUnsafeLimitsAreRejected() {
        listOf<() -> Unit>(
            { widget(topic = "/9bad") }, { widget(linear = Double.NaN) },
            { widget(linear = 100.0) }, { widget(minimum = 2.0, maximum = 1.0) },
            { widget(minimum = -Double.MAX_VALUE, maximum = Double.MAX_VALUE) },
            { widget(type = "Arbitrary code") },
            { ControlWidget("test", "Button", "Button · Bool", "/test/button", "yes") }
        ).forEach { bad -> try { bad(); fail("Invalid configuration accepted") } catch (_: IllegalArgumentException) {} }
    }
}
