package dev.ros2android.android

import java.nio.ByteBuffer

/** Copies only valid pixels: camera planes may overlap and have row padding. */
internal object YuvPlanes {
    data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

    fun toNv21(
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        planes: List<Plane>,
        output: ByteArray,
    ) {
        require(width % 2 == 0 && height % 2 == 0 && left % 2 == 0 && top % 2 == 0)
        require(planes.size == 3 && output.size >= width * height * 3 / 2)
        planes.forEachIndexed { index, plane ->
            val buffer = plane.buffer.duplicate()
            val chroma = index != 0
            val w = if (chroma) width / 2 else width
            val h = if (chroma) height / 2 else height
            val x = if (chroma) left / 2 else left
            val y = if (chroma) top / 2 else top
            val base = buffer.position() + y * plane.rowStride + x * plane.pixelStride

            for (row in 0 until h) {
                val source = base + row * plane.rowStride

                if (!chroma && plane.pixelStride == 1) {
                    buffer.position(source)
                    buffer.get(output, row * width, w)
                } else {
                    // NV21 is Y followed by interleaved V,U; do not assume I420 or NV12.
                    val destination =
                        if (chroma) {
                            width * height + row * width + (if (index == 2) 0 else 1)
                        } else {
                            row * width
                        }
                    val step = if (chroma) 2 else 1
                    for (column in 0 until w) output[destination + column * step] =
                        buffer.get(source + column * plane.pixelStride)
                }
            }
        }
    }
}

/** Unknown camera clocks are anchored at first arrival; REALTIME uses elapsedRealtimeNanos. */
internal class CameraClock(private val realtime: Boolean, private val wallMinusElapsedNanos: Long) {
    private var unknownOffset: Long? = null

    fun toEpochNanos(sensorNanos: Long, arrivalElapsedNanos: Long): Long {
        if (!realtime && unknownOffset == null) unknownOffset = arrivalElapsedNanos - sensorNanos
        return sensorNanos + (unknownOffset ?: 0L) + wallMinusElapsedNanos
    }
}
