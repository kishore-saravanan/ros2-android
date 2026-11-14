package dev.ros2android.android

import dev.ros2android.protocol.ProjectProtocol as Protocol
import org.junit.Assert.*
import org.junit.Test

class ProjectProtocolTest {
    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Incompatible envelope accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun legacyV1RemainsCompatible() {
        assertEquals(setOf(1, 2, 3, 4), Protocol.negotiate(false, null, null))
        for (command in Protocol.commands) Protocol.requireRequest(false, null, command)
    }

    @Test
    fun strictVersionCheckRunsBeforeCommandSideEffects() {
        for (version in listOf(null, true, "1", 1L, 0, 2)) {
            for (command in Protocol.commands) {
                var invoked = false
                rejects {
                    Protocol.requireRequest(true, version, command)
                    invoked = true
                }

                assertFalse(invoked)
            }

            rejects { Protocol.negotiate(true, version, intArrayOf(1, 2, 3, 4)) }
        }
    }

    @Test
    fun knownRequestsOnly() {
        for (command in Protocol.commands) Protocol.requireRequest(true, 1, command)
        for (command in listOf(-1, 0, 5)) rejects { Protocol.requireRequest(true, 1, command) }
    }

    @Test
    fun negotiateSubsetAndIgnoreAdditiveCommands() {
        assertEquals(setOf(1, 3), Protocol.negotiate(true, 1, intArrayOf(1, 3, 99)))
        assertEquals(setOf(1), Protocol.negotiate(true, 1, intArrayOf(1)))
        assertEquals(Protocol.commands, Protocol.negotiate(true, 1, intArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun malformedOrPartialCapabilitiesFailClosed() {
        for (commands in
            listOf(
                null,
                "1,2,3,4",
                arrayOf(1, 2),
                intArrayOf(),
                intArrayOf(2, 3),
                intArrayOf(1, 1),
                intArrayOf(1, -1),
                IntArray(65) { it + 1 },
            )) {
            rejects { Protocol.negotiate(true, 1, commands) }
        }

        rejects { Protocol.negotiate(false, null, intArrayOf(1, 2)) }
    }

    @Test
    fun installedMetadataVersionRequiresExactMatch() {
        assertTrue(Protocol.compatible(1))
        for (version in listOf(0, -1, 2, Int.MAX_VALUE)) assertFalse(Protocol.compatible(version))
    }
}
