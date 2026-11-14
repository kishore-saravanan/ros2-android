package dev.ros2android.android

import org.junit.Assert.*
import org.junit.Test

class CameraOptionsTest {
    @Test
    fun cameraAcceptsAutoAndHighRateWithoutChangingSensorValidation() {
        assertEquals(0.0, EndpointConfig("/camera", 0.0, CameraOptions()).rateHz, 0.0)
        assertEquals(
            240.0,
            EndpointConfig("/camera", 240.0, CameraOptions(1920, 1080, 90)).rateHz,
            0.0,
        )
        assertThrows(IllegalArgumentException::class.java) { EndpointConfig("/imu", 240.0) }

        for (rate in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, .1)) {
            assertThrows(IllegalArgumentException::class.java) {
                EndpointConfig("/camera", rate, CameraOptions())
            }
        }
    }

    @Test
    fun selectsSupportedRangeRatherThanHardcodedThirty() {
        val ranges = listOf(15 to 30, 30 to 30, 30 to 60, 60 to 60)
        assertEquals(60 to 60, CameraRatePolicy.select(ranges, 0.0))
        assertEquals(60 to 60, CameraRatePolicy.select(ranges, 60.0))
        assertEquals(30 to 30, CameraRatePolicy.select(ranges, 30.0))
        assertEquals(60 to 60, CameraRatePolicy.select(ranges, 240.0))
        assertEquals(15 to 30, CameraRatePolicy.select(listOf(15 to 30), 60.0))
        assertThrows(IllegalArgumentException::class.java) {
            CameraRatePolicy.select(emptyList(), 60.0)
        }
    }

    @Test
    fun resolutionAndQualityAreValidated() {
        assertEquals(100, CameraOptions(3840, 2160, 100).jpegQuality)

        for (make in
            listOf<() -> Unit>(
                { CameraOptions(1, 1080) },
                { CameraOptions(1920, 0) },
                { CameraOptions(jpegQuality = 101) },
                { CameraOptions(jpegQuality = -1) },
            )) {
            assertThrows(IllegalArgumentException::class.java) { make() }
        }
    }
}
