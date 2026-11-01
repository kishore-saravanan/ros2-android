package dev.ros2android.android

import dev.ros2android.security.SigningIdentity
import org.junit.Assert.*
import org.junit.Test

class SigningIdentityTest {
    @Test fun uidMustBelongOnlyToTheExpectedPackage() {
        val name = "dev.ros2android.android"
        assertTrue(SigningIdentity.uidMatches(name, 10001, 10001, setOf(name)))
        assertFalse(SigningIdentity.uidMatches(name, 10002, 10001, setOf(name)))
        assertFalse(SigningIdentity.uidMatches(name, -1, -1, setOf(name)))
        assertFalse(SigningIdentity.uidMatches(name, 10001, 10001, setOf(name, "other.app")))
        assertFalse(SigningIdentity.uidMatches(name, 10001, null, null))
    }
    @Test fun fingerprintsFailClosed() {
        val valid = "ab".repeat(32)
        assertTrue(SigningIdentity.matches(valid, valid))
        for (invalid in listOf(null, "", "ab", "AB".repeat(32), "xy".repeat(32))) {
            assertFalse(SigningIdentity.matches(invalid, invalid))
            assertFalse(SigningIdentity.matches(valid, invalid))
        }
        assertFalse(SigningIdentity.matches(valid, "cd".repeat(32)))
    }
}
