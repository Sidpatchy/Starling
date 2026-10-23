package com.sidpatchy.Starling

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the shared Reticulum chat repository's network lifecycle, hub connection,
 * preview-mode gating, identity handling, and direct-message validation. The send
 * queue is covered separately by [ReticulumChatRepositorySendTest].
 */
class ReticulumChatRepositoryTest {

    private val HUB = "0123456789abcdef0123456789abcdef"
    private val OTHER_HUB = "fedcba9876543210fedcba9876543210"

    private class FakeTransport : ReticulumTransport {
        override val state = MutableStateFlow<ReticulumConnectionState>(ReticulumConnectionState.Disconnected)
        override val nodeReady = MutableStateFlow(true)
        override val localDeliveryAddress = MutableStateFlow<String?>(null)
        override val events = MutableSharedFlow<ReticulumEvent>(extraBufferCapacity = 64)
        val sentRoomMessages = mutableListOf<String>()
        val sentDirectMessages = mutableListOf<Pair<String, String>>()
        val connectConfigs = mutableListOf<ReticulumConnectionConfig>()
        var disconnectCalls = 0
        /** When non-null, the next sendRoomMessage call throws this instead of recording the message. */
        var sendRoomMessageException: Exception? = null

        override suspend fun connect(config: ReticulumConnectionConfig) {
            connectConfigs += config
        }
        override suspend fun disconnect() {
            disconnectCalls++
        }
        override suspend fun joinRoom(roomId: String) = Unit
        override suspend fun leaveRoom(roomId: String) = Unit
        override suspend fun sendRoomMessage(roomId: String, body: String, isAction: Boolean) {
            sendRoomMessageException?.let { throw it }
            sentRoomMessages += body
        }
        override suspend fun sendDirectMessage(address: String, body: String) {
            sentDirectMessages += address to body
        }
        override suspend fun setLocalDisplayName(displayName: String) = Unit
    }

    private object FakeStateStore : ChatStateStore {
        override fun read(): String? = null
        override fun write(value: String) = Unit
    }

    private data class RepoFixture(
        val repository: ReticulumChatRepository,
        val transport: FakeTransport,
        val network: ChatNetwork,
        val room: ChatRoom,
    )

    private suspend fun TestScope.newRepository(): RepoFixture {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeTransport()
        val preview = LocalPreviewChatRepository()
        val repository = ReticulumChatRepository(preview, transport, FakeStateStore, dispatcher)
        preview.setPreviewMode(false)
        val network = preview.addNetwork("Test Hub", HUB)
        val room = preview.addRoom(network.id, "general", "A test room")
        return RepoFixture(repository, transport, network, room)
    }

    private suspend fun TestScope.connectActiveHub(f: RepoFixture) {
        f.repository.connectToHub(ReticulumConnectionConfig(HUB, "Tester", f.network.id))
    }

    @Test
    fun connectToHubSetsActiveHubAndCallsTransport() = runTest {
        val f = newRepository()
        f.transport.connectConfigs.clear()

        f.repository.connectToHub(ReticulumConnectionConfig(HUB, "Tester", f.network.id))

        assertEquals(f.network.id, f.repository.activeHubNetworkId.value)
        assertEquals(1, f.transport.connectConfigs.size)
        assertEquals(HUB, f.transport.connectConfigs.single().hubDestination)
        assertEquals("Starling", f.transport.connectConfigs.single().displayName)
        assertEquals(f.network.id, f.transport.connectConfigs.single().networkId)
        assertNull(f.repository.lastError.value)
    }

    @Test
    fun connectToHubClearsPriorSendError() = runTest {
        val f = newRepository()
        connectActiveHub(f)
        f.transport.connectConfigs.clear()
        f.transport.state.value = ReticulumConnectionState.Connected("Test Hub")
        f.transport.sendRoomMessageException = IllegalStateException("The hub is not accepting messages.")
        f.repository.send(f.room.id, "unsent message")
        assertEquals("The hub is not accepting messages.", f.repository.lastError.value)

        f.transport.sendRoomMessageException = null
        f.repository.connectToHub(ReticulumConnectionConfig(HUB, "Tester", f.network.id))

        assertNull(f.repository.lastError.value)
        assertEquals(1, f.transport.connectConfigs.size)
    }

    @Test
    fun connectToHubThrowsInPreviewMode() = runTest {
        val f = newRepository()
        f.repository.setPreviewMode(true)
        f.transport.connectConfigs.clear()

        assertFailsWith<IllegalStateException> {
            f.repository.connectToHub(ReticulumConnectionConfig(HUB, "Tester", f.network.id))
        }
        assertTrue(f.transport.connectConfigs.isEmpty())
    }

    @Test
    fun connectToHubThrowsForUnknownNetwork() = runTest {
        val f = newRepository()
        f.transport.connectConfigs.clear()

        assertFailsWith<IllegalArgumentException> {
            f.repository.connectToHub(ReticulumConnectionConfig(HUB, "Tester", "no-such-network"))
        }
        assertTrue(f.transport.connectConfigs.isEmpty())
    }

    @Test
    fun disconnectFromHubClearsActiveHubAndCallsTransport() = runTest {
        val f = newRepository()
        connectActiveHub(f)
        assertEquals(f.network.id, f.repository.activeHubNetworkId.value)

        f.repository.disconnectFromHub()

        assertNull(f.repository.activeHubNetworkId.value)
        assertEquals(1, f.transport.disconnectCalls)
    }

    @Test
    fun sendInPreviewModeDoesNotUseTransport() = runTest {
        val f = newRepository()
        f.repository.setPreviewMode(true)
        f.transport.connectConfigs.clear()

        f.repository.send(f.room.id, "hello from preview")

        assertTrue(f.transport.sentRoomMessages.isEmpty())
        assertTrue(f.transport.connectConfigs.isEmpty())
        assertNull(f.repository.lastError.value)
    }

    @Test
    fun sendWhileDisconnectedIsQueuedAndFlushedOnConnect() = runTest {
        val f = newRepository()
        connectActiveHub(f)
        f.transport.state.value = ReticulumConnectionState.Disconnected

        f.repository.send(f.room.id, "queued message")

        assertTrue(f.transport.sentRoomMessages.isEmpty())
        assertNull(f.repository.lastError.value)

        f.transport.state.value = ReticulumConnectionState.Connected("Test Hub")
        advanceUntilIdle()

        assertEquals(listOf("queued message"), f.transport.sentRoomMessages)
        assertNull(f.repository.lastError.value)
    }

    @Test
    fun sendWhileConnectedIsSentImmediately() = runTest {
        val f = newRepository()
        connectActiveHub(f)
        f.transport.state.value = ReticulumConnectionState.Connected("Test Hub")

        f.repository.send(f.room.id, "immediate message")

        assertEquals(listOf("immediate message"), f.transport.sentRoomMessages)
        assertNull(f.repository.lastError.value)
    }

    @Test
    fun roomJoinedRecordsJoinEventInRoomHistory() = runTest {
        val f = newRepository()
        connectActiveHub(f)

        f.transport.events.emit(ReticulumEvent.RoomJoined(f.room.name))

        val messages = f.repository.messages(f.room.id).first()
        assertTrue(messages.any { message ->
            message.systemEvents.any { it.kind == ChatSystemEventKind.JOIN && it.text == "You joined #${f.room.name}" }
        })
    }

    @Test
    fun queueRejectsMessagesBeyondTheCap() = runTest {
        val f = newRepository()
        f.transport.state.value = ReticulumConnectionState.Disconnected

        repeat(32) { f.repository.send(f.room.id, "queued $it") }
        assertTrue(f.transport.sentRoomMessages.isEmpty())
        assertNull(f.repository.lastError.value)

        f.repository.send(f.room.id, "one too many")

        assertTrue(f.transport.sentRoomMessages.isEmpty())
        assertEquals("Message queue is full. Reconnect and try again.", f.repository.lastError.value)
    }

    @Test
    fun unknownRoomStillThrows() = runTest {
        val f = newRepository()
        f.transport.state.value = ReticulumConnectionState.Connected("Test Hub")

        assertFailsWith<IllegalArgumentException> { f.repository.send("no-such-room", "hello") }
        assertTrue(f.transport.sentRoomMessages.isEmpty())
    }

    @Test
    fun sendToInactiveNetworkIsQueuedAndFlushedOnConnect() = runTest {
        val f = newRepository()
        connectActiveHub(f)
        f.transport.state.value = ReticulumConnectionState.Disconnected

        f.repository.send(f.room.id, "queued for inactive network")

        assertTrue(f.transport.sentRoomMessages.isEmpty())

        f.transport.state.value = ReticulumConnectionState.Connected("Test Hub")
        advanceUntilIdle()

        assertEquals(listOf("queued for inactive network"), f.transport.sentRoomMessages)
    }

    @Test
    fun failedQueuedSendIsRetriedOnReconnect() = runTest {
        val f = newRepository()
        connectActiveHub(f)
        f.transport.state.value = ReticulumConnectionState.Disconnected
        f.transport.sendRoomMessageException = IllegalStateException("Temporarily unavailable.")

        f.repository.send(f.room.id, "first queued")
        f.repository.send(f.room.id, "second queued")

        assertTrue(f.transport.sentRoomMessages.isEmpty())
        assertNull(f.repository.lastError.value)

        f.transport.state.value = ReticulumConnectionState.Connected("Test Hub")
        advanceUntilIdle()

        assertTrue(f.transport.sentRoomMessages.isEmpty())
        assertEquals("Temporarily unavailable.", f.repository.lastError.value)

        f.transport.sendRoomMessageException = null
        f.transport.state.value = ReticulumConnectionState.Disconnected
        f.transport.state.value = ReticulumConnectionState.Connected("Test Hub")
        advanceUntilIdle()

        assertEquals(listOf("first queued", "second queued"), f.transport.sentRoomMessages)
        assertNull(f.repository.lastError.value)
    }

    @Test
    fun reconnectActiveHubConnectsTheActiveHub() = runTest {
        val f = newRepository()
        f.transport.state.value = ReticulumConnectionState.Failed("The hub is not accepting messages.")
        connectActiveHub(f)
        f.transport.connectConfigs.clear()
        f.transport.disconnectCalls = 0

        f.repository.reconnectActiveHub()

        assertEquals(f.network.id, f.repository.activeHubNetworkId.value)
        assertEquals(1, f.transport.disconnectCalls)
        assertEquals(1, f.transport.connectConfigs.size)
        assertEquals(HUB, f.transport.connectConfigs.single().hubDestination)
        assertEquals("Starling", f.transport.connectConfigs.single().displayName)
        assertEquals(f.network.id, f.transport.connectConfigs.single().networkId)
        assertNull(f.repository.lastError.value)
    }

    @Test
    fun removeNetworkRemovesSavedRoomsAndDisconnectsActiveHub() = runTest {
        val f = newRepository()
        connectActiveHub(f)
        f.repository.savePreferredHubNetworkId(f.network.id)

        f.repository.removeNetwork(f.network.id)
        advanceUntilIdle()

        assertTrue(f.repository.networks.value.none { it.id == f.network.id })
        assertTrue(f.repository.rooms.value.none { it.networkId == f.network.id })
        assertNull(f.repository.activeHubNetworkId.value)
        assertNull(f.repository.preferredHubNetworkId.value)
        assertEquals(1, f.transport.disconnectCalls)
    }

    @Test
    fun roomInfoAndModeNoticesUpdateTheSavedTopicAndPermissions() = runTest {
        val f = newRepository()
        connectActiveHub(f)

        f.transport.events.emit(
            ReticulumEvent.RoomMessageReceived(
                RrcRoomMessage(
                    id = "room-info",
                    roomId = f.room.name,
                    senderId = "hub",
                    senderName = "Hub",
                    body = "room ${f.room.name}: registered; mode=+inrt; topic=Live topic",
                    timestampMillis = 100,
                    isMine = false,
                    isNotice = true,
                ),
            ),
        )
        advanceUntilIdle()

        val withSnapshot = f.repository.rooms.value.single { it.id == f.room.id }
        assertEquals("Live topic", withSnapshot.topic)
        assertEquals(
            ChatRoomModes(inviteOnly = true, topicOpsOnly = true, noOutsideMessages = true, registered = true),
            withSnapshot.modes,
        )

        f.transport.events.emit(
            ReticulumEvent.RoomMessageReceived(
                RrcRoomMessage(
                    id = "mode-update",
                    roomId = f.room.name,
                    senderId = "hub",
                    senderName = "Hub",
                    body = "mode for ${f.room.name} is now: +imnrt",
                    timestampMillis = 200,
                    isMine = false,
                    isNotice = true,
                ),
            ),
        )
        advanceUntilIdle()

        val afterModeUpdate = f.repository.rooms.value.single { it.id == f.room.id }
        assertEquals(true, afterModeUpdate.modes?.moderated)
        assertEquals(true, afterModeUpdate.modes?.inviteOnly)
        assertEquals("Live topic", afterModeUpdate.topic)
    }

    @Test
    fun presenceNoticeRefreshesTheRoomRosterWithoutAddingAChatMessage() = runTest {
        val f = newRepository()
        connectActiveHub(f)
        f.transport.state.value = ReticulumConnectionState.Connected("Test Hub")

        f.transport.events.emit(
            ReticulumEvent.RoomMessageReceived(
                RrcRoomMessage(
                    id = "presence-heartbeat",
                    roomId = f.room.name,
                    senderId = "abcdef0123456789abcdef0123456789",
                    senderName = "Other Client",
                    body = RRC_PRESENCE_NOTICE_BODY,
                    timestampMillis = 100,
                    isMine = false,
                    isNotice = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals("Other Client", f.repository.members(f.room.id).first().single().displayName)
        assertTrue(f.repository.messages(f.room.id).first().none { it.text == RRC_PRESENCE_NOTICE_BODY })
    }

    @Test
    fun previouslySeenRoomMembersMoveToOfflineListWhenHubDisconnects() = runTest {
        val f = newRepository()
        connectActiveHub(f)
        f.transport.state.value = ReticulumConnectionState.Connected("Test Hub")
        val member = ChatMember("cached-member", "Cached member")
        f.transport.events.emit(ReticulumEvent.MembersChanged(f.room.name, listOf(member)))
        advanceUntilIdle()

        assertEquals(listOf(member), f.repository.members(f.room.id).first())
        assertTrue(f.repository.offlineMembers(f.room.id).first().isEmpty())

        f.transport.state.value = ReticulumConnectionState.Disconnected
        advanceUntilIdle()

        assertTrue(f.repository.members(f.room.id).first().isEmpty())
        assertEquals(listOf(member), f.repository.offlineMembers(f.room.id).first())
    }

    @Test
    fun reconnectActiveHubThrowsWithNoActiveHub() = runTest {
        val f = newRepository()
        f.repository.disconnectFromHub()
        f.transport.connectConfigs.clear()

        assertFailsWith<IllegalStateException> { f.repository.reconnectActiveHub() }
        assertTrue(f.transport.connectConfigs.isEmpty())
    }

    @Test
    fun reconnectActiveHubThrowsInPreviewMode() = runTest {
        val f = newRepository()
        f.transport.connectConfigs.clear()
        f.repository.setPreviewMode(true)

        assertFailsWith<IllegalStateException> { f.repository.reconnectActiveHub() }
        assertTrue(f.transport.connectConfigs.isEmpty())
    }

    @Test
    fun reconnectActiveHubClearsPriorError() = runTest {
        val f = newRepository()
        connectActiveHub(f)
        f.transport.state.value = ReticulumConnectionState.Connected("Test Hub")
        f.transport.sendRoomMessageException = IllegalStateException("The hub is not accepting messages.")

        f.repository.send(f.room.id, "unsent message")
        assertEquals("The hub is not accepting messages.", f.repository.lastError.value)

        f.repository.reconnectActiveHub()

        assertNull(f.repository.lastError.value)
        assertEquals(2, f.transport.connectConfigs.size)
    }

    @Test
    fun nodeNotReadyBlocksDirectSendAndReportsError() = runTest {
        val f = newRepository()
        f.transport.nodeReady.value = false

        f.repository.sendDirectMessage("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4", "hello")

        assertEquals("Start the Reticulum node before sending an LXMF message.", f.repository.directMessageError.value)
        assertNull(f.repository.lastError.value)
        assertTrue(f.transport.connectConfigs.isEmpty())
    }

    @Test
    fun noDeliveryAddressBlocksDirectSendAndReportsError() = runTest {
        val f = newRepository()
        f.transport.localDeliveryAddress.value = null

        f.repository.sendDirectMessage("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4", "hello")

        assertEquals("The LXMF delivery identity is still starting. Try again in a moment.", f.repository.directMessageError.value)
        assertNull(f.repository.lastError.value)
    }

    @Test
    fun unknownLxmfAddressBlocksDirectSendAndReportsError() = runTest {
        val f = newRepository()
        f.transport.localDeliveryAddress.value = "0123456789abcdef0123456789abcdef"
        val member = ChatMember("noaddr", "noaddr")
        f.repository.addContact(member)

        f.repository.sendDirectMessage("noaddr", "hello")

        assertEquals("This contact has no valid LXMF delivery address. Add or update the contact with their 32-character address.", f.repository.directMessageError.value)
        assertNull(f.repository.lastError.value)
    }

    @Test
    fun sendDirectMessageClearsErrorAndSends() = runTest {
        val f = newRepository()
        f.transport.localDeliveryAddress.value = "0123456789abcdef0123456789abcdef"
        val member = ChatMember("sparrow", "sparrow", address = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        f.repository.addContact(member)

        f.repository.sendDirectMessage("sparrow", "hello direct")

        assertNull(f.repository.directMessageError.value)
        assertEquals(listOf("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4" to "hello direct"), f.transport.sentDirectMessages)
        assertEquals("sending", f.repository.directMessages("sparrow").first().single().deliveryState)
    }

    @Test
    fun pendingDirectMessageUsesBridgeIdAndUpdatesDeliveryState() = runTest {
        val f = newRepository()
        f.transport.localDeliveryAddress.value = "0123456789abcdef0123456789abcdef"
        f.repository.addContact(
            ChatMember("sparrow", "Sparrow", address = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"),
        )

        f.repository.sendDirectMessage("sparrow", "hello direct")
        f.transport.events.emit(
            ReticulumEvent.DirectMessage(
                LxmfDirectMessage("bridge-message-id", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4", "", "hello direct", 10, true, "queued"),
            ),
        )

        val queued = f.repository.directMessages("sparrow").first().single()
        assertEquals("bridge-message-id", queued.id)
        assertEquals("queued", queued.deliveryState)

        f.transport.events.emit(
            ReticulumEvent.DeliveryStatus("bridge-message-id", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4", "delivered"),
        )
        assertEquals("delivered", f.repository.directMessages("sparrow").first().single().deliveryState)
    }

    @Test
    fun lxmfFailureMarksPendingMessageAndKeepsErrorInDmState() = runTest {
        val f = newRepository()
        f.transport.localDeliveryAddress.value = "0123456789abcdef0123456789abcdef"
        f.repository.addContact(
            ChatMember("sparrow", "Sparrow", address = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"),
        )
        f.repository.sendDirectMessage("sparrow", "hello direct")

        f.transport.events.emit(
            ReticulumEvent.LxmfError("Could not discover destination", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"),
        )

        assertEquals("failed", f.repository.directMessages("sparrow").first().single().deliveryState)
        assertEquals("Could not discover destination", f.repository.directMessageError.value)
        assertNull(f.repository.lastError.value)
    }

    @Test
    fun contactsWithTheSameLxmfAddressReuseTheExistingMemberIdentity() = runTest {
        val f = newRepository()
        val address = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
        val roomMember = ChatMember("identity-hash", "Moss", address = address)
        f.repository.addContact(roomMember)

        val added = f.repository.addContact(
            ChatMember("lxmf-$address", "Local Moss", address = "LXMF://${address.uppercase()}"),
        )

        assertEquals("identity-hash", added?.id)
        assertEquals(setOf("identity-hash"), f.repository.contactMemberIds.value)
        assertEquals(1, f.repository.knownMembers.value.count { normalizeLxmfAddress(it.address.orEmpty()) == address })
        assertEquals("Local Moss", f.repository.knownMembers.value.single { it.id == "identity-hash" }.displayName)
    }

    @Test
    fun lxmfAddressNormalizationValidatesAndCanonicalizesDeliveryHashes() {
        val address = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"

        assertEquals(address, normalizeLxmfAddress("  LXMF://${address.uppercase()}  "))
        assertNull(normalizeLxmfAddress("lxmf://not-a-hash"))
        assertNull(normalizeLxmfAddress("a1b2c3d4"))
    }

    @Test
    fun togglingAnRrcMemberWithAContactAliasRemovesTheExistingContact() = runTest {
        val f = newRepository()
        val address = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
        f.repository.addContact(ChatMember("lxmf-$address", "Moss", address = address))

        f.repository.toggleContact(ChatMember("identity-hash", "Moss", address = address))

        assertTrue(f.repository.contactMemberIds.value.isEmpty())
    }

    @Test
    fun lxmfErrorsNeverBecomeRrcRoomMessagesOrRrcErrors() = runTest {
        val f = newRepository()
        f.transport.state.value = ReticulumConnectionState.Connected("Test Hub")
        f.repository.send(f.room.id, "/who")

        f.transport.events.emit(ReticulumEvent.LxmfError("Could not discover this LXMF destination.", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"))

        assertTrue(f.repository.messages(f.room.id).first().isEmpty())
        assertNull(f.repository.lastError.value)
        assertEquals("Could not discover this LXMF destination.", f.repository.directMessageError.value)
    }

    @Test
    fun setPreviewModeClearsActiveHubAndError() = runTest {
        val f = newRepository()
        connectActiveHub(f)
        assertEquals(f.network.id, f.repository.activeHubNetworkId.value)

        f.repository.setPreviewMode(true)

        assertNull(f.repository.activeHubNetworkId.value)
        assertNull(f.repository.lastError.value)
    }

    @Test
    fun updateNetworkNicknameOnActiveNetworkReconnectsWhenConnected() = runTest {
        val f = newRepository()
        connectActiveHub(f)
        f.transport.state.value = ReticulumConnectionState.Connected("Test Hub")

        f.repository.updateNetworkNickname(f.network.id, "Renamed")

        advanceUntilIdle()
        assertEquals(2, f.transport.connectConfigs.size)
        assertEquals(1, f.transport.disconnectCalls)
    }

    @Test
    fun updateNetworkNicknameOnInactiveNetworkDoesNotReconnect() = runTest {
        val f = newRepository()
        val otherNetwork = f.repository.addNetwork("Other Hub", OTHER_HUB)
        connectActiveHub(f)
        f.transport.state.value = ReticulumConnectionState.Connected("Test Hub")

        f.repository.updateNetworkNickname(otherNetwork.id, "Renamed")

        advanceUntilIdle()
        assertEquals(1, f.transport.connectConfigs.size)
        assertEquals(0, f.transport.disconnectCalls)
    }
}
