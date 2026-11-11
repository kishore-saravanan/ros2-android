package dev.ros2android.android

import dev.ros2android.protocol.ProjectProtocol as Protocol
import org.junit.Assert.*
import org.junit.Test

class ProjectRequestsTest {
    @Test fun statusPollsDoNotPostponeTheDeadline() {
        val requests = ProjectRequests()
        requests.begin(Protocol.STATUS, 10000)!!
        assertNull(requests.begin(Protocol.STATUS, 13000))
        assertFalse(requests.expired(13999))
        assertTrue(requests.expired(14000))
    }
    @Test fun operatorCommandSupersedesPollAndIgnoresItsLateReply() {
        val requests = ProjectRequests()
        val poll = requests.begin(Protocol.STATUS, 0)!!
        val stop = requests.begin(Protocol.STOP, 10)!!
        assertFalse(requests.accepts(poll, Protocol.STATUS))
        requests.finish(poll)
        assertTrue(requests.accepts(stop, Protocol.STOP))
        assertNull(requests.begin(Protocol.STATUS, 20))
    }
    @Test fun operatorCommandsAreNotQueuedOrRepeated() {
        val requests = ProjectRequests()
        for (command in listOf(Protocol.START, Protocol.STOP, Protocol.CONFIGURE)) {
            val ticket = requests.begin(command, 100)!!
            for (next in Protocol.commands) assertNull(requests.begin(next, 101))
            requests.finish(ticket)
        }
    }
    @Test fun replyMustMatchEndpointAndCommand() {
        val requests = ProjectRequests()
        val first = requests.begin(Protocol.STATUS, 0)!!
        assertFalse(requests.accepts(first, Protocol.START))
        assertTrue(requests.accepts(first, Protocol.STATUS))
        requests.finish(first)
        assertFalse(requests.accepts(first, Protocol.STATUS))
        val next = requests.begin(Protocol.STATUS, 1)!!
        assertFalse(requests.accepts(first, Protocol.STATUS))
        assertTrue(requests.accepts(next, Protocol.STATUS))
    }
    @Test fun reconnectAndCloseInvalidateAllOldRequests() {
        val requests = ProjectRequests()
        val old = requests.begin(Protocol.START, 100)!!
        requests.reset()
        assertFalse(requests.expired(10000))
        val current = requests.begin(Protocol.START, 200)!!
        assertFalse(requests.accepts(old, Protocol.START))
        requests.finish(old)
        assertTrue(requests.accepts(current, Protocol.START))
        requests.reset()
        assertFalse(requests.accepts(current, Protocol.START))
    }
    @Test fun timeoutUsesElapsedTimeAndCommandGetsItsOwnDeadline() {
        val requests = ProjectRequests()
        requests.begin(Protocol.STATUS, 5000)
        requests.begin(Protocol.CONFIGURE, 8900)
        assertFalse(requests.expired(9000))
        assertTrue(requests.expired(12900))
    }
}
