package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BlePeeringTest {
    @Test
    fun lowerAddressInitiatesAndHigherAddressWaits() {
        // The example pair from the ble-reticulum v2.2 specification.
        val higher = "B8:27:EB:A8:A7:22"
        val lower = "B8:27:EB:10:28:CD"

        assertEquals(BlePeerConnectionDirection.INITIATE, blePeerConnectionDirection(lower, higher))
        assertEquals(BlePeerConnectionDirection.WAIT, blePeerConnectionDirection(higher, lower))
        assertEquals(BlePeerConnectionDirection.INITIATE, blePeerConnectionDirection("b8:27:eb:10:28:cd", higher))
    }

    @Test
    fun directionIsUnknownWithoutTwoOrderableAddresses() {
        val peer = "B8:27:EB:A8:A7:22"

        listOf(null, "", "02:00:00:00:00:00", "00:00:00:00:00:00", "B8:27:EB", "B8-27-EB-A8-A7-22", "ZZ:27:EB:A8:A7:22")
            .forEach { local ->
                assertEquals(BlePeerConnectionDirection.UNKNOWN, blePeerConnectionDirection(local, peer), "local=$local")
            }
        assertEquals(BlePeerConnectionDirection.UNKNOWN, blePeerConnectionDirection(peer, null))
        assertEquals(BlePeerConnectionDirection.UNKNOWN, blePeerConnectionDirection(peer, peer))
    }

    @Test
    fun addressesParseAsUnsigned48BitValues() {
        assertEquals(0xB827EBA8A722L, parseBleAddress("B8:27:EB:A8:A7:22"))
        assertEquals(0xFFFFFFFFFFFFL, parseBleAddress(" ff:ff:ff:ff:ff:ff "))
        assertNull(parseBleAddress("B8:27:EB:A8:A7:2"))
        assertNull(parseBleAddress("B8:27:EB:A8:A7:22:01"))
    }

    @Test
    fun payloadSizeLeavesRoomForTheAttHeader() {
        assertEquals(512, blePeerPayloadSize(BLE_REQUESTED_ATT_MTU))
        assertEquals(244, blePeerPayloadSize(247))
        assertEquals(182, blePeerPayloadSize(185))
        assertEquals(20, blePeerPayloadSize(BLE_DEFAULT_ATT_MTU))
        assertEquals(20, blePeerPayloadSize(0))
    }

    @Test
    fun operationsStartOneAtATimeInSubmissionOrder() {
        val queue = BlePeerOperationQueue<String>()

        assertEquals("discover", queue.submit("discover"))
        assertNull(queue.submit("read identity"))
        assertNull(queue.submit("request mtu"))
        assertEquals("discover", queue.current)
        assertEquals(3, queue.size)

        assertEquals("read identity", queue.finish("discover"))
        assertEquals("request mtu", queue.finish("read identity"))
        assertNull(queue.finish("request mtu"))
        assertNull(queue.current)
        assertEquals(0, queue.size)
        assertEquals("write", queue.submit("write"))
    }

    @Test
    fun staleCompletionsDoNotAdvanceTheQueue() {
        val queue = BlePeerOperationQueue<Any>()
        val running = Any()
        val queued = Any()
        queue.submit(running)
        queue.submit(queued)

        assertNull(queue.finish(queued))
        assertNull(queue.finish(Any()))
        assertSame(running, queue.current)
        assertSame(queued, queue.finish(running))
        assertNull(queue.finish(running))
        assertSame(queued, queue.current)
    }

    @Test
    fun cancellingAConnectionKeepsTheRunningOperationAndOtherPeers() {
        val queue = BlePeerOperationQueue<Pair<String, Int>>()
        queue.submit("a" to 1)
        queue.submit("a" to 2)
        queue.submit("b" to 1)
        queue.submit("a" to 3)

        assertEquals(listOf("a" to 2, "a" to 3), queue.cancelPending { it.first == "a" })
        assertEquals("a" to 1, queue.current)
        assertEquals(2, queue.size)
        assertEquals("b" to 1, queue.finish("a" to 1))
        assertNull(queue.finish("b" to 1))
    }

    @Test
    fun clearReturnsEverythingStillOwed() {
        val queue = BlePeerOperationQueue<String>()
        queue.submit("running")
        queue.submit("queued")

        assertEquals(listOf("running", "queued"), queue.clear())
        assertNull(queue.current)
        assertEquals(0, queue.size)
        assertEquals("next", queue.submit("next"))
    }

    @Test
    fun bluetoothPeersSettingRoundTripsWithTheOtherInterfaceSettings() {
        val config = ReticulumNodeConfig(
            tcpPeers = listOf(ReticulumTcpPeer("mesh.example", 4242)),
            rnodeInterfaces = listOf(ReticulumRNodeInterface("ble://RNode", 915_000_000L, 125_000, 17, 7, 5)),
            blePeeringEnabled = true,
        )

        val decoded = ChatStateCodec.decode(ChatStateCodec.encode(PersistedChatState(nodeConfig = config)))

        assertEquals(config, decoded?.nodeConfig)
        assertEquals(
            ReticulumNodeConfig(blePeeringEnabled = true),
            ChatStateCodec.decode(ChatStateCodec.encode(PersistedChatState(nodeConfig = ReticulumNodeConfig(blePeeringEnabled = true))))?.nodeConfig,
        )
    }

    @Test
    fun bluetoothPeersSettingIsOffByDefaultAndForStateSavedBeforeItExisted() {
        val config = ReticulumNodeConfig(tcpPeers = listOf(ReticulumTcpPeer("mesh.example", 4242)))
        val encoded = ChatStateCodec.encode(PersistedChatState(nodeConfig = config))

        assertFalse(ReticulumNodeConfig().blePeeringEnabled)
        assertFalse(encoded.lineSequence().any { it.startsWith("E\t") })
        assertEquals(false, ChatStateCodec.decode(encoded)?.nodeConfig?.blePeeringEnabled)
    }

    @Test
    fun bluetoothPeersAloneAreAValidEmbeddedConfiguration() {
        assertNotNull(ReticulumNodeConfig().validationError())
        assertNull(ReticulumNodeConfig(blePeeringEnabled = true).validationError())
        assertNotNull(
            ReticulumNodeConfig(
                mode = ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE,
                existingInstanceRpcKeyHex = "11223344556677889900aabbccddeeff",
                blePeeringEnabled = true,
            ).validationError(),
        )
    }

    @Test
    fun runningStateCountsBluetoothPeeringAsOneInterface() {
        val idle = ReticulumNodeState.Running(
            mode = ReticulumRuntimeMode.EMBEDDED,
            tcpPeers = emptyList(),
            onlineTcpPeerIds = emptySet(),
            blePeeringEnabled = true,
            blePeeringOnline = true,
        )

        assertEquals(1, idle.configuredInterfaceCount)
        assertEquals(1, idle.onlineInterfaceCount)
        assertFalse(idle.interfaceOnline)
        assertTrue(idle.copy(blePeerCount = 3).interfaceOnline)
        assertEquals(1, idle.copy(blePeerCount = 3).onlineInterfaceCount)
        assertEquals(0, idle.copy(blePeeringOnline = false).onlineInterfaceCount)
        assertEquals(0, idle.copy(blePeeringEnabled = false).configuredInterfaceCount)
    }

    @Test
    fun failedScansAreRetriedWithADoublingDelayUpToAMinute() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L), (1..6).map(::blePeerScanRetryDelayMs))
        assertEquals(60_000L, blePeerScanRetryDelayMs(1_000))
        assertEquals(2_000L, blePeerScanRetryDelayMs(0))
    }

    @Test
    fun statusExplainsThatBluetoothIsOffInsteadOfCountingPeers() {
        assertEquals("0 Bluetooth peers connected", blePeerStatusText(0, bluetoothOff = false))
        assertEquals("1 Bluetooth peer connected", blePeerStatusText(1, bluetoothOff = false))
        assertEquals(
            "Bluetooth is off; Bluetooth peers will connect when it is turned on",
            blePeerStatusText(0, bluetoothOff = true),
        )
    }

    @Test
    fun platformsDoNotOfferBluetoothPeersUnlessTheyOptIn() {
        assertFalse(UnsupportedReticulumNodeController("unavailable").supportsBlePeering)
    }
}
