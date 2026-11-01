package dev.ros2android.android

import org.junit.Assert.*
import org.junit.Test

class DashboardCommandQueueTest {
    @Test fun offArrivingDuringOnWriteRemainsQueued() {
        val queue = DashboardCommandQueue()
        queue.submit("track", 1.0, 0.0, "", 0, false, switchInput = true)
        val on = queue.snapshot().single()
        queue.submit("track", 0.0, 0.0, "", 1, false, switchInput = true)
        assertFalse(queue.isCurrent(on))
        assertFalse(queue.complete(on, true))
        assertEquals(DashboardCommandQueue.Phase.QUEUED, queue.status("track")!!.phase)
        val off = queue.snapshot().single()
        assertEquals(0.0, off.first, 0.0)
        assertTrue(queue.complete(off, true))
        assertTrue(queue.snapshot().isEmpty())
        assertTrue(queue.switchDescription("track").contains("Requested OFF · written locally"))
        assertTrue(queue.switchDescription("track").contains("Remote state unknown"))
    }
    @Test fun restartingSameWidgetCannotReplayOrCompleteOldRequest() {
        val queue = DashboardCommandQueue()
        queue.submit("track", 1.0, 0.0, "", 0, false, switchInput = true)
        val old = queue.snapshot().single()
        queue.cancel()
        assertTrue(queue.snapshot().isEmpty())
        assertTrue(queue.switchDescription("track").contains("inactive"))
        queue.submit("track", 0.0, 0.0, "", 1, false, switchInput = true)
        assertFalse(queue.complete(old, false))
        assertEquals(DashboardCommandQueue.Phase.QUEUED, queue.status("track")!!.phase)
        assertEquals(0.0, queue.snapshot().single().first, 0.0)
    }
    @Test fun failedWriteIsNotReportedAsWritten() {
        val queue = DashboardCommandQueue()
        queue.submit("track", 0.0, 0.0, "", 0, false, switchInput = true)
        queue.complete(queue.snapshot().single(), false)
        assertTrue(queue.snapshot().isEmpty())
        queue.cancel() // Service teardown must preserve the failure for diagnosis.
        assertTrue(queue.switchDescription("track").contains("write failed"))
    }
    @Test fun rapidChangesCoalesceOnlyWithinTheSameWidget() {
        val queue = DashboardCommandQueue()
        repeat(1000) { queue.submit("track", (it % 2).toDouble(), 0.0, "", it.toLong(), false, switchInput = true) }
        queue.submit("other", 0.0, 0.0, "hello", 1001, false)
        assertEquals(2, queue.snapshot().size)
        val track = queue.snapshot().single { it.id == "track" }
        assertEquals(999L, track.stamp)
        queue.complete(track, true)
        assertEquals("other", queue.snapshot().single().id)
    }
    @Test fun motionKeepsItsOriginalLeaseUntilReplacedOrCancelled() {
        val queue = DashboardCommandQueue()
        queue.submit("drive", 0.2, -0.4, "", 100, true)
        val held = queue.snapshot().single()
        queue.complete(held, true)
        assertSame(held, queue.snapshot().single())
        assertEquals(100L, held.stamp) // A local write does not renew the UI command lease.
        queue.submit("drive", 0.0, 0.0, "", 200, true)
        assertFalse(queue.complete(held, true))
        assertEquals(0.0, queue.snapshot().single().first, 0.0)
        queue.cancel()
        assertTrue(queue.snapshot().isEmpty())
    }
    @Test fun changingAnotherWidgetIntoSwitchDoesNotInventASwitchRequest() {
        val queue = DashboardCommandQueue()
        queue.submit("same_id", 1.0, 0.0, "", 0, false)
        queue.complete(queue.snapshot().single(), true)
        assertEquals("No request sent · remote state unknown", queue.switchDescription("same_id"))
    }
    @Test fun deletedWidgetsDoNotKeepOldState() {
        val queue = DashboardCommandQueue()
        queue.submit("old", 1.0, 0.0, "", 0, false, switchInput = true)
        queue.retain(setOf("new"))
        assertNull(queue.status("old"))
        assertTrue(queue.snapshot().isEmpty())
        assertEquals("No request sent · remote state unknown", queue.switchDescription("new"))
    }
}
