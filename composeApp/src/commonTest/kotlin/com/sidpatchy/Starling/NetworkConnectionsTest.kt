package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkConnectionsTest {
    private val peer = ReticulumTcpPeer("mesh.example", 4242)
    private val bluetooth = ReticulumRNodeInterface("ble://RNode Field", 915_000_000L, 125_000, 0, 7, 5)
    private val wifi = bluetooth.copy(port = "tcp://radio.local")

    @Test
    fun chooserOffersOnlyConnectionTypesSupportedByThePlatform() {
        assertEquals(listOf(NetworkConnectionKind.TCP), networkConnectionKinds(false, false))
        assertEquals(
            listOf(NetworkConnectionKind.TCP, NetworkConnectionKind.BLUETOOTH, NetworkConnectionKind.WIFI),
            networkConnectionKinds(true, true),
        )
        assertEquals(
            listOf(NetworkConnectionKind.TCP, NetworkConnectionKind.SERIAL, NetworkConnectionKind.WIFI),
            networkConnectionKinds(true, false),
        )
    }

    @Test
    fun stagingAConnectionValidatesItWithoutRequiringTheOtherKind() {
        assertNull(newTcpConnectionError(peer, emptyList()))
        assertNull(newRNodeConnectionError(bluetooth, emptyList(), supportsBleRNodes = true))
        assertNull(newRNodeConnectionError(wifi, listOf(bluetooth), supportsBleRNodes = true))
        assertNotNull(newTcpConnectionError(peer.copy(host = "MESH.EXAMPLE"), listOf(peer)))
        assertNotNull(newTcpConnectionError(peer.copy(ifacPassphrase = "incomplete"), emptyList()))
        assertNotNull(newRNodeConnectionError(bluetooth.copy(port = "ble://Other"), listOf(bluetooth), true))
        assertNotNull(newRNodeConnectionError(wifi.copy(port = "tcp://https://radio.local"), emptyList(), true))
    }

    @Test
    fun legacyEmptyTcpPlaceholderIsRemovedWithoutDiscardingEnteredSettings() {
        assertTrue(listOf(ReticulumTcpPeer("", 4242)).withoutEmptyDefaultTcpPeer().isEmpty())
        val configured = listOf(peer, ReticulumTcpPeer("", 4243))
        assertEquals(configured, configured.withoutEmptyDefaultTcpPeer())
    }

    @Test
    fun savedConnectionIsNotReportedAsOnlineOrRetrying() {
        assertEquals(NetworkConnectionStatus.SAVED, networkConnectionStatus(peer.id, emptySet(), emptySet()))
        assertEquals(NetworkConnectionStatus.RETRYING, networkConnectionStatus(peer.id, setOf(peer.id), emptySet()))
        assertEquals(NetworkConnectionStatus.ONLINE, networkConnectionStatus(peer.id, setOf(peer.id), setOf(peer.id)))
    }

    @Test
    fun migratingAConnectedBluetoothRadioKeepsItsOnlineStatus() {
        val running = ReticulumNodeState.Running(
            ReticulumRuntimeMode.EMBEDDED, emptyList(), emptySet(),
            rnodeInterfaces = listOf(bluetooth), onlineRNodePorts = setOf(bluetooth.id),
        )
        val resolved = ResolvedBleRNode("12345678-1234-1234-1234-123456789abc", "RNode Field")
        val connections = activeRNodeConnections(running, resolved)
        assertEquals(setOf(rnodeBlePort(resolved.id)), connections.keys)
        assertEquals(NetworkConnectionStatus.ONLINE, connections.values.single().second)
    }

    @Test
    fun resumeRecoveryOnlyLooksAtTcpConnectionsOnTheRunningNode() {
        val running = ReticulumNodeState.Running(
            ReticulumRuntimeMode.EMBEDDED, emptyList(), emptySet(),
            rnodeInterfaces = listOf(bluetooth), onlineRNodePorts = setOf(bluetooth.id),
        )
        assertFalse(running.needsTcpRecovery())
        assertTrue(running.copy(tcpPeers = listOf(peer)).needsTcpRecovery())
        assertFalse(running.copy(tcpPeers = listOf(peer), onlineTcpPeerIds = setOf(peer.id)).needsTcpRecovery())
        assertTrue(running.copy(rnodeInterfaces = listOf(bluetooth, wifi)).needsTcpRecovery())
        assertFalse(
            running.copy(rnodeInterfaces = listOf(bluetooth, wifi), onlineRNodePorts = setOf(wifi.id))
                .needsTcpRecovery(),
        )
    }
}
