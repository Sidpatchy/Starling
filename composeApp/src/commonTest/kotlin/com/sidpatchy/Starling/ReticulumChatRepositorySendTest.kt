package com.sidpatchy.Starling

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the client-side RRC send queue: messages typed while the hub is not
 * reachable are kept instead of dropped and flushed once the connection is
 * established. Mirrors the server-side queueing already verified in
 * [test_rrc.py].
 */
class ReticulumChatRepositorySendTest {

    private val HUB = "0123456789abcdef0123456789abcdef"

    private class FakeTransport : ReticulumTransport {
        override val state = MutableStateFlow<ReticulumConnectionState>(ReticulumConnectionState.Disconnected)
        override val nodeReady = MutableStateFlow(true)
        override val localDeliveryAddress = MutableStateFlow<String?>(null)
        override val events = MutableSharedFlow<ReticulumEvent>(extraBufferCapacity = 64)
        val sentRoomMessages = mutableListOf<String>()
        /** Records every [ReticulumConnectionConfig] passed to [connect], for reconnect assertions. */
        val connectConfigs = mutableListOf<ReticulumConnectionConfig>()
        /** When non-null, the next sendRoomMessage call throws this instead of recording the message. */
        var sendRoomMessageException: Exception? = null

        override suspend fun connect(config: ReticulumConnectionConfig) {
            connectConfigs += config
        }
        override suspend fun disconnect() = Unit
        override suspend fun joinRoom(roomId: String) = Unit
        override suspend fun leaveRoom(roomId: String) = Unit
        override suspend fun sendRoomMessage(roomId: String, body: String, isAction: Boolean) {
            sendRoomMessageException?.let { throw it }
            sentRoomMessages += body
        }
        override suspend fun sendDirectMessage(address: String, body: String) = Unit
        override suspend fun setLocalDisplayName(displayName: String) = Unit
    }

    private object FakeStateStore : ChatStateStore {
        override fun read(): String? = null
        override fun write(value: String) = Unit
    }

    private suspend fun TestScope.newRepository(): Triple<ReticulumChatRepository, FakeTransport, ChatRoom> {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeTransport()
        val preview = LocalPreviewChatRepository()
        val repository = ReticulumChatRepository(preview, transport, FakeStateStore, dispatcher)
        preview.setPreviewMode(false)
        val network = preview.addNetwork("Test Hub", HUB)
        val room = preview.addRoom(network.id, "general", "A test room")
        repository.connectToHub(ReticulumConnectionConfig(HUB, "Starling", network.id))
        return Triple(repository, transport, room)
    }

    @Test
    fun sendWhileDisconnectedIsQueuedAndFlushedOnConnect() = runTest {
        val (repository, transport, room) = newRepository()
        transport.state.value = ReticulumConnectionState.Disconnected

        repository.send(room.id, "queued message")

        // Nothing is sent while the hub is unreachable, and no connection error is shown.
        assertTrue(transport.sentRoomMessages.isEmpty())
        assertNull(repository.lastError.value)

        transport.state.value = ReticulumConnectionState.Connected("Test Hub")

        assertEquals(listOf("queued message"), transport.sentRoomMessages)
        assertNull(repository.lastError.value)
    }

    @Test
    fun sendWhileConnectedIsSentImmediately() = runTest {
        val (repository, transport, room) = newRepository()
        transport.state.value = ReticulumConnectionState.Connected("Test Hub")

        repository.send(room.id, "immediate message")

        assertEquals(listOf("immediate message"), transport.sentRoomMessages)
        assertNull(repository.lastError.value)
    }

    @Test
    fun queueRejectsMessagesBeyondTheCap() = runTest {
        val (repository, transport, room) = newRepository()
        transport.state.value = ReticulumConnectionState.Disconnected

        repeat(32) { repository.send(room.id, "queued $it") }
        assertTrue(transport.sentRoomMessages.isEmpty())
        assertNull(repository.lastError.value)

        repository.send(room.id, "one too many")

        assertTrue(transport.sentRoomMessages.isEmpty())
        assertEquals("Message queue is full. Reconnect and try again.", repository.lastError.value)
    }

    @Test
    fun unknownRoomStillThrows() = runTest {
        val (repository, transport, room) = newRepository()
        transport.state.value = ReticulumConnectionState.Connected("Test Hub")

        assertFailsWith<IllegalArgumentException> { repository.send("no-such-room", "hello") }
        assertTrue(transport.sentRoomMessages.isEmpty())
    }

    @Test
    fun sendToInactiveNetworkIsQueued() = runTest {
        val (repository, transport, room) = newRepository()
        transport.state.value = ReticulumConnectionState.Disconnected

        repository.send(room.id, "queued for inactive network")

        assertTrue(transport.sentRoomMessages.isEmpty())

        transport.state.value = ReticulumConnectionState.Connected("Test Hub")

        assertEquals(listOf("queued for inactive network"), transport.sentRoomMessages)
    }

    @Test
    fun sendWhileConnectedTransportFailureReportsError() = runTest {
        val (repository, transport, room) = newRepository()
        transport.state.value = ReticulumConnectionState.Connected("Test Hub")
        transport.sendRoomMessageException = IllegalStateException("The hub is not accepting messages.")

        repository.send(room.id, "unsent message")

        assertTrue(transport.sentRoomMessages.isEmpty())
        assertEquals("The hub is not accepting messages.", repository.lastError.value)
    }

    @Test
    fun failedQueuedSendIsRetriedOnReconnect() = runTest {
        val (repository, transport, room) = newRepository()
        transport.state.value = ReticulumConnectionState.Disconnected
        transport.sendRoomMessageException = IllegalStateException("Temporarily unavailable.")

        repository.send(room.id, "first queued")
        repository.send(room.id, "second queued")

        assertTrue(transport.sentRoomMessages.isEmpty())
        assertNull(repository.lastError.value)

        // Connect while the transport still fails: the flush fails, no messages are delivered,
        // and the error is surfaced without losing the queue.
        transport.state.value = ReticulumConnectionState.Connected("Test Hub")

        assertTrue(transport.sentRoomMessages.isEmpty())
        assertEquals("Temporarily unavailable.", repository.lastError.value)

        // Recover and reconnect: both queued messages flush in order and the error clears.
        transport.sendRoomMessageException = null
        transport.state.value = ReticulumConnectionState.Disconnected
        transport.state.value = ReticulumConnectionState.Connected("Test Hub")

        assertEquals(listOf("first queued", "second queued"), transport.sentRoomMessages)
        assertNull(repository.lastError.value)
    }

    @Test
    fun reconnectActiveHubConnectsTheActiveHub() = runTest {
        val (repository, transport, room) = newRepository()
        transport.state.value = ReticulumConnectionState.Failed("The hub is not accepting messages.")
        transport.connectConfigs.clear()

        repository.reconnectActiveHub()

        assertEquals(1, transport.connectConfigs.size)
        assertEquals(HUB, transport.connectConfigs.single().hubDestination)
        assertEquals("Starling", transport.connectConfigs.single().displayName)
        assertEquals(room.networkId, transport.connectConfigs.single().networkId)
        assertNull(repository.lastError.value)
    }

    @Test
    fun reconnectActiveHubThrowsWithNoActiveHub() = runTest {
        val (repository, transport, _) = newRepository()
        repository.disconnectFromHub()
        transport.connectConfigs.clear()

        assertFailsWith<IllegalStateException> { repository.reconnectActiveHub() }
        assertTrue(transport.connectConfigs.isEmpty())
    }

    @Test
    fun reconnectActiveHubThrowsInPreviewMode() = runTest {
        val (repository, transport, _) = newRepository()
        transport.connectConfigs.clear()
        repository.setPreviewMode(true)

        assertFailsWith<IllegalStateException> { repository.reconnectActiveHub() }
        assertTrue(transport.connectConfigs.isEmpty())
    }

    @Test
    fun reconnectActiveHubClearsPriorError() = runTest {
        val (repository, transport, room) = newRepository()
        transport.state.value = ReticulumConnectionState.Connected("Test Hub")
        transport.sendRoomMessageException = IllegalStateException("The hub is not accepting messages.")

        repository.send(room.id, "unsent message")
        assertEquals("The hub is not accepting messages.", repository.lastError.value)

        repository.reconnectActiveHub()

        assertNull(repository.lastError.value)
        assertEquals(2, transport.connectConfigs.size)
    }
}
