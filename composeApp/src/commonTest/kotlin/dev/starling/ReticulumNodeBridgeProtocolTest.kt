package dev.starling

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReticulumNodeBridgeProtocolTest {
    private val config = ReticulumNodeConfig(
        tcpPeers = listOf(ReticulumTcpPeer("mesh.example", 4242, "private mesh", "passphrase")),
        rnodeInterfaces = listOf(ReticulumRNodeInterface("/dev/ttyUSB0", 915_000_000L, 125_000, 17, 7, 5)),
    )

    @Test
    fun readyAndStatusLinesPreserveConfigurationAndParseNestedInterfaceStatus() {
        val peers = "${encodeBridgeBase64("MESH.example")},4242,1;${encodeBridgeBase64("offline")},4243,0;bad,port,1"
        val radios = "${encodeBridgeBase64("/dev/ttyUSB0")},1;${encodeBridgeBase64("COM3")},0;,1"
        for (prefix in listOf("READY", "STATUS")) {
            assertEquals(
                ReticulumNodeState.Running(
                    mode = config.mode,
                    tcpPeers = config.tcpPeers,
                    onlineTcpPeerIds = setOf("mesh.example:4242"),
                    sharedInstanceConnected = true,
                    rnodeInterfaces = config.rnodeInterfaces,
                    onlineRNodePorts = setOf("/dev/ttyusb0"),
                ),
                parseReticulumNodeBridgeState(
                    "$prefix\tRUNNING\tEMBEDDED\t${encodeBridgeBase64(peers)}\t3\t1\t${encodeBridgeBase64(radios)}",
                    config,
                ),
            )
        }
    }

    @Test
    fun missingOptionalFieldsAndUnknownModesKeepTheirFallbacks() {
        assertEquals(
            ReticulumNodeState.Running(config.mode, config.tcpPeers, emptySet(), rnodeInterfaces = config.rnodeInterfaces),
            parseReticulumNodeBridgeState("STATUS\tRUNNING\tunknown", config),
        )
        assertEquals(
            ReticulumNodeState.Running(
                ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE, config.tcpPeers, emptySet(), rnodeInterfaces = config.rnodeInterfaces,
            ),
            parseReticulumNodeBridgeState("STATUS\tRUNNING\tEXISTING_SHARED_INSTANCE", config),
        )
    }

    @Test
    fun stoppedStartupIsAFailureButStandaloneStoppedIsANodeState() {
        assertEquals(ReticulumNodeState.Stopped, parseReticulumNodeBridgeState("STOPPED", config))
        assertEquals(
            ReticulumNodeState.Failed("Reticulum started without a running shared instance."),
            parseReticulumNodeBridgeState("READY\tSTOPPED", config),
        )
        assertNull(parseReticulumNodeBridgeState("STATUS\tSTOPPED", config))
    }

    @Test
    fun failureMessagesDecodeAndPlatformSpecificDefaultsArePreserved() {
        assertEquals(
            ReticulumNodeState.Failed("Failure 🌍"),
            parseReticulumNodeBridgeState("ERROR\t${encodeBridgeBase64("Failure 🌍")}", config),
        )
        assertEquals(ReticulumNodeState.Failed("The RNS runtime failed."), parseReticulumNodeBridgeState("ERROR", config))
        assertEquals(
            ReticulumNodeState.Failed("The iOS Reticulum runtime failed."),
            parseReticulumNodeBridgeState("ERROR\t", config, defaultFailureMessage = "The iOS Reticulum runtime failed."),
        )
    }

    @Test
    fun fieldDecodingPolicyCanRemainStrictOnDesktopAndLenientOnIos() {
        val line = "ERROR\tYW!Jj"
        assertEquals(ReticulumNodeState.Failed("abc"), parseReticulumNodeBridgeState(line, config))
        assertEquals(
            ReticulumNodeState.Failed("The RNS runtime failed."),
            parseReticulumNodeBridgeState(line, config, decodeField = { value ->
                runCatching { Base64.decode(value).decodeToString() }.getOrDefault("")
            }),
        )
    }

    @Test
    fun unrelatedAndIncompleteLinesAreNotConsumedAsNodeStatus() {
        for (line in listOf("", "EVENT\tIDENTITY\tYWJj", "UNKNOWN", "READY", "STATUS", "STATUS\tFAILED")) {
            assertNull(parseReticulumNodeBridgeState(line, config), line)
        }
    }
}
