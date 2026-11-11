package dev.ros2android.network

import java.net.Inet4Address
import java.net.NetworkInterface

/** Startup snapshot shared by sensors, all runner backends and the workspace shell. */
object CycloneNetwork {
    fun current(peer: String, local: String): String {
        val addresses = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().filterIsInstance<Inet4Address>() }
            .mapNotNull { it.hostAddress }.toSet()
        return configuration(peer, local, addresses)
    }

    /** Pure policy; activeAddresses contains this device's currently up external IPv4 addresses. */
    fun configuration(peer: String, local: String, activeAddresses: Set<String>): String {
        for (address in listOf(peer, local)) require(address.isEmpty() || ipv4(address)) {
            "Use an IPv4 address or leave blank"
        }
        require(local.isEmpty() || local == LOOPBACK || local in activeAddresses) {
            "Selected phone address $local is unavailable. Clear Phone address for automatic selection, or use a current phone IPv4 address, then restart."
        }
        return buildString {
            append("<CycloneDDS><Domain><General><Interfaces>")
            // Pinned Cyclone disables multicast globally if any selected interface is
            // non-multicast-capable. Android loopback supports IP multicast despite
            // not advertising IFF_MULTICAST; retain the main app's explicit override.
            append("<NetworkInterface address=\"$LOOPBACK\" multicast=\"true\" priority=\"10\"/>")
            if (local.isNotEmpty() && local != LOOPBACK) append("<NetworkInterface address=\"$local\"/>")
            else if (local.isEmpty() && activeAddresses.isNotEmpty()) append("<NetworkInterface autodetermine=\"true\"/>")
            append("</Interfaces><MulticastRecvNetworkInterfaceAddresses>all</MulticastRecvNetworkInterfaceAddresses>")
            append("<RedundantNetworking>true</RedundantNetworking><AllowMulticast>true</AllowMulticast></General>")
            append("<Discovery><ParticipantIndex>auto</ParticipantIndex><MaxAutoParticipantIndex>50</MaxAutoParticipantIndex>")
            append("<Peers><Peer Address=\"$LOOPBACK\"/>")
            if (peer.isNotEmpty() && peer != LOOPBACK) append("<Peer Address=\"$peer\"/>")
            append("</Peers></Discovery></Domain></CycloneDDS>")
        }
    }

    private fun ipv4(value: String): Boolean = value.split('.').let { parts ->
        parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all { it in '0'..'9' } && part.toIntOrNull() in 0..255
        }
    }
    private const val LOOPBACK = "127.0.0.1"
}
