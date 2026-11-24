package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NearbyPeopleTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun address(index: Int) = index.toString(16).padStart(32, '0')

    private fun onBluetooth(index: Int, name: String = "", ageMillis: Long = minute) =
        NearbyPeer(address(index), name, bleHeardMillis = now - ageMillis, bleHops = 1)

    private fun onNetwork(index: Int, name: String = "", ageMillis: Long = minute, hops: Int = 3, kind: String = "tcp") =
        NearbyPeer(address(index), name, networkHeardMillis = now - ageMillis, networkHops = hops, networkKind = kind)

    private fun listing(vararg peers: NearbyPeer, members: List<ChatMember> = emptyList(), contacts: Set<String> = emptySet()) =
        buildNearbyListing(peers.toList(), now, members, contacts)

    @Test
    fun aDirectlyHeardAnnounceIsOneHopInReticulum() {
        assertEquals("Direct", hopLabel(1))
        assertEquals("Direct", hopLabel(0))
        assertEquals("2 hops", hopLabel(2))
        assertEquals("7 hops", hopLabel(7))
    }

    @Test
    fun lastHeardIsShownRelativeToNow() {
        assertEquals("Just now", relativeHeardLabel(now, now))
        assertEquals("Just now", relativeHeardLabel(now - 59_000, now))
        assertEquals("Just now", relativeHeardLabel(now + 5_000, now))
        assertEquals("1 min ago", relativeHeardLabel(now - minute, now))
        assertEquals("12 min ago", relativeHeardLabel(now - 12 * minute, now))
        assertEquals("59 min ago", relativeHeardLabel(now - 59 * minute, now))
        assertEquals("1 h ago", relativeHeardLabel(now - hour, now))
        assertEquals("2 h ago", relativeHeardLabel(now - 2 * hour - 59 * minute, now))
        assertEquals("23 h ago", relativeHeardLabel(now - 23 * hour, now))
        assertEquals("1 day ago", relativeHeardLabel(now - day, now))
        assertEquals("2 days ago", relativeHeardLabel(now - 2 * day - hour, now))
    }

    @Test
    fun namesAreCappedAndStrippedOfControlAndBidiOverrideCharacters() {
        assertEquals("Wren", sanitizeNearbyName("  Wren \n"))
        assertEquals("Wren", sanitizeNearbyName("W\u0000r\u0007e\tn\u007F\u0085"))
        assertEquals("admin", sanitizeNearbyName("‮nimda‬".reversed()))
        assertEquals("ab", sanitizeNearbyName("a⁦⁧⁨⁩‪‫‭‮b"))
        assertEquals("Mesh 🌍 مرحبا", sanitizeNearbyName("Mesh 🌍 مرحبا"))
        assertEquals("", sanitizeNearbyName(" ‮\n "))
        assertEquals(NEARBY_NAME_LIMIT, sanitizeNearbyName("x".repeat(200)).length)
        // A cut never leaves half of a surrogate pair behind.
        assertEquals("x".repeat(63), sanitizeNearbyName("x".repeat(63) + "🌍"))
    }

    @Test
    fun interfacesUseTheNamesTheRestOfTheAppUses() {
        assertEquals("Bluetooth", nearbyInterfaceLabel("ble"))
        assertEquals("TCP", nearbyInterfaceLabel("tcp"))
        assertEquals("RNode", nearbyInterfaceLabel("rnode"))
        assertEquals("Shared instance", nearbyInterfaceLabel("LocalClientInterface"))
        assertEquals("AutoInterface", nearbyInterfaceLabel("AutoInterface"))
        assertEquals("Bluetooth · Direct", nearbyHeardOverLabel("ble", 1))
        assertEquals("TCP · 3 hops · 12 min ago", nearbyReadingLabel("tcp", 3, now - 12 * minute, now))
        // An unknown interface is left out rather than given a made-up name.
        assertEquals("2 hops · Just now", nearbyReadingLabel("", 2, now, now))
    }

    @Test
    fun rowsSortByMinuteThenNameWithNamelessLastThenAddress() {
        val rows = listing(
            onNetwork(1, "delta", ageMillis = 5 * minute),
            onNetwork(2, "", ageMillis = minute + 10_000),
            onNetwork(3, "Bravo", ageMillis = minute + 50_000),
            onNetwork(4, "alpha", ageMillis = minute + 30_000),
            onNetwork(6, "", ageMillis = minute + 20_000),
            onNetwork(5, "charlie", ageMillis = 30_000),
        ).network

        assertEquals(listOf("charlie", "alpha", "Bravo", "", "", "delta"), rows.map { it.displayName })
        assertEquals(listOf(address(2), address(6)), rows.filter { it.displayName.isBlank() }.map { it.peer.address })
    }

    @Test
    fun repeatedNamesGetTheStartOfTheirAddressAndOthersDoNot() {
        val result = listing(
            NearbyPeer("7f3a" + "0".repeat(28), "Wren", bleHeardMillis = now - minute, bleHops = 1),
            NearbyPeer("c28e" + "0".repeat(28), "Wren", networkHeardMillis = now - minute, networkHops = 2),
            onNetwork(9, "Heron"),
            onNetwork(10, ""),
            onNetwork(11, ""),
        )

        assertEquals("7f3a", result.bluetooth.single().nameSuffix)
        assertEquals("c28e", result.network.first { it.displayName == "Wren" }.nameSuffix)
        assertNull(result.network.first { it.displayName == "Heron" }.nameSuffix)
        assertTrue(result.network.filter { it.displayName.isBlank() }.all { it.nameSuffix == null })
    }

    @Test
    fun someoneHeardOnBothAppearsOnceUnderBluetoothWithTheBluetoothReading() {
        val both = onBluetooth(1, "Wren", ageMillis = 2 * minute)
            .copy(networkHeardMillis = now - minute, networkHops = 4, networkKind = "tcp")
        val result = listing(both, onNetwork(2, "Heron"))

        val row = result.bluetooth.single()
        assertEquals(address(1), row.peer.address)
        assertEquals("ble", row.kind)
        assertEquals(1, row.hops)
        assertEquals(now - 2 * minute, row.heardMillis)
        assertTrue(row.peer.heardOnNetwork(now))
        assertEquals(listOf(address(2)), result.network.map { it.peer.address })
    }

    @Test
    fun anExpiredBluetoothReadingMovesThePersonToTheNetworkSection() {
        val both = onBluetooth(1, "Wren", ageMillis = NEARBY_BLE_EXPIRY_MILLIS)
            .copy(networkHeardMillis = now - 2 * hour, networkHops = 4, networkKind = "rnode")
        val result = listing(both)

        assertTrue(result.bluetooth.isEmpty())
        assertEquals("rnode", result.network.single().kind)
        assertEquals(4, result.network.single().hops)
    }

    @Test
    fun readingsExpireAfterAnHourOnBluetoothAndThreeDaysOnTheNetwork() {
        assertEquals(1, listing(onBluetooth(1, ageMillis = NEARBY_BLE_EXPIRY_MILLIS - 1)).bluetooth.size)
        assertTrue(listing(onBluetooth(1, ageMillis = NEARBY_BLE_EXPIRY_MILLIS)).rows.isEmpty())
        assertEquals(1, listing(onNetwork(1, ageMillis = NEARBY_NETWORK_EXPIRY_MILLIS - 1)).network.size)
        assertTrue(listing(onNetwork(1, ageMillis = NEARBY_NETWORK_EXPIRY_MILLIS)).rows.isEmpty())
        assertEquals(hour, NEARBY_BLE_EXPIRY_MILLIS)
        assertEquals(3 * day, NEARBY_NETWORK_EXPIRY_MILLIS)
    }

    @Test
    fun theNetworkSectionShowsTheFiftyMostRecentAndBluetoothIsUncapped() {
        val network = (1..60).map { onNetwork(it, "net $it", ageMillis = it * minute) }
        val bluetooth = (101..170).map { onBluetooth(it, "ble $it") }
        val result = buildNearbyListing(network + bluetooth, now, emptyList(), emptySet())

        assertEquals(70, result.bluetooth.size)
        assertEquals(NEARBY_NETWORK_LIMIT, result.network.size)
        assertTrue(result.networkTruncated)
        assertEquals(address(1), result.network.first().peer.address)
        assertFalse(result.network.any { it.peer.address == address(51) })
        assertFalse(buildNearbyListing(network.take(50), now, emptyList(), emptySet()).networkTruncated)
    }

    @Test
    fun pruningDropsExpiredReadingsAndKeepsTheNewestNetworkPeople() {
        val stale = onBluetooth(1, "Wren", ageMillis = 2 * hour)
            .copy(networkHeardMillis = now - 5 * minute, networkHops = 2, networkKind = "tcp")
        val pruned = pruneNearbyPeers(
            listOf(stale, onBluetooth(2, ageMillis = 2 * hour), onNetwork(3, ageMillis = 4 * day)) +
                (10..70).map { onNetwork(it, ageMillis = it * minute) },
            now,
        )

        val kept = pruned.first { it.address == address(1) }
        assertEquals(0L, kept.bleHeardMillis)
        assertEquals(now - 5 * minute, kept.networkHeardMillis)
        assertFalse(pruned.any { it.address == address(2) || it.address == address(3) })
        assertEquals(NEARBY_NETWORK_LIMIT, pruned.size)
        assertTrue(pruned.any { it.address == address(58) })
        assertFalse(pruned.any { it.address == address(59) })
    }

    @Test
    fun mergingKeepsTheNewerOfEachReadingAndAKnownName() {
        val stored = onNetwork(1, "Wren", ageMillis = hour, hops = 5, kind = "rnode")
        val heard = NearbyPeer(address(1), "", bleHeardMillis = now, bleHops = 1)

        val merged = mergeNearbyPeer(stored, heard)

        assertEquals("Wren", merged.name)
        assertEquals(now, merged.bleHeardMillis)
        assertEquals(now - hour, merged.networkHeardMillis)
        assertEquals(5, merged.networkHops)
        assertEquals("rnode", merged.networkKind)

        val renamed = mergeNearbyPeer(merged, onNetwork(1, "Wren II", ageMillis = 0, hops = 2, kind = "tcp"))
        assertEquals("Wren II", renamed.name)
        assertEquals(now, renamed.bleHeardMillis)
        assertEquals(2, renamed.networkHops)
        assertEquals("tcp", renamed.networkKind)
        assertEquals(heard, mergeNearbyPeer(null, heard))
    }

    @Test
    fun contactsAreMatchedByAddressAndKeepTheirSavedName() {
        val saved = ChatMember("rrc-member", "Wren (work)", address = address(1))
        val result = listing(
            onBluetooth(1, "wren"),
            onBluetooth(2, "Heron"),
            members = listOf(saved),
            contacts = setOf(saved.id),
        )

        val contactRow = result.bluetooth.first { it.peer.address == address(1) }
        assertEquals(saved, contactRow.contact)
        assertEquals("Wren (work)", contactRow.displayName)
        assertEquals("wren", contactRow.peer.name)
        assertNull(result.bluetooth.first { it.peer.address == address(2) }.contact)
        // A known member who is not a contact does not count.
        assertNull(listing(onBluetooth(1, "wren"), members = listOf(saved)).bluetooth.single().contact)
    }

    @Test
    fun theBadgeCountsBluetoothPeopleWhoAreNotContacts() {
        val saved = ChatMember("lxmf-${address(1)}", "Wren", address = address(1))
        val result = listing(
            onBluetooth(1, "Wren"),
            onBluetooth(2, "Heron"),
            onBluetooth(3),
            onNetwork(4, "Crane"),
            members = listOf(saved),
            contacts = setOf(saved.id),
        )

        assertEquals(2, result.newBluetoothCount)
        assertEquals("1 person nearby", nearbyBadgeDescription(1))
        assertEquals("2 people nearby", nearbyBadgeDescription(2))
    }

    @Test
    fun blockedPeopleAreRecognisedByMemberIdOrAddress() {
        val peer = onBluetooth(1, "Wren")
        val roomMember = ChatMember("rrc-member", "Wren", address = address(1))

        assertTrue(isNearbyPeerBlocked(peer, emptyList(), setOf("lxmf-${address(1)}")))
        assertTrue(isNearbyPeerBlocked(peer, listOf(roomMember), setOf(roomMember.id)))
        assertFalse(isNearbyPeerBlocked(peer, listOf(roomMember), emptySet()))
        assertFalse(isNearbyPeerBlocked(onBluetooth(2), listOf(roomMember), setOf(roomMember.id)))
    }

    @Test
    fun samplePeopleNeverExpireAndUseTheirOwnMemberIds() {
        val sample = NearbyPeer(address(1), "wren", bleHeardMillis = now - 5 * day, bleHops = 1, isPreview = true)

        assertEquals(1, listing(sample).bluetooth.size)
        assertEquals("preview-nearby-${address(1)}", nearbyMemberId(sample))
        assertEquals("lxmf-${address(1)}", nearbyMemberId(sample.copy(isPreview = false)))
        assertEquals("00000000…", sample.shortAddress)
    }

    @Test
    fun anOpenDialogSaysWhyThePersonIsNoLongerListed() {
        assertEquals("No longer in range. Last heard 1 h ago.", nearbyExpiredNotice(true, now - hour, now))
        assertEquals("Not heard recently. Last heard 3 days ago.", nearbyExpiredNotice(false, now - 3 * day, now))
    }
}
