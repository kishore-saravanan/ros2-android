package dev.ros2android.runner

/** Bounded, process-local output from this runner only; no device log access. */
object ProjectLog {
    private val lines = java.util.ArrayDeque<String>()
    @Synchronized fun append(value: String) {
        lines.addLast(value.take(2048))
        while (lines.size > 60) lines.removeFirst()
    }
    @Synchronized fun snapshot(): String = lines.joinToString("\n")
}
