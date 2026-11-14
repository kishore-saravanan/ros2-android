package dev.ros2android.android

import dev.ros2android.protocol.ProjectProtocol

/** One outstanding request. Tokens stay local, so legacy runners need no changes. */
internal class ProjectRequests {
    data class Ticket(val session: Long, val sequence: Long, val command: Int, val sentAt: Long)

    private var session = 0L
    private var sequence = 0L
    private var pending: Ticket? = null

    fun reset() {
        session++
        pending = null
    }

    fun begin(command: Int, now: Long): Ticket? {
        // An operator command can replace a status poll, never another operator command.
        if (
            pending?.let {
                command == ProjectProtocol.STATUS || it.command != ProjectProtocol.STATUS
            } == true
        ) {
            return null
        }

        return Ticket(session, ++sequence, command, now).also { pending = it }
    }

    fun accepts(ticket: Ticket, command: Int): Boolean =
        pending == ticket && ticket.command == command

    fun finish(ticket: Ticket) {
        if (pending == ticket) pending = null
    }

    fun expired(now: Long): Boolean = pending?.let { now - it.sentAt >= TIMEOUT_MS } == true

    companion object {
        const val TIMEOUT_MS = 4000L
    }
}
