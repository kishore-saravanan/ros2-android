package dev.ros2android.android

/** One latest request per widget. Local writes never imply remote acknowledgement. */
internal class DashboardCommandQueue {
    enum class Phase {
        QUEUED,
        WRITTEN,
        FAILED,
        CANCELLED,
    }

    class Command(
        val id: String,
        val first: Double,
        val second: Double,
        val text: String,
        val stamp: Long,
        val motion: Boolean,
        val switchInput: Boolean,
    )

    data class Status(val command: Command, val phase: Phase)

    private val pending = mutableMapOf<String, Command>()
    private val statuses = mutableMapOf<String, Status>()

    @Synchronized
    fun submit(
        id: String,
        first: Double,
        second: Double,
        text: String,
        stamp: Long,
        motion: Boolean,
        switchInput: Boolean = false,
    ) {
        val command = Command(id, first, second, text, stamp, motion, switchInput)
        pending[id] = command
        statuses[id] = Status(command, Phase.QUEUED)
    }

    @Synchronized fun snapshot(): List<Command> = pending.values.toList()

    @Synchronized fun isCurrent(command: Command): Boolean = pending[command.id] === command

    @Synchronized fun status(id: String): Status? = statuses[id]

    /** Completing an older write must never remove or mark a newer OFF request written. */
    @Synchronized
    fun complete(command: Command, succeeded: Boolean): Boolean {
        if (pending[command.id] !== command) return false
        statuses[command.id] = Status(command, if (succeeded) Phase.WRITTEN else Phase.FAILED)
        if (!command.motion || !succeeded) pending.remove(command.id)
        return true
    }

    @Synchronized
    fun cancel() {
        pending.clear()
        statuses.replaceAll { _, status ->
            if (status.phase == Phase.FAILED) status else status.copy(phase = Phase.CANCELLED)
        }
    }

    @Synchronized
    fun retain(ids: Set<String>) {
        pending.keys.retainAll(ids)
        statuses.keys.retainAll(ids)
    }

    fun switchDescription(id: String): String {
        val status =
            status(id)?.takeIf { it.command.switchInput }
                ?: return "No request sent · remote state unknown"
        val value = if (status.command.first != 0.0) "ON" else "OFF"
        val local =
            when (status.phase) {
                Phase.QUEUED -> "Requested $value · queued"
                Phase.WRITTEN -> "Requested $value · written locally"
                Phase.FAILED -> "Requested $value · write failed"
                Phase.CANCELLED -> "Previous request: $value · inactive"
            }
        return "$local\nRemote state unknown"
    }
}
