package dev.robotbrain.android

import org.junit.Assert.*
import org.junit.Test

class EndpointConfigTest {
    @Test fun acceptsCustomAbsoluteTopics() {
        assertEquals("/robot/camera_front/image", EndpointConfig("/robot/camera_front/image", 1.0).topic)
    }
    @Test fun rejectsInvalidTopics() {
        listOf("phone/imu", "/phone/", "/phone//imu", "/1phone", "/phone debug", "").forEach {
            assertThrows(IllegalArgumentException::class.java) { EndpointConfig(it, 10.0) }
        }
    }
    @Test fun rejectsInvalidRates() {
        listOf(0.0, -1.0, 101.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { EndpointConfig("/phone/imu", it) }
        }
    }
}
