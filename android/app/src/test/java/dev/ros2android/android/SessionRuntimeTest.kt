package dev.ros2android.android

import org.junit.Assert.*
import org.junit.Test

class SessionRuntimeTest {
    private data class Profile(val config: SessionConfig, val peer: String = "", val local: String = "")
    private val original = Profile(SessionConfig("/phone", 0))

    @Test fun identicalRequestsShareOneNativeStartup() {
        val started = mutableListOf<Profile>()
        val runtime = SessionRuntime<Profile>({ started += it }, {})
        assertFalse(runtime.connected)
        runtime.ensure(original)
        runtime.ensure(original.copy())
        assertTrue(runtime.connected)
        assertEquals(listOf(original), started)
    }

    @Test fun profileMismatchCannotSilentlyReuseAnotherDomainOrAddress() {
        val started = mutableListOf<Profile>()
        var stops = 0
        val runtime = SessionRuntime<Profile>({ started += it }, { stops++ })
        runtime.ensure(original)
        for (different in listOf(
            original.copy(config = SessionConfig("/phone", 87)),
            original.copy(config = SessionConfig("/robot", 0)),
            original.copy(peer = "192.0.2.1"), original.copy(local = "192.0.2.2"))) {
            val error = assertThrows(IllegalStateException::class.java) { runtime.ensure(different) }
            assertTrue(error.message!!.contains("Stop the sensor and Dashboard session"))
            assertTrue(runtime.connected)
        }
        assertEquals(listOf(original), started)
        assertEquals(0, stops)
    }

    @Test fun failedStartupIsDisconnectedAndCanBeRetriedExplicitly() {
        var attempts = 0
        val runtime = SessionRuntime<Profile>({ if (++attempts == 1) error("native start failed") }, {})
        assertThrows(IllegalStateException::class.java) { runtime.ensure(original) }
        assertFalse(runtime.connected)
        runtime.ensure(original)
        assertTrue(runtime.connected)
        assertEquals(2, attempts)
    }

    @Test fun completedStopAllowsDifferentProfile() {
        val started = mutableListOf<Profile>()
        var stops = 0
        val runtime = SessionRuntime<Profile>({ started += it }, { stops++ })
        runtime.ensure(original)
        runtime.stop()
        assertFalse(runtime.connected)
        val replacement = original.copy(config = SessionConfig("/robot", 87))
        runtime.ensure(replacement)
        assertEquals(listOf(original, replacement), started)
        assertEquals(1, stops)
    }

    @Test fun cleanupFailureIsPropagatedWithoutReportingAnActiveStartup() {
        val runtime = SessionRuntime<Profile>({}, { error("native stop failed") })
        runtime.ensure(original)
        assertEquals("native stop failed", assertThrows(IllegalStateException::class.java) { runtime.stop() }.message)
        assertFalse(runtime.connected)
    }
}
