package dev.ros2android.android

import dev.ros2android.network.CycloneNetwork
import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class CycloneNetworkTest {
    private fun xml(peer: String = "", local: String = "", addresses: Set<String> = emptySet()) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(CycloneNetwork.configuration(peer, local, addresses).byteInputStream())
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid network setting accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun offlineStartupAlwaysHasLoopbackInterfaceAndDiscoveryPeer() {
        val config = xml()
        val interfaces = config.getElementsByTagName("NetworkInterface")
        assertEquals(1, interfaces.length)
        val loopback = interfaces.item(0).attributes
        assertEquals("127.0.0.1", loopback.getNamedItem("address").nodeValue)
        assertEquals("true", loopback.getNamedItem("multicast").nodeValue)
        assertEquals("10", loopback.getNamedItem("priority").nodeValue)
        assertEquals("127.0.0.1", config.getElementsByTagName("Peer").item(0).attributes.getNamedItem("Address").nodeValue)
        assertEquals("true", config.getElementsByTagName("RedundantNetworking").item(0).textContent)
        assertEquals("auto", config.getElementsByTagName("ParticipantIndex").item(0).textContent)
    }
    @Test fun automaticSelectionAddsExternalInterfaceOnlyWhileAvailable() {
        val config = xml(addresses = setOf("192.0.2.1"))
        val interfaces = config.getElementsByTagName("NetworkInterface")
        assertEquals(2, interfaces.length)
        assertEquals("true", interfaces.item(1).attributes.getNamedItem("autodetermine").nodeValue)
        assertEquals(1, xml().getElementsByTagName("NetworkInterface").length)
    }
    @Test fun selectedExternalAddressRetainsLoopbackAndPeer() {
        val config = xml("192.0.2.2", "192.0.2.1", setOf("192.0.2.1", "198.51.100.1"))
        val interfaces = config.getElementsByTagName("NetworkInterface")
        assertEquals(2, interfaces.length)
        assertEquals("192.0.2.1", interfaces.item(1).attributes.getNamedItem("address").nodeValue)
        assertEquals(2, config.getElementsByTagName("Peer").length)
    }
    @Test fun explicitLoopbackDoesNotSelectExternalOrDuplicatePeers() {
        val config = xml("127.0.0.1", "127.0.0.1", setOf("192.0.2.1"))
        assertEquals(1, config.getElementsByTagName("NetworkInterface").length)
        assertEquals(1, config.getElementsByTagName("Peer").length)
    }
    @Test fun stalePinExplainsHowToRecoverWithoutSilentlyChangingSettings() {
        for (addresses in listOf(emptySet(), setOf("198.51.100.1"))) {
            try {
                CycloneNetwork.configuration("", "192.0.2.1", addresses)
                fail("Stale phone address accepted")
            } catch (failure: IllegalArgumentException) {
                assertTrue(failure.message!!.contains("Clear Phone address"))
            }
        }
        // The same profile is valid again when its selected interface returns.
        xml(local = "192.0.2.1", addresses = setOf("192.0.2.1"))
    }
    @Test fun remotePeerNeedNotBelongToThisDeviceOrBeReachable() {
        val config = xml(peer = "192.0.2.99")
        assertEquals(1, config.getElementsByTagName("NetworkInterface").length)
        assertEquals(2, config.getElementsByTagName("Peer").length)
    }
    @Test fun malformedAddressesCannotBecomeXmlOrHostnames() {
        for (bad in listOf("localhost", "::1", "1.2.3", "1.2.3.256", "1..2.3", "-1.2.3.4", "+1.2.3.4",
            " 1.2.3.4", "1.2.3.4\n", "1.2.3.4\"/>", "1.2.3.999999999999999999")) {
            rejects { CycloneNetwork.configuration(bad, "", emptySet()) }
            rejects { CycloneNetwork.configuration("", bad, setOf(bad)) }
        }
    }
}
