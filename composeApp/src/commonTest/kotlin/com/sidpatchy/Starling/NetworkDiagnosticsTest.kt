package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NetworkDiagnosticsTest {

    private val tcpPeer = ReticulumTcpPeer("192.168.1.10", 4242)
    private val otherTcpPeer = ReticulumTcpPeer("10.0.0.5", 9999)
    private val rnodeRadio = ReticulumRNodeInterface(
        port = "/dev/ttyUSB0",
        frequencyHz = 868_000_000,
        bandwidthHz = 125_000,
        txPowerDbm = 20,
        spreadingFactor = 7,
        codingRate = 5,
    )

    private fun runningNode(
        tcpPeers: List<ReticulumTcpPeer> = emptyList(),
        rnodeRadios: List<ReticulumRNodeInterface> = emptyList(),
        onlineTcp: Set<String> = emptySet(),
        onlineRNode: Set<String> = emptySet(),
        sharedInstance: Boolean = false,
    ): ReticulumNodeState.Running =
        ReticulumNodeState.Running(
            mode = ReticulumRuntimeMode.EMBEDDED,
            tcpPeers = tcpPeers,
            onlineTcpPeerIds = onlineTcp,
            sharedInstanceConnected = sharedInstance,
            rnodeInterfaces = rnodeRadios,
            onlineRNodePorts = onlineRNode,
        )

    private val hubNetwork = ChatNetwork(id = "net-1", name = "Example Hub", hubDestination = "abcd1234efgh5678ijkl9012mnop3456")
    private val inactiveNetwork = ChatNetwork(id = "net-2", name = "Inactive", hubDestination = "zzzz0000yyyy1111xxxx2222wwww3333")

    @Test
    fun preview_mode_hides_all_live_data() {
        val diagnostics = buildNetworkDiagnostics(
            nodeState = runningNode(tcpPeers = listOf(tcpPeer), onlineTcp = setOf(tcpPeer.id)),
            rrcState = ReticulumConnectionState.Connected("hub"),
            networks = listOf(hubNetwork),
            activeHubNetworkId = hubNetwork.id,
            localDeliveryAddress = "lxmf1abcdef...",
            previewMode = true,
        )

        assertTrue(diagnostics.previewMode)
        assertEquals("Hidden in Local preview", diagnostics.nodeLabel)
        assertSame(DiagnosticsSeverity.Neutral, diagnostics.nodeSeverity)
        assertNull(diagnostics.interfaceSummary)
        assertTrue(diagnostics.tcpPeers.isEmpty())
        assertTrue(diagnostics.rnodeRadios.isEmpty())
        assertEquals("Hidden in Local preview", diagnostics.rrcLabel)
        assertSame(DiagnosticsSeverity.Neutral, diagnostics.rrcSeverity)
        assertNull(diagnostics.rrcHubName)
        assertNull(diagnostics.rrcDestination)
        assertNull(diagnostics.rrcError)
        assertNull(diagnostics.deliveryAddress)
    }

    @Test
    fun stopped_node_reports_stopped_without_interfaces() {
        val diagnostics = buildNetworkDiagnostics(
            nodeState = ReticulumNodeState.Stopped,
            rrcState = ReticulumConnectionState.Disconnected,
            networks = listOf(hubNetwork),
            activeHubNetworkId = hubNetwork.id,
            localDeliveryAddress = null,
            previewMode = false,
        )

        assertEquals("Stopped", diagnostics.nodeLabel)
        assertSame(DiagnosticsSeverity.Neutral, diagnostics.nodeSeverity)
        assertNull(diagnostics.interfaceSummary)
        assertTrue(diagnostics.tcpPeers.isEmpty())
        assertTrue(diagnostics.rnodeRadios.isEmpty())
        assertEquals("Disconnected", diagnostics.rrcLabel)
        assertEquals(hubNetwork.hubDestination, diagnostics.rrcDestination)
    }

    @Test
    fun failed_node_reports_error() {
        val diagnostics = buildNetworkDiagnostics(
            nodeState = ReticulumNodeState.Failed("RNS exited with code 1"),
            rrcState = ReticulumConnectionState.Disconnected,
            networks = listOf(hubNetwork),
            activeHubNetworkId = hubNetwork.id,
            localDeliveryAddress = null,
            previewMode = false,
        )

        assertEquals("Failed: RNS exited with code 1", diagnostics.nodeLabel)
        assertSame(DiagnosticsSeverity.Error, diagnostics.nodeSeverity)
    }

    @Test
    fun running_node_classifies_tcp_peers_online_and_retrying() {
        val diagnostics = buildNetworkDiagnostics(
            nodeState = runningNode(
                tcpPeers = listOf(tcpPeer, otherTcpPeer),
                onlineTcp = setOf(tcpPeer.id),
            ),
            rrcState = ReticulumConnectionState.Disconnected,
            networks = listOf(hubNetwork),
            activeHubNetworkId = hubNetwork.id,
            localDeliveryAddress = null,
            previewMode = false,
        )

        assertEquals("Running", diagnostics.nodeLabel)
        assertSame(DiagnosticsSeverity.OK, diagnostics.nodeSeverity)
        assertEquals("1 of 2 TCP connections online", diagnostics.interfaceSummary)
        assertEquals(2, diagnostics.tcpPeers.size)
        assertEquals("192.168.1.10:4242", diagnostics.tcpPeers.first { it.port == 4242 }.label)
        assertTrue(diagnostics.tcpPeers.first { it.port == 4242 }.online)
        assertFalse(diagnostics.tcpPeers.first { it.port == 9999 }.online)
    }

    @Test
    fun running_node_classifies_rnode_radios_online_and_retrying() {
        val offlineRadio = ReticulumRNodeInterface(
            port = "ble://RNode-0001",
            frequencyHz = 868_000_000,
            bandwidthHz = 125_000,
            txPowerDbm = 20,
            spreadingFactor = 7,
            codingRate = 5,
        )
        val diagnostics = buildNetworkDiagnostics(
            nodeState = runningNode(
                rnodeRadios = listOf(rnodeRadio, offlineRadio),
                onlineRNode = setOf(rnodeRadio.id),
            ),
            rrcState = ReticulumConnectionState.Disconnected,
            networks = listOf(hubNetwork),
            activeHubNetworkId = hubNetwork.id,
            localDeliveryAddress = null,
            previewMode = false,
        )

        assertEquals("1 of 2 RNode radios online", diagnostics.interfaceSummary)
        assertEquals(2, diagnostics.rnodeRadios.size)
        assertTrue(diagnostics.rnodeRadios.first { it.port == "/dev/ttyUSB0" }.online)
        assertFalse(diagnostics.rnodeRadios.first { it.port == "ble://RNode-0001" }.online)
    }

    @Test
    fun running_node_without_interfaces_reports_no_interface() {
        val diagnostics = buildNetworkDiagnostics(
            nodeState = runningNode(),
            rrcState = ReticulumConnectionState.Disconnected,
            networks = listOf(hubNetwork),
            activeHubNetworkId = hubNetwork.id,
            localDeliveryAddress = null,
            previewMode = false,
        )

        assertEquals("Running without a configured interface.", diagnostics.interfaceSummary)
        assertTrue(diagnostics.tcpPeers.isEmpty())
        assertTrue(diagnostics.rnodeRadios.isEmpty())
    }

    @Test
    fun shared_instance_connected_is_reflected() {
        val diagnostics = buildNetworkDiagnostics(
            nodeState = runningNode(sharedInstance = true),
            rrcState = ReticulumConnectionState.Disconnected,
            networks = listOf(hubNetwork),
            activeHubNetworkId = hubNetwork.id,
            localDeliveryAddress = null,
            previewMode = false,
        )

        assertTrue(diagnostics.interfaceSummary!!.contains("Shared RNS instance connected"))
    }

    @Test
    fun rrc_connected_shows_hub_name_and_ok() {
        val diagnostics = buildNetworkDiagnostics(
            nodeState = ReticulumNodeState.Stopped,
            rrcState = ReticulumConnectionState.Connected("Public Hub"),
            networks = listOf(hubNetwork),
            activeHubNetworkId = hubNetwork.id,
            localDeliveryAddress = null,
            previewMode = false,
        )

        assertEquals("Connected to Public Hub", diagnostics.rrcLabel)
        assertSame(DiagnosticsSeverity.OK, diagnostics.rrcSeverity)
        assertNull(diagnostics.rrcError)
    }

    @Test
    fun rrc_connected_without_hub_name() {
        val diagnostics = buildNetworkDiagnostics(
            nodeState = ReticulumNodeState.Stopped,
            rrcState = ReticulumConnectionState.Connected(),
            networks = listOf(hubNetwork),
            activeHubNetworkId = hubNetwork.id,
            localDeliveryAddress = null,
            previewMode = false,
        )

        assertEquals("Connected", diagnostics.rrcLabel)
    }

    @Test
    fun rrc_failed_reports_error() {
        val diagnostics = buildNetworkDiagnostics(
            nodeState = ReticulumNodeState.Stopped,
            rrcState = ReticulumConnectionState.Failed("No route to hub"),
            networks = listOf(hubNetwork),
            activeHubNetworkId = hubNetwork.id,
            localDeliveryAddress = null,
            previewMode = false,
        )

        assertEquals("Failed: No route to hub", diagnostics.rrcLabel)
        assertSame(DiagnosticsSeverity.Error, diagnostics.rrcSeverity)
        assertEquals("No route to hub", diagnostics.rrcError)
    }

    @Test
    fun rrc_disconnected_and_connecting() {
        val disconnected = buildNetworkDiagnostics(
            nodeState = ReticulumNodeState.Stopped,
            rrcState = ReticulumConnectionState.Disconnected,
            networks = listOf(hubNetwork),
            activeHubNetworkId = hubNetwork.id,
            localDeliveryAddress = null,
            previewMode = false,
        )
        assertEquals("Disconnected", disconnected.rrcLabel)
        assertSame(DiagnosticsSeverity.Neutral, disconnected.rrcSeverity)

        val connecting = buildNetworkDiagnostics(
            nodeState = ReticulumNodeState.Stopped,
            rrcState = ReticulumConnectionState.Connecting,
            networks = listOf(hubNetwork),
            activeHubNetworkId = hubNetwork.id,
            localDeliveryAddress = null,
            previewMode = false,
        )
        assertEquals("Connecting", connecting.rrcLabel)
        assertSame(DiagnosticsSeverity.Warning, connecting.rrcSeverity)
    }

    @Test
    fun active_hub_destination_and_delivery_address_are_shown() {
        val diagnostics = buildNetworkDiagnostics(
            nodeState = ReticulumNodeState.Stopped,
            rrcState = ReticulumConnectionState.Disconnected,
            networks = listOf(hubNetwork, inactiveNetwork),
            activeHubNetworkId = hubNetwork.id,
            localDeliveryAddress = "lxmf1abcdef0123456789abcdef01234567",
            previewMode = false,
        )

        assertEquals(hubNetwork.hubDestination, diagnostics.rrcDestination)
        assertEquals("lxmf1abcdef0123456789abcdef01234567", diagnostics.deliveryAddress)
    }

    @Test
    fun inactive_network_shows_no_destination() {
        val diagnostics = buildNetworkDiagnostics(
            nodeState = ReticulumNodeState.Stopped,
            rrcState = ReticulumConnectionState.Disconnected,
            networks = listOf(hubNetwork, inactiveNetwork),
            activeHubNetworkId = null,
            localDeliveryAddress = null,
            previewMode = false,
        )

        assertNull(diagnostics.rrcDestination)
        assertNull(diagnostics.deliveryAddress)
    }

    @Test
    fun starting_and_preparing_node_states() {
        val starting = buildNetworkDiagnostics(
            nodeState = ReticulumNodeState.Starting(ReticulumRuntimeMode.EMBEDDED),
            rrcState = ReticulumConnectionState.Disconnected,
            networks = listOf(hubNetwork),
            activeHubNetworkId = null,
            localDeliveryAddress = null,
            previewMode = false,
        )
        assertEquals("Starting Reticulum", starting.nodeLabel)
        assertSame(DiagnosticsSeverity.Warning, starting.nodeSeverity)

        val preparing = buildNetworkDiagnostics(
            nodeState = ReticulumNodeState.PreparingRuntime("Loading Python bridge"),
            rrcState = ReticulumConnectionState.Disconnected,
            networks = listOf(hubNetwork),
            activeHubNetworkId = null,
            localDeliveryAddress = null,
            previewMode = false,
        )
        assertEquals("Preparing runtime: Loading Python bridge", preparing.nodeLabel)
        assertSame(DiagnosticsSeverity.Warning, preparing.nodeSeverity)

        val unavailable = buildNetworkDiagnostics(
            nodeState = ReticulumNodeState.Unavailable("Embedded RNS is not available on this platform yet."),
            rrcState = ReticulumConnectionState.Disconnected,
            networks = listOf(hubNetwork),
            activeHubNetworkId = null,
            localDeliveryAddress = null,
            previewMode = false,
        )
        assertEquals("Unavailable: Embedded RNS is not available on this platform yet.", unavailable.nodeLabel)
        assertSame(DiagnosticsSeverity.Neutral, unavailable.nodeSeverity)
    }
}
