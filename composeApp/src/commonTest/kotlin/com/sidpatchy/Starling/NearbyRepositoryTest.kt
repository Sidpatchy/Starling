package com.sidpatchy.Starling

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Covers how the chat repository stores, filters, and persists people heard nearby. */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyRepositoryTest {
    private val local = "fedcba9876543210fedcba9876543210"
    private val wren = "a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7"
    private val heron = "b8b8b8b8b8b8b8b8b8b8b8b8b8b8b8b8"

    private class FakeTransport(address: String?) : ReticulumTransport {
        override val state = MutableStateFlow<ReticulumConnectionState>(ReticulumConnectionState.Disconnected)
        override val nodeReady = MutableStateFlow(true)
        override val localDeliveryAddress = MutableStateFlow(address)
        override val events = MutableSharedFlow<ReticulumEvent>(extraBufferCapacity = 64)
        var announces = 0
        var announceFailure: Exception? = null

        override suspend fun connect(config: ReticulumConnectionConfig) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun joinRoom(roomId: String) = Unit
        override suspend fun leaveRoom(roomId: String) = Unit
        override suspend fun sendRoomMessage(roomId: String, body: String, isAction: Boolean) = Unit
        override suspend fun sendDirectMessage(address: String, body: String) = Unit
        override suspend fun setLocalDisplayName(displayName: String) = Unit
        override suspend fun announce() {
            announceFailure?.let { throw it }
            announces += 1
        }
    }

    private class MemoryStore(var value: String? = null) : ChatStateStore {
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }
    }

    private class Fixture(val repository: ReticulumChatRepository, val transport: FakeTransport, val store: MemoryStore)

    private fun TestScope.liveRepository(store: MemoryStore = MemoryStore()): Fixture {
        if (store.value == null) store.value = ChatStateCodec.encode(PersistedChatState(previewMode = false))
        val transport = FakeTransport(local)
        val repository = ReticulumChatRepository(
            LocalPreviewChatRepository(), transport, store, UnconfinedTestDispatcher(testScheduler),
        )
        return Fixture(repository, transport, store)
    }

    private fun now() = Clock.System.now().toEpochMilliseconds()

    private fun onBluetooth(address: String, name: String) =
        NearbyPeer(address, name, bleHeardMillis = now(), bleHops = 1)

    private fun onNetwork(address: String, name: String, ageMillis: Long = 0) =
        NearbyPeer(address, name, networkHeardMillis = now() - ageMillis, networkHops = 3, networkKind = "tcp")

    @Test
    fun heardPeopleAreListedAndLaterReadingsMergeIntoOneEntry() = runTest {
        val f = liveRepository()

        f.transport.events.emit(ReticulumEvent.NearbyPeerHeard(onNetwork(wren, "Wren")))
        f.transport.events.emit(ReticulumEvent.NearbyPeerHeard(onBluetooth(wren, "")))
        f.transport.events.emit(ReticulumEvent.NearbyPeerHeard(onNetwork(heron, "He‮ron\n")))

        val peers = f.repository.nearbyPeers.value.associateBy { it.address }
        assertEquals(setOf(wren, heron), peers.keys)
        assertEquals("Wren", peers.getValue(wren).name)
        assertTrue(peers.getValue(wren).bleHeardMillis > 0)
        assertEquals("tcp", peers.getValue(wren).networkKind)
        assertEquals("Heron", peers.getValue(heron).name)
        f.repository.close()
    }

    @Test
    fun ourOwnAddressAndMalformedAddressesAreNeverListed() = runTest {
        val f = liveRepository()

        f.transport.events.emit(ReticulumEvent.NearbyPeerHeard(onNetwork(local, "Me")))
        f.transport.events.emit(ReticulumEvent.NearbyPeerHeard(onNetwork("not-an-address", "Nobody")))

        assertTrue(f.repository.nearbyPeers.value.isEmpty())
        f.repository.close()
    }

    @Test
    fun networkPeoplePersistAcrossRestartsAndBluetoothReadingsDoNot() = runTest {
        val f = liveRepository()
        f.transport.events.emit(ReticulumEvent.NearbyPeerHeard(onNetwork(wren, "Wren")))
        f.transport.events.emit(ReticulumEvent.NearbyPeerHeard(onBluetooth(wren, "Wren")))
        f.transport.events.emit(ReticulumEvent.NearbyPeerHeard(onBluetooth(heron, "Heron")))
        f.repository.close()

        val stored = ChatStateCodec.decode(f.store.value!!)!!.nearbyPeers
        assertEquals(listOf(wren), stored.map { it.address })
        assertEquals(0L, stored.single().bleHeardMillis)

        val restored = liveRepository(f.store)
        val peer = restored.repository.nearbyPeers.value.single()
        assertEquals(wren, peer.address)
        assertEquals("Wren", peer.name)
        assertEquals(3, peer.networkHops)
        assertEquals("tcp", peer.networkKind)
        assertEquals(0L, peer.bleHeardMillis)
        restored.repository.close()
    }

    @Test
    fun storedPeopleOlderThanThreeDaysAreDroppedOnRestore() = runTest {
        val store = MemoryStore(
            ChatStateCodec.encode(
                PersistedChatState(
                    previewMode = false,
                    nearbyPeers = listOf(
                        onNetwork(wren, "Wren", ageMillis = NEARBY_NETWORK_EXPIRY_MILLIS + 60_000),
                        onNetwork(heron, "Heron", ageMillis = 60_000),
                    ),
                ),
            ),
        )
        val f = liveRepository(store)

        assertEquals(listOf(heron), f.repository.nearbyPeers.value.map { it.address })
        f.repository.close()
    }

    @Test
    fun onlyTheFiftyMostRecentNetworkPeopleAreKept() = runTest {
        val f = liveRepository()
        repeat(55) { index ->
            val address = index.toString(16).padStart(32, '0')
            f.transport.events.emit(
                ReticulumEvent.NearbyPeerHeard(onNetwork(address, "Peer $index", ageMillis = (55 - index) * 60_000L)),
            )
        }

        val addresses = f.repository.nearbyPeers.value.map { it.address }
        assertEquals(NEARBY_NETWORK_LIMIT, addresses.size)
        assertFalse("0".repeat(32) in addresses)
        assertTrue(54.toString(16).padStart(32, '0') in addresses)
        f.repository.close()
    }

    @Test
    fun addingSomeoneHeardNearbyMakesAContactWithoutAConversation() = runTest {
        val f = liveRepository()
        f.transport.events.emit(ReticulumEvent.NearbyPeerHeard(onBluetooth(wren, "Wren")))

        val contact = f.repository.addContactFromAnnounce(f.repository.nearbyPeers.value.single())

        assertEquals(ChatMember("lxmf-$wren", "Wren", address = wren), contact)
        assertEquals(setOf("lxmf-$wren"), f.repository.contactMemberIds.value)
        assertTrue(f.repository.directMessages("lxmf-$wren").first().isEmpty())
        // The person stays listed, now as a contact.
        assertEquals(listOf(wren), f.repository.nearbyPeers.value.map { it.address })
        f.repository.close()
        val stored = ChatStateCodec.decode(f.store.value!!)!!
        assertEquals(setOf("lxmf-$wren"), stored.contacts)
        assertTrue(stored.directMessages.isEmpty())
    }

    @Test
    fun aNamelessPersonNeedsANameChosenByTheUser() = runTest {
        val f = liveRepository()
        val peer = onBluetooth(wren, "")

        assertNull(f.repository.addContactFromAnnounce(peer))
        assertNull(f.repository.addContactFromAnnounce(peer, "  ‮ "))
        assertTrue(f.repository.contactMemberIds.value.isEmpty())

        assertEquals("Hill neighbour", f.repository.addContactFromAnnounce(peer, " Hill neighbour ")?.displayName)
        f.repository.close()
    }

    @Test
    fun anAnnounceNeverRenamesAnExistingContact() = runTest {
        val f = liveRepository()
        val saved = assertNotNull(f.repository.addContact(ChatMember("lxmf-$wren", "Wren (work)", address = wren)))

        f.transport.events.emit(ReticulumEvent.NearbyPeerHeard(onBluetooth(wren, "xX_wren_Xx")))
        val result = f.repository.addContactFromAnnounce(f.repository.nearbyPeers.value.single())

        assertEquals(saved, result)
        assertEquals("Wren (work)", f.repository.knownMembers.value.single { it.address == wren }.displayName)
        f.repository.close()
    }

    @Test
    fun messagesFromSomeoneAddedFromNearbySkipRequests() = runTest {
        val f = liveRepository()
        f.repository.addContactFromAnnounce(onBluetooth(wren, "Wren"))

        f.transport.events.emit(
            ReticulumEvent.DirectMessage(LxmfDirectMessage("m1", wren, "Wren", "Hello from the hill", 1_000L, isMine = false)),
        )

        assertTrue(f.repository.contactRequests.value.isEmpty())
        assertEquals("Hello from the hill", f.repository.directMessages("lxmf-$wren").first().single().text)
        f.repository.close()
    }

    @Test
    fun blockedSendersAreRemovedFromTheList() = runTest {
        val f = liveRepository()
        f.transport.events.emit(ReticulumEvent.NearbyPeerHeard(onBluetooth(wren, "Wren")))
        f.transport.events.emit(ReticulumEvent.NearbyPeerHeard(onBluetooth(heron, "Heron")))
        f.transport.events.emit(
            ReticulumEvent.DirectMessage(LxmfDirectMessage("m1", wren, "Wren", "spam", 1_000L, isMine = false)),
        )
        assertEquals(2, f.repository.nearbyPeers.value.size)

        f.repository.blockContactRequest(f.repository.contactRequests.value.single().id)

        assertEquals(listOf(heron), f.repository.nearbyPeers.value.map { it.address })
        f.repository.close()
    }

    @Test
    fun manualAnnounceGoesToTheTransportAndFailuresAreReported() = runTest {
        val f = liveRepository()

        f.repository.announce()
        assertEquals(1, f.transport.announces)

        f.transport.announceFailure = IllegalStateException("Start the Reticulum node before announcing.")
        f.repository.announce()
        assertEquals(1, f.transport.announces)
        assertEquals("Start the Reticulum node before announcing.", f.repository.directMessageError.value)
        f.repository.close()
    }

    @Test
    fun previewShowsOnlySamplePeopleAndKeepsLivePeopleOutOfIt() = runTest {
        val f = liveRepository()
        f.transport.events.emit(ReticulumEvent.NearbyPeerHeard(onNetwork(wren, "Live Wren")))

        f.repository.setPreviewMode(true)

        val samples = f.repository.nearbyPeers.value
        assertEquals(3, samples.size)
        assertTrue(samples.all { it.isPreview })
        assertEquals(2, samples.count { it.bleHeardMillis > 0 })
        assertEquals(1, samples.count { it.bleHeardMillis > 0 && it.name.isBlank() })
        assertEquals(1, samples.count { it.networkHeardMillis > 0 })
        assertFalse(samples.any { it.address == wren })

        // Announces heard while previewing are ignored, and announcing does nothing.
        f.transport.events.emit(ReticulumEvent.NearbyPeerHeard(onNetwork(heron, "Live Heron")))
        f.repository.announce()
        assertEquals(0, f.transport.announces)
        assertEquals(3, f.repository.nearbyPeers.value.size)

        // Adding in preview uses the preview contact list only.
        val named = samples.first { it.name.isNotBlank() }
        val added = assertNotNull(f.repository.addContactFromAnnounce(named))
        assertEquals("preview-nearby-${named.address}", added.id)
        assertNull(added.address)
        assertTrue(added.id in f.repository.contactMemberIds.value)
        assertNull(f.repository.addContactFromAnnounce(onNetwork(wren, "Live Wren")))

        f.repository.setPreviewMode(false)

        val live = f.repository.nearbyPeers.value
        assertEquals(listOf(wren), live.map { it.address })
        assertFalse(live.any { it.isPreview })
        assertTrue(f.repository.contactMemberIds.value.isEmpty())
        f.repository.close()
        val stored = ChatStateCodec.decode(f.store.value!!)!!
        assertEquals(listOf(wren), stored.nearbyPeers.map { it.address })
        assertFalse(stored.contacts.contains(added.id))
        assertFalse(stored.members.any { it.id == added.id })
    }

    @Test
    fun aRestoredPreviewSessionShowsSamplesUntilPreviewIsTurnedOff() = runTest {
        val store = MemoryStore(
            ChatStateCodec.encode(
                PersistedChatState(previewMode = true, nearbyPeers = listOf(onNetwork(wren, "Live Wren", ageMillis = 60_000))),
            ),
        )
        val f = liveRepository(store)

        assertTrue(f.repository.nearbyPeers.value.all { it.isPreview })
        f.repository.setPreviewMode(false)
        assertEquals(listOf(wren), f.repository.nearbyPeers.value.map { it.address })
        f.repository.close()
    }
}
