package dev.ros2android.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue

/** One in-flight and one latest pending frame per image widget. No disk writes. */
internal class DashboardImageDecoder(private val maxDimension: Int = 1280, private val deliver: (Long, Bitmap?) -> Unit) : Closeable {
    private data class Frame(val bytes: ByteArray, val receivedAt: Long)
    private val pending = ArrayBlockingQueue<Frame>(1)
    @Volatile private var closed = false
    private val thread = Thread({
        try {
            while (!closed) {
                val frame = pending.take()
                val bitmap = runCatching { decode(frame.bytes, maxDimension) }.getOrNull()
                if (!closed) deliver(frame.receivedAt, bitmap)
            }
        } catch (_: InterruptedException) { /* Dashboard stopped. */ }
    }, "dashboard-image-decode").apply { isDaemon = true; start() }
    fun submit(bytes: ByteArray, receivedAt: Long) {
        if (closed) return
        pending.poll(); pending.offer(Frame(bytes, receivedAt))
    }
    override fun close() { closed = true; pending.clear(); thread.interrupt() }
    companion object {
        fun decode(bytes: ByteArray, maxDimension: Int = 1280): Bitmap? {
            if (bytes.isEmpty() || bytes.size > 2 * 1024 * 1024) return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outMimeType !in listOf("image/jpeg", "image/png") || options.outWidth !in 1..8192 ||
                options.outHeight !in 1..8192 || options.outWidth.toLong() * options.outHeight > 16_777_216) return null
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            val pixelBudget = Runtime.getRuntime().maxMemory() / 64 // Account for old/new frames across four widgets.
            while (options.outWidth / options.inSampleSize > maxDimension || options.outHeight / options.inSampleSize > maxDimension ||
                (options.outWidth / options.inSampleSize).toLong() * (options.outHeight / options.inSampleSize) > pixelBudget) options.inSampleSize *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }
}
