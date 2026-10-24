package dev.robotbrain.android

object NativeRuntime {
    private val loadResult = runCatching { System.loadLibrary("robotbrain") }

    val available: Boolean get() = loadResult.isSuccess
    fun diagnostic(): String = if (available) describe() else
        "Native library unavailable: ${loadResult.exceptionOrNull()?.message}"

    external fun describe(): String
    external fun monotonicTimeNanos(): Long
    external fun start(domain: Int, namespace: String, peer: String, localAddress: String, libraryDir: String)
    external fun stop()
    external fun createPublisher(id: String, topic: String, kind: Int)
    external fun removePublisher(id: String)
    external fun publishSample(id: String, timestampNanos: Long, values: FloatArray): Boolean
    external fun publishImage(id: String, timestampNanos: Long, jpeg: ByteArray): Boolean
    external fun subscribeDisplay(topic: String)
    external fun unsubscribeDisplay()
    external fun pollDisplay(): String?
    external fun publishedCount(id: String): Long
    external fun status(): String
    external fun topics(): Array<String>
}

