package dev.robotbrain.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionConfigTest {
    @Test fun acceptsNestedRosNamespacesAndBoundaryDomains() {
        assertEquals("/robot_1/phone", SessionConfig("/robot_1/phone", 101).namespace)
        assertEquals(0, SessionConfig("/phone", 0).domainId)
    }

    @Test fun rejectsInvalidRosNamesBeforeSaving() {
        listOf("phone", "/9phone", "/phone/", "/robot//phone", "/phone name", "").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { SessionConfig(name, 0) }
        }
    }

    @Test fun rejectsDomainsOutsidePrototypeRange() {
        listOf(-1, 102, 232).forEach { domain ->
            assertThrows(IllegalArgumentException::class.java) { SessionConfig("/phone", domain) }
        }
    }
}

