package dev.ros2android.protocol

/** Additive v1 envelope; missing fields mean the original authenticated v1 service. */
object ProjectProtocol {
    const val VERSION = 1
    const val VERSION_KEY = "protocolVersion"
    const val COMMANDS_KEY = "supportedCommands"
    const val STATUS = 1
    const val START = 2
    const val STOP = 3
    const val CONFIGURE = 4
    val commands: Set<Int>
        get() = setOf(STATUS, START, STOP, CONFIGURE)

    fun requireRequest(versionPresent: Boolean, version: Any?, command: Int) {
        requireVersion(versionPresent, version)
        require(command in commands) { "Unsupported project control request: $command" }
    }

    fun negotiate(versionPresent: Boolean, version: Any?, advertised: Any?): Set<Int> {
        requireVersion(versionPresent, version)
        if (!versionPresent) {
            require(advertised == null) { "Invalid runner compatibility response" }
            return commands // Legacy v1, identified by installed project metadata.
        }

        require(
            advertised is IntArray &&
                advertised.size in 1..64 &&
                advertised.all { it > 0 } &&
                advertised.toSet().size == advertised.size &&
                STATUS in advertised
        ) {
            "Invalid runner command capabilities"
        }

        return advertised.toSet().intersect(commands)
    }

    fun compatible(version: Int): Boolean = version == VERSION

    private fun requireVersion(present: Boolean, version: Any?) {
        require(!present || version is Int && version == VERSION) {
            "Unsupported runner protocol $version; supported: $VERSION. Use a compatible " +
                "app and runner."
        }
    }
}
