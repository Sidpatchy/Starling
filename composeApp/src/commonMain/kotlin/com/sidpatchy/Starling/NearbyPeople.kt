package com.sidpatchy.Starling

/**
 * A person heard through an LXMF delivery announce. One entry per delivery
 * address holds at most one Bluetooth reading and one network reading; a
 * reading with a zero timestamp was not heard.
 */
data class NearbyPeer(
    val address: String,
    val name: String = "",
    val bleHeardMillis: Long = 0,
    val bleHops: Int = 0,
    val networkHeardMillis: Long = 0,
    val networkHops: Int = 0,
    /** Bridge interface kind of the network reading: tcp, rnode, or an RNS interface class name. */
    val networkKind: String = "",
    val isPreview: Boolean = false,
)

internal const val NEARBY_BLE_EXPIRY_MILLIS = 60L * 60L * 1000L
internal const val NEARBY_NETWORK_EXPIRY_MILLIS = 3L * 24L * 60L * 60L * 1000L
internal const val NEARBY_NETWORK_LIMIT = 50
internal const val NEARBY_NAME_LIMIT = 64
internal const val NEARBY_KIND_BLE = "ble"
private const val MINUTE_MILLIS = 60_000L

/** Sample entries never expire, so the preview always has something to show. */
internal fun NearbyPeer.heardOnBluetooth(nowMillis: Long): Boolean =
    bleHeardMillis > 0 && (isPreview || nowMillis - bleHeardMillis < NEARBY_BLE_EXPIRY_MILLIS)

internal fun NearbyPeer.heardOnNetwork(nowMillis: Long): Boolean =
    networkHeardMillis > 0 && (isPreview || nowMillis - networkHeardMillis < NEARBY_NETWORK_EXPIRY_MILLIS)

internal val NearbyPeer.shortAddress: String get() = address.take(8) + "…"

/** Contacts made from an announce use the same member id as other LXMF-only contacts. */
internal fun nearbyMemberId(peer: NearbyPeer): String =
    if (peer.isPreview) "preview-nearby-${peer.address}" else "lxmf-${peer.address}"

/**
 * Display-only clean-up of a name chosen by its sender: drop control characters and
 * bidirectional embedding, override, and isolate controls, then cap the length.
 */
internal fun sanitizeNearbyName(value: String): String {
    val cleaned = buildString {
        value.forEach { character ->
            val code = character.code
            val isControl = code < 0x20 || code in 0x7F..0x9F
            val isBidiControl = code in 0x202A..0x202E || code in 0x2066..0x2069
            if (!isControl && !isBidiControl) append(character)
        }
    }.trim()
    if (cleaned.length <= NEARBY_NAME_LIMIT) return cleaned
    val end = if (cleaned[NEARBY_NAME_LIMIT - 1].isHighSurrogate()) NEARBY_NAME_LIMIT - 1 else NEARBY_NAME_LIMIT
    return cleaned.substring(0, end).trim()
}

/**
 * Reticulum counts the receiving hop, so an announce heard straight from its
 * sender is stored with one hop.
 */
internal fun hopLabel(hops: Int): String = if (hops <= 1) "Direct" else "$hops hops"

internal fun relativeHeardLabel(heardMillis: Long, nowMillis: Long): String {
    val minutes = (nowMillis - heardMillis).coerceAtLeast(0) / MINUTE_MILLIS
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours h ago"
        days == 1L -> "1 day ago"
        else -> "$days days ago"
    }
}

/** Names match the ones Settings and diagnostics already use; other types keep their RNS class name. */
internal fun nearbyInterfaceLabel(kind: String): String = when (kind) {
    NEARBY_KIND_BLE -> "Bluetooth"
    "tcp" -> "TCP"
    "rnode" -> "RNode"
    "LocalClientInterface" -> "Shared instance"
    // RNS reports a peer found on the local network through a per-peer child interface.
    "AutoInterface", "AutoInterfacePeer" -> "Local network"
    else -> kind
}

/** "Bluetooth · Direct"; the interface is left out when it is not known. */
internal fun nearbyHeardOverLabel(kind: String, hops: Int): String =
    listOf(nearbyInterfaceLabel(kind), hopLabel(hops)).filter { it.isNotBlank() }.joinToString(" · ")

internal fun nearbyReadingLabel(kind: String, hops: Int, heardMillis: Long, nowMillis: Long): String =
    nearbyHeardOverLabel(kind, hops) + " · " + relativeHeardLabel(heardMillis, nowMillis)

/** Keep the newer of each reading so a restored network reading survives a Bluetooth-only update. */
internal fun mergeNearbyPeer(existing: NearbyPeer?, heard: NearbyPeer): NearbyPeer {
    if (existing == null) return heard
    val bleIsNewer = heard.bleHeardMillis >= existing.bleHeardMillis
    val networkIsNewer = heard.networkHeardMillis >= existing.networkHeardMillis
    return existing.copy(
        name = heard.name.ifBlank { existing.name },
        bleHeardMillis = if (bleIsNewer) heard.bleHeardMillis else existing.bleHeardMillis,
        bleHops = if (bleIsNewer && heard.bleHeardMillis > 0) heard.bleHops else existing.bleHops,
        networkHeardMillis = if (networkIsNewer) heard.networkHeardMillis else existing.networkHeardMillis,
        networkHops = if (networkIsNewer && heard.networkHeardMillis > 0) heard.networkHops else existing.networkHops,
        networkKind = if (networkIsNewer && heard.networkHeardMillis > 0) heard.networkKind else existing.networkKind,
    )
}

/** Drop expired readings and keep only the most recently heard network-only people. */
internal fun pruneNearbyPeers(peers: Collection<NearbyPeer>, nowMillis: Long): List<NearbyPeer> {
    val live = peers.mapNotNull { peer ->
        val onBluetooth = peer.heardOnBluetooth(nowMillis)
        val onNetwork = peer.heardOnNetwork(nowMillis)
        if (!onBluetooth && !onNetwork) return@mapNotNull null
        peer.copy(
            bleHeardMillis = if (onBluetooth) peer.bleHeardMillis else 0,
            bleHops = if (onBluetooth) peer.bleHops else 0,
            networkHeardMillis = if (onNetwork) peer.networkHeardMillis else 0,
            networkHops = if (onNetwork) peer.networkHops else 0,
            networkKind = if (onNetwork) peer.networkKind else "",
        )
    }
    val (bluetooth, networkOnly) = live.partition { it.bleHeardMillis > 0 }
    return bluetooth + networkOnly.sortedByDescending { it.networkHeardMillis }.take(NEARBY_NETWORK_LIMIT)
}

/** The contact that already stands for this person, matched by member id or LXMF address. */
internal fun nearbyContactFor(peer: NearbyPeer, knownMembers: List<ChatMember>, contactIds: Set<String>): ChatMember? {
    val memberId = nearbyMemberId(peer)
    return knownMembers.firstOrNull { member ->
        member.id in contactIds &&
            (member.id == memberId || (!peer.isPreview && normalizeLxmfAddress(member.address.orEmpty()) == peer.address))
    }
}

internal fun isNearbyPeerBlocked(peer: NearbyPeer, knownMembers: List<ChatMember>, blockedIds: Set<String>): Boolean {
    if (nearbyMemberId(peer) in blockedIds) return true
    if (peer.isPreview) return false
    return knownMembers.any { it.id in blockedIds && normalizeLxmfAddress(it.address.orEmpty()) == peer.address }
}

internal data class NearbyRow(
    val peer: NearbyPeer,
    /** Saved contact name, else the announced name; blank when the person shared none. */
    val displayName: String,
    val contact: ChatMember?,
    val onBluetooth: Boolean,
    /** First characters of the address, set when another visible row shows the same name. */
    val nameSuffix: String? = null,
) {
    val heardMillis: Long get() = if (onBluetooth) peer.bleHeardMillis else peer.networkHeardMillis
    val hops: Int get() = if (onBluetooth) peer.bleHops else peer.networkHops
    val kind: String get() = if (onBluetooth) NEARBY_KIND_BLE else peer.networkKind
}

internal data class NearbyListing(
    val bluetooth: List<NearbyRow> = emptyList(),
    val network: List<NearbyRow> = emptyList(),
    val networkTruncated: Boolean = false,
) {
    val rows: List<NearbyRow> get() = bluetooth + network
    /** People in Bluetooth range who are not contacts yet; shown as the sidebar badge. */
    val newBluetoothCount: Int get() = bluetooth.count { it.contact == null }
    fun row(address: String): NearbyRow? = rows.firstOrNull { it.peer.address == address }
}

/** Newest first by the minute, then by name with nameless people last, then by address. */
internal fun sortNearbyRows(rows: List<NearbyRow>): List<NearbyRow> = rows.sortedWith(
    compareByDescending<NearbyRow> { it.heardMillis / MINUTE_MILLIS }
        .thenBy { it.displayName.isBlank() }
        .thenBy { it.displayName.lowercase() }
        .thenBy { it.peer.address },
)

internal fun buildNearbyListing(
    peers: List<NearbyPeer>,
    nowMillis: Long,
    knownMembers: List<ChatMember>,
    contactIds: Set<String>,
): NearbyListing {
    val rows = peers.distinctBy { it.address }.mapNotNull { peer ->
        val onBluetooth = peer.heardOnBluetooth(nowMillis)
        if (!onBluetooth && !peer.heardOnNetwork(nowMillis)) return@mapNotNull null
        val contact = nearbyContactFor(peer, knownMembers, contactIds)
        NearbyRow(
            peer = peer,
            displayName = contact?.displayName?.takeIf { it.isNotBlank() } ?: peer.name,
            contact = contact,
            onBluetooth = onBluetooth,
        )
    }
    val bluetooth = sortNearbyRows(rows.filter { it.onBluetooth })
    val allNetwork = sortNearbyRows(rows.filterNot { it.onBluetooth })
    val network = allNetwork.take(NEARBY_NETWORK_LIMIT)
    val visible = bluetooth + network
    val repeatedNames = visible.filter { it.displayName.isNotBlank() }
        .groupingBy { it.displayName }.eachCount().filterValues { it > 1 }.keys
    fun withSuffix(row: NearbyRow) =
        if (row.displayName in repeatedNames) row.copy(nameSuffix = row.peer.address.take(4)) else row
    return NearbyListing(
        bluetooth = bluetooth.map(::withSuffix),
        network = network.map(::withSuffix),
        networkTruncated = allNetwork.size > NEARBY_NETWORK_LIMIT,
    )
}

/** "1 person nearby" / "3 people nearby", read out for the sidebar badge. */
internal fun nearbyBadgeDescription(count: Int): String =
    if (count == 1) "1 person nearby" else "$count people nearby"

internal fun nearbyExpiredNotice(wasOnBluetooth: Boolean, lastHeardMillis: Long, nowMillis: Long): String {
    val lastHeard = relativeHeardLabel(lastHeardMillis, nowMillis)
    return if (wasOnBluetooth) "No longer in range. Last heard $lastHeard." else "Not heard recently. Last heard $lastHeard."
}
