package dev.robotbrain.android

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class YuvPlanesTest {
    @Test fun convertsPaddedPlanarPlanesWithoutReadingPadding() {
        val planes = listOf(
            YuvPlanes.Plane(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 99, 99, 5, 6, 7, 8)), 6, 1),
            YuvPlanes.Plane(ByteBuffer.wrap(byteArrayOf(11, 12)), 2, 1),
            YuvPlanes.Plane(ByteBuffer.wrap(byteArrayOf(21, 22)), 2, 1))
        val output = ByteArray(12)
        YuvPlanes.toNv21(4, 2, 0, 0, planes, output)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 21, 11, 22, 12), output)
    }
    @Test fun respectsInterleavedChromaPositionsAndFinalRowWithoutPadding() {
        val chroma = byteArrayOf(99, 21, 11, 22, 12)
        val planes = listOf(
            YuvPlanes.Plane(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)), 4, 1),
            YuvPlanes.Plane(ByteBuffer.wrap(chroma).apply { position(2) }, 4, 2),
            YuvPlanes.Plane(ByteBuffer.wrap(chroma).apply { position(1) }, 4, 2))
        val output = ByteArray(12)
        YuvPlanes.toNv21(4, 2, 0, 0, planes, output)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 21, 11, 22, 12), output)
    }
    @Test fun respectsCropAndMultirowChromaStrides() {
        val y = ByteArray(24) { it.toByte() }
        val u = byteArrayOf(31, 32, 99, 33, 34)
        val v = byteArrayOf(41, 42, 99, 43, 44)
        val output = ByteArray(12)
        YuvPlanes.toNv21(2, 4, 2, 0, listOf(
            YuvPlanes.Plane(ByteBuffer.wrap(y), 6, 1),
            YuvPlanes.Plane(ByteBuffer.wrap(u), 3, 1),
            YuvPlanes.Plane(ByteBuffer.wrap(v), 3, 1)), output)
        assertArrayEquals(byteArrayOf(2, 3, 8, 9, 14, 15, 20, 21, 42, 32, 44, 34), output)
    }
    @Test fun mapsCaptureTimeRatherThanEncodeCompletionTime() {
        val realtime = CameraClock(true, 1000)
        assertEquals(1100L, realtime.toEpochNanos(100, 500))
        val unknown = CameraClock(false, 1000)
        assertEquals(1500L, unknown.toEpochNanos(100, 500))
        assertEquals(1510L, unknown.toEpochNanos(110, 900))
    }
}
