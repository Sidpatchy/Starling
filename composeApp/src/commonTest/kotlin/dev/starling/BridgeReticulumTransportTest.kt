package dev.starling

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeReticulumTransportTest {
    @Test
    fun connectionAndRoomCommandsKeepTheirWireFormat() = runTest {
        withBridge { fixture ->
            fixture.transport.connect(ReticulumConnectionConfig(" $HUB ", "  ${"a".repeat(40)}  ", "hub"))
            assertEquals(ReticulumConnectionState.Connecting, fixture.transport.state.value)
            fixture.transport.joinRoom("general 🌍")
            fixture.transport.leaveRoom("general 🌍")

            assertEquals(
                listOf(
                    "RRC_CONNECT\t${encodeBridgeBase64(HUB)}\t${encodeBridgeBase64("a".repeat(32))}",
                    "RRC_JOIN\t${encodeBridgeBase64("general 🌍")}",
                    "RRC_PART\t${encodeBridgeBase64("general 🌍")}",
                ),
                fixture.commands,
            )
        }
    }

    @Test
    fun roomDirectAndNicknameCommandsUseTheSameBridge() = runTest {
        withBridge { fixture ->
            fixture.lines.emit("CONNECTION\tconnected\t")
            fixture.lines.emit("LOCAL_ADDRESS\t${encodeBridgeBase64("local-address")}")
            runCurrent()
            val body = "Hello 🌍\twith tabs\nand lines"
            fixture.transport.sendRoomMessage("general", body, isAction = true)
            fixture.transport.sendRoomMessage("general", body, isAction = false)
            fixture.transport.sendDirectMessage("contact", body)
            fixture.transport.setLocalDisplayName("Mesh 🌍")

            assertEquals(
                listOf(
                    "RRC_SEND\tZ2VuZXJhbA==\t${encodeBridgeBase64(body)}\t1",
                    "RRC_SEND\tZ2VuZXJhbA==\t${encodeBridgeBase64(body)}\t0",
                    "LXMF_SEND\tY29udGFjdA==\t${encodeBridgeBase64(body)}",
                    "LXMF_SET_NAME\t${encodeBridgeBase64("Mesh 🌍")}",
                ),
                fixture.commands,
            )
        }
    }

    @Test
    fun invalidDestinationsAndStoppedNodesNeverSendConnectCommands() = runTest {
        withBridge(ReticulumNodeState.Stopped) { fixture ->
            fixture.transport.connect(ReticulumConnectionConfig("invalid", "Starling", "hub"))
            assertEquals(
                ReticulumConnectionState.Failed("RRC hub destination must be a 32-character hexadecimal hash."),
                fixture.transport.state.value,
            )
            fixture.transport.connect(ReticulumConnectionConfig(HUB, "Starling", "hub"))
            assertEquals(
                ReticulumConnectionState.Failed("Start the Reticulum node before connecting to an RRC hub."),
                fixture.transport.state.value,
            )
            fixture.transport.setLocalDisplayName("Starling")
            assertTrue(fixture.commands.isEmpty())
        }
    }

    @Test
    fun sendingGuardsPreserveRoomAndDirectMessageReadinessRequirements() = runTest {
        withBridge { fixture ->
            assertFailsWith<IllegalStateException> { fixture.transport.joinRoom("general") }
            assertFailsWith<IllegalStateException> { fixture.transport.leaveRoom("general") }
            assertFailsWith<IllegalStateException> { fixture.transport.sendDirectMessage("contact", "hello") }
            fixture.transport.connect(ReticulumConnectionConfig(HUB, "Starling", "hub"))
            assertFailsWith<IllegalStateException> { fixture.transport.sendRoomMessage("general", "hello") }
        }
    }

    @Test
    fun bridgeEventsAreForwardedAndNodeFailureClearsReadinessAndAddress() = runTest {
        withBridge { fixture ->
            val events = mutableListOf<ReticulumEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.transport.events.toList(events) }
            fixture.lines.emit("UNKNOWN\tignored")
            fixture.lines.emit("LOCAL_ADDRESS\t${encodeBridgeBase64("local-address")}")
            fixture.lines.emit("CONNECTION\tconnected\t${encodeBridgeBase64("Mesh")}")
            fixture.lines.emit("IDENTITY\t${encodeBridgeBase64("identity")}")
            runCurrent()

            assertTrue(fixture.transport.nodeReady.value)
            assertEquals("local-address", fixture.transport.localDeliveryAddress.value)
            assertEquals(ReticulumConnectionState.Connected("Mesh"), fixture.transport.state.value)
            assertEquals<List<ReticulumEvent>>(listOf(ReticulumEvent.LocalIdentityChanged("identity")), events)

            fixture.nodeState.value = ReticulumNodeState.Failed("Node exited")
            runCurrent()
            assertFalse(fixture.transport.nodeReady.value)
            assertNull(fixture.transport.localDeliveryAddress.value)
            assertEquals(ReticulumConnectionState.Failed("Node exited"), fixture.transport.state.value)

            fixture.nodeState.value = ReticulumNodeState.Stopped
            runCurrent()
            assertEquals(ReticulumConnectionState.Disconnected, fixture.transport.state.value)
        }
    }

    @Test
    fun nodeFailureDoesNotTurnADisconnectedHubIntoAFailedConnection() = runTest {
        withBridge { fixture ->
            fixture.nodeState.value = ReticulumNodeState.Failed("Node exited")
            runCurrent()
            assertEquals(ReticulumConnectionState.Disconnected, fixture.transport.state.value)
        }
    }

    @Test
    fun connectAndRoomCommandFailuresKeepTheirExistingErrorBehavior() = runTest {
        withBridge { fixture ->
            val events = mutableListOf<ReticulumEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.transport.events.toList(events) }
            fixture.failure = IllegalStateException("Bridge unavailable")
            fixture.transport.connect(ReticulumConnectionConfig(HUB, "Starling", "hub"))
            assertEquals(ReticulumConnectionState.Failed("Bridge unavailable"), fixture.transport.state.value)
            assertTrue(events.isEmpty())

            fixture.lines.emit("CONNECTION\tconnected\t")
            fixture.lines.emit("LOCAL_ADDRESS\t${encodeBridgeBase64("local-address")}")
            runCurrent()
            fixture.transport.sendRoomMessage("general", "hello")
            assertEquals(ReticulumConnectionState.Failed("Bridge unavailable"), fixture.transport.state.value)
            assertEquals<List<ReticulumEvent>>(listOf(ReticulumEvent.Error("Bridge unavailable")), events)
            assertFailsWith<IllegalStateException> { fixture.transport.sendDirectMessage("contact", "hello") }

            fixture.transport.disconnect()
            assertEquals(ReticulumConnectionState.Disconnected, fixture.transport.state.value)
            assertEquals("local-address", fixture.transport.localDeliveryAddress.value)
        }
    }

    @Test
    fun closingDisconnectsAndReleasesBothCollectors() = runTest {
        withBridge { fixture ->
            fixture.transport.close()
            runCurrent()
            assertEquals(listOf("RRC_DISCONNECT"), fixture.commands)
            assertEquals(0, fixture.lines.subscriptionCount.value)
            assertEquals(0, fixture.nodeState.subscriptionCount.value)
            fixture.lines.emit("CONNECTION\tconnected\t")
            runCurrent()
            assertEquals(ReticulumConnectionState.Disconnected, fixture.transport.state.value)
        }
    }

    private suspend fun TestScope.withBridge(
        nodeState: ReticulumNodeState = ReticulumNodeState.Running(ReticulumRuntimeMode.EMBEDDED, emptyList(), emptySet()),
        test: suspend TestScope.(BridgeFixture) -> Unit,
    ) {
        val fixture = BridgeFixture(StandardTestDispatcher(testScheduler), nodeState)
        runCurrent()
        try {
            test(fixture)
        } finally {
            fixture.transport.close()
        }
    }

    private class BridgeFixture(dispatcher: CoroutineDispatcher, initialState: ReticulumNodeState) {
        val nodeState = MutableStateFlow(initialState)
        val lines = MutableSharedFlow<String>(extraBufferCapacity = 128)
        val commands = mutableListOf<String>()
        var failure: Exception? = null
        val transport = BridgeReticulumTransport(nodeState, lines, { command ->
            failure?.let { throw it }
            commands += command
        }, dispatcher)
    }

    companion object {
        private const val HUB = "0123456789abcdef0123456789abcdef"
    }
}
