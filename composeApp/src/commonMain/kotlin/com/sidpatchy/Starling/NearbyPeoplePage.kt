package com.sidpatchy.Starling

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val CONFIRMATION_MILLIS = 2_000L

/** Sends an LXMF announce and confirms it in place, since the app has no snackbar. */
@Composable
internal fun AnnounceButton(enabled: Boolean, onAnnounce: () -> Unit, modifier: Modifier = Modifier) {
    var announced by remember { mutableStateOf(false) }
    LaunchedEffect(announced) {
        if (announced) {
            delay(CONFIRMATION_MILLIS)
            announced = false
        }
    }
    TextButton(
        onClick = {
            onAnnounce()
            announced = true
        },
        enabled = enabled && !announced,
        modifier = modifier,
    ) {
        Text(if (announced) "Announced" else "Announce", color = if (enabled && !announced) Accent else Muted)
    }
}

@Composable
internal fun NearbyPeoplePage(
    listing: NearbyListing,
    nowMillis: Long,
    previewMode: Boolean,
    nodeRunning: Boolean,
    supportsBlePeering: Boolean,
    blePeeringEnabled: Boolean,
    onAnnounce: () -> Unit,
    onAdd: (NearbyRow) -> Unit,
    onDetails: (NearbyRow) -> Unit,
    onOpenSettings: () -> Unit,
    compact: Boolean = false,
    showNavigationButton: Boolean = false,
    onOpenNavigation: () -> Unit = {},
) {
    val sectionsVisible = previewMode || nodeRunning
    Column(Modifier.fillMaxSize().background(Canvas)) {
        Row(
            Modifier.fillMaxWidth().height(68.dp).background(Paper)
                .padding(horizontal = if (compact) 8.dp else 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact || showNavigationButton) {
                NavigationToggle(onClick = onOpenNavigation)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                "Nearby people",
                modifier = Modifier.weight(1f),
                color = Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sectionsVisible && listing.rows.isNotEmpty()) {
                Text(listing.rows.size.toString(), color = Muted, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
            }
            if (!previewMode) AnnounceButton(enabled = nodeRunning, onAnnounce = onAnnounce)
        }
        if (previewMode) PreviewNotice("Sample people. Nothing here was heard on a real network.")
        if (!sectionsVisible) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                NearbyHint("Reticulum isn’t running. Start it from Identity or Settings to hear who’s around.")
            }
        } else {
            // Sample people include Bluetooth ones on every platform.
            val showBluetooth = previewMode || supportsBlePeering
            val bluetoothOn = previewMode || blePeeringEnabled
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).preferredFrameRate(120f),
                contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (showBluetooth) {
                    item(key = "section-bluetooth") {
                        NearbySectionHeader(
                            "NEARBY (BLUETOOTH)",
                            listing.bluetooth.size.takeIf { bluetoothOn && it > 0 }?.toString(),
                        )
                    }
                    when {
                        !bluetoothOn -> item(key = "bluetooth-off") {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                NearbyHint("Bluetooth peers are off. Turn them on in Settings to see people close by.")
                                TextButton(onClick = onOpenSettings) { Text("Open settings", color = Accent) }
                            }
                        }
                        listing.bluetooth.isEmpty() -> item(key = "bluetooth-empty") {
                            NearbyHint("No one in Bluetooth range yet.")
                        }
                        else -> items(listing.bluetooth, key = { it.peer.address }) { row ->
                            NearbyPeerRow(row, nowMillis, onAdd = { onAdd(row) }, onDetails = { onDetails(row) })
                        }
                    }
                    item(key = "section-gap") { Spacer(Modifier.height(14.dp)) }
                }
                item(key = "section-network") {
                    NearbySectionHeader(
                        "NETWORK",
                        when {
                            listing.networkTruncated -> "most recent $NEARBY_NETWORK_LIMIT"
                            listing.network.isNotEmpty() -> listing.network.size.toString()
                            else -> null
                        },
                    )
                }
                if (listing.network.isEmpty()) {
                    item(key = "network-empty") {
                        NearbyHint("No one has announced on your networks yet. People appear here when they come online.")
                    }
                } else {
                    items(listing.network, key = { it.peer.address }) { row ->
                        NearbyPeerRow(row, nowMillis, onAdd = { onAdd(row) }, onDetails = { onDetails(row) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyHint(message: String) {
    Text(
        message,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp),
        color = Muted,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun NearbySectionHeader(label: String, count: String?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 5.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = Muted,
            fontSize = 10.sp,
            letterSpacing = 1.3.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (count != null) Text(count, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun NearbyAvatar(displayName: String, size: Dp) {
    if (displayName.isNotBlank()) {
        MemberAvatar(ChatMember("", displayName), size = size)
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(Line), contentAlignment = Alignment.Center) {
            Text("?", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun NearbyPeerRow(row: NearbyRow, nowMillis: Long, onAdd: () -> Unit, onDetails: () -> Unit) {
    val darkTheme = LocalStarlingPalette.current == DarkPalette
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                .pointerInput(onDetails) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                event.changes.forEach { it.consume() }
                                onDetails()
                            }
                        }
                    }
                }
                .heightIn(min = 48.dp)
                .combinedClickable(role = Role.Button, onClick = onDetails, onLongClick = onDetails)
                .padding(horizontal = 5.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NearbyAvatar(row.displayName, size = 30.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.displayName.isNotBlank()) {
                        Text(
                            row.displayName,
                            modifier = Modifier.weight(1f, fill = false),
                            color = ircNickTextColor(row.displayName, darkTheme),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(row.peer.shortAddress, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                    row.nameSuffix?.let { Text(" · $it", color = Muted, fontSize = 13.sp, maxLines = 1) }
                }
                Text(
                    nearbyReadingLabel(row.kind, row.hops, row.heardMillis, nowMillis),
                    color = Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (row.contact != null) {
            Text(
                "Contact",
                modifier = Modifier.padding(horizontal = 12.dp),
                color = Muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        } else {
            TextButton(onClick = onAdd) { Text("Add", color = Accent) }
        }
    }
}

@Composable
private fun NearbyField(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun NearbyFieldValue(value: String) {
    Text(value, color = Ink, fontSize = 13.sp)
}

/**
 * Details for one person. [row] is the live row while the person is still listed;
 * once [expired] it is the snapshot taken when the dialog was opened.
 */
@Composable
internal fun NearbyPeerDialog(
    row: NearbyRow,
    expired: Boolean,
    nowMillis: Long,
    justAdded: Boolean,
    onAdd: () -> Unit,
    onMessage: (ChatMember) -> Unit,
    onDismiss: () -> Unit,
) {
    val peer = row.peer
    val darkTheme = LocalStarlingPalette.current == DarkPalette
    val timeZonePreference = LocalTimestampTimeZonePreference.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(CONFIRMATION_MILLIS)
            copied = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NearbyAvatar(row.displayName, size = 38.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    val status = when {
                        row.contact != null -> "Contact"
                        row.onBluetooth -> "Heard nearby"
                        else -> "Heard on your network"
                    }
                    if (row.displayName.isNotBlank()) {
                        Text(row.displayName, color = ircNickTextColor(row.displayName, darkTheme), fontWeight = FontWeight.SemiBold)
                        Text(status, color = Muted, fontSize = 11.sp)
                    } else {
                        Text(peer.shortAddress, color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("$status · No display name", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NearbyField("ADDRESS") {
                    SelectionContainer {
                        Text(peer.address, color = Ink, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(peer.address))
                            copied = true
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) { Text(if (copied) "Copied" else "Copy address", color = Accent) }
                }
                if (expired) {
                    NearbyFieldValue(nearbyExpiredNotice(row.onBluetooth, row.heardMillis, nowMillis))
                } else {
                    NearbyField("HEARD OVER") { NearbyFieldValue(nearbyHeardOverLabel(row.kind, row.hops)) }
                    NearbyField("LAST HEARD") {
                        NearbyFieldValue(
                            relativeHeardLabel(row.heardMillis, nowMillis) +
                                " (" + formatChatTimestamp(row.heardMillis, timeZonePreference) + ")",
                        )
                    }
                    if (row.onBluetooth && peer.heardOnNetwork(nowMillis)) {
                        NearbyField("ALSO HEARD OVER NETWORK") {
                            NearbyFieldValue(
                                nearbyReadingLabel(peer.networkKind, peer.networkHops, peer.networkHeardMillis, nowMillis),
                            )
                        }
                    }
                }
                if (row.contact != null && peer.name.isNotBlank() && peer.name != row.contact.displayName) {
                    NearbyField("ANNOUNCES AS") { NearbyFieldValue(peer.name) }
                }
                if (justAdded && row.contact != null) NearbyFieldValue("Added to your contacts.")
                Text("Names are chosen by the sender and aren’t verified.", color = Muted, fontSize = 10.sp)
            }
        },
        confirmButton = {
            val contact = row.contact
            if (contact != null) {
                TextButton(onClick = { onMessage(contact) }) { Text("Message", color = Accent) }
            } else {
                TextButton(onClick = onAdd) { Text("Add contact", color = Accent) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Muted) } },
    )
}
