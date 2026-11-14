package dev.ros2android.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/** One frame decoding plus one latest pending frame; never queue behind ROS publishing. */
internal class LatestPreviewDecoder(private val deliver: (String, Long, Bitmap) -> Unit) :
    Closeable {
    private data class Frame(
        val id: String,
        val epoch: Long,
        val bytes: ByteArray,
        val capturedEpochNanos: Long,
    )

    private val pending = ArrayBlockingQueue<Frame>(1)
    private val epoch = AtomicLong()
    @Volatile private var closed = false
    @Volatile
    var enabled = false
        set(value) {
            if (field == value) return
            synchronized(this) {
                field = value
                epoch.incrementAndGet()
                pending.clear()
            }
        }

    private val thread =
        Thread(
                {
                    try {
                        while (!closed) {
                            val frame = pending.take()
                            if (!enabled || frame.epoch != epoch.get()) continue
                            // Optional UI work cannot block or fail the encoder/publisher workers.
                            val bitmap =
                                runCatching {
                                    BitmapFactory.decodeByteArray(
                                        frame.bytes,
                                        0,
                                        frame.bytes.size,
                                        BitmapFactory.Options().apply { inSampleSize = 2 },
                                    )
                                }
                                    .getOrNull() ?: continue
                            synchronized(this) {
                                if (enabled && !closed && frame.epoch == epoch.get()) {
                                    deliver(frame.id, frame.capturedEpochNanos, bitmap)
                                }
                            }
                        }
                    } catch (_: InterruptedException) {
                        /* Service teardown. */
                    }
                },
                "camera-preview-decode",
            )
            .apply {
                isDaemon = true
                start()
            }

    fun submit(id: String, bytes: ByteArray, capturedEpochNanos: Long) {
        if (!enabled || closed) return
        val frame = Frame(id, epoch.get(), bytes, capturedEpochNanos)
        pending.poll()
        pending.offer(frame)
    }

    override fun close() {
        closed = true
        enabled = false
        pending.clear()
        thread.interrupt()
        thread.join(1000)
    }
}
