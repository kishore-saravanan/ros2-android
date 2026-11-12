package dev.ros2android.android

/** Serialized by the service worker. Owns accepted ROS startup identity, not UI/hardware.
 * Native calls are supplied at the boundary so profile/retry rules are testable without JNI.
 */
internal class SessionRuntime<Profile : Any>(
    private val startNative: (Profile) -> Unit,
    private val stopNative: () -> Unit
) {
    @Volatile private var accepted: Profile? = null
    val connected: Boolean get() = accepted != null

    fun ensure(profile: Profile) {
        val current = accepted
        if (current != null) {
            check(current == profile) { "Stop the sensor and Dashboard session before changing its connection profile" }
            return
        }
        startNative(profile)
        accepted = profile // Failed startup must not look connected or prevent an explicit retry.
    }

    fun stop() {
        try { stopNative() }
        finally { accepted = null } // The service still reports any cleanup failure explicitly.
    }
}
