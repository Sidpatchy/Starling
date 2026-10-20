package com.sidpatchy.Starling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A single configured TCP peer and its observed online state.
 *
 * A peer is "online" when its `host:port` id appears in the running node's
 * `onlineTcpPeerIds`; otherwise it is retrying or not yet reachable.
 */
data class TcpPeerDiagnostics(
    val host: String,
    val port: Int,
    val online: Boolean,
) {
    val label: String = "$host:$port"
}

/**
 * A single configured RNode radio and its observed online state.
 *
 * A radio is "online" when its connection id appears in the running node's
 * `onlineRNodePorts`; otherwise it is retrying or not yet reachable.
 */
data class RNodeRadioDiagnostics(
    val port: String,
    val frequencyHz: Long,
    val bandwidthHz: Int,
    val txPowerDbm: Int,
    val spreadingFactor: Int,
    val codingRate: Int,
    val online: Boolean,
)

/**
 * How strongly a diagnostics item calls for the user's attention.
 *
 * Kept free of theme colors so the pure logic below can be tested without a
 * CompositionLocal theme; the dialog maps these to theme tokens.
 */
enum class DiagnosticsSeverity {
    /** Everything expected is online (green). */
    OK,
    /** Something is in progress or not yet online (amber). */
    Warning,
    /** Something failed (red). */
    Error,
    /** Neutral informational state (muted). */
    Neutral,
}

/**
 * Aggregated, presentable network diagnostics for one inspected context.
 *
 * Produced by [buildNetworkDiagnostics] from the live state flows. Preview mode
 * suppresses all live data.
 */
data class NetworkDiagnostics(
    val previewMode: Boolean,
    val nodeLabel: String,
    val nodeSeverity: DiagnosticsSeverity,
    val interfaceSummary: String?,
    val tcpPeers: List<TcpPeerDiagnostics>,
    val rnodeRadios: List<RNodeRadioDiagnostics>,
    val rrcLabel: String,
    val rrcSeverity: DiagnosticsSeverity,
    val rrcHubName: String?,
    val rrcDestination: String?,
    val rrcError: String?,
    val deliveryAddress: String?,
)

private val ConnectedGreen = Color(0xFF48A868)
private val ConnectingAmber = Color(0xFFE1A448)
private val FailedRed = Color(0xFFC44545)

/**
 * Builds presentable network diagnostics from the live node, RRC, and network
 * state. Pure and side-effect free so it can be unit tested without a theme or
 * a running runtime.
 */
fun buildNetworkDiagnostics(
    nodeState: ReticulumNodeState,
    rrcState: ReticulumConnectionState,
    networks: List<ChatNetwork>,
    activeHubNetworkId: String?,
    localDeliveryAddress: String?,
    previewMode: Boolean,
): NetworkDiagnostics {
    if (previewMode) {
        return NetworkDiagnostics(
            previewMode = true,
            nodeLabel = "Hidden in Local preview",
            nodeSeverity = DiagnosticsSeverity.Neutral,
            interfaceSummary = null,
            tcpPeers = emptyList(),
            rnodeRadios = emptyList(),
            rrcLabel = "Hidden in Local preview",
            rrcSeverity = DiagnosticsSeverity.Neutral,
            rrcHubName = null,
            rrcDestination = null,
            rrcError = null,
            deliveryAddress = null,
        )
    }

    val nodeDiagnostics = when (val state = nodeState) {
        is ReticulumNodeState.Stopped -> NodeDiagnostics("Stopped", DiagnosticsSeverity.Neutral)
        is ReticulumNodeState.Starting -> NodeDiagnostics("Starting Reticulum", DiagnosticsSeverity.Warning)
        is ReticulumNodeState.PreparingRuntime ->
            NodeDiagnostics("Preparing runtime: ${state.detail}", DiagnosticsSeverity.Warning)
        is ReticulumNodeState.Running -> {
            val tcpOnline = state.onlineTcpPeerIds.size
            val tcpTotal = state.tcpPeers.size
            val rnodeOnline = state.onlineRNodePorts.size
            val rnodeTotal = state.rnodeInterfaces.size
            val parts = mutableListOf<String>()
            if (tcpTotal > 0) {
                parts.add("$tcpOnline of $tcpTotal TCP connections online")
            }
            if (rnodeTotal > 0) {
                parts.add("$rnodeOnline of $rnodeTotal RNode radios online")
            }
            if (state.sharedInstanceConnected) {
                parts.add("Shared RNS instance connected")
            }
            val summary = parts.joinToString("; ").takeIf { it.isNotBlank() }
                ?: "Running without a configured interface."
            NodeDiagnostics(
                "Running",
                DiagnosticsSeverity.OK,
                interfaceSummary = summary,
                tcpPeers = state.tcpPeers.map { peer ->
                    TcpPeerDiagnostics(peer.host, peer.port, peer.id in state.onlineTcpPeerIds)
                },
                rnodeRadios = state.rnodeInterfaces.map { radio ->
                    RNodeRadioDiagnostics(
                        port = radio.port,
                        frequencyHz = radio.frequencyHz,
                        bandwidthHz = radio.bandwidthHz,
                        txPowerDbm = radio.txPowerDbm,
                        spreadingFactor = radio.spreadingFactor,
                        codingRate = radio.codingRate,
                        online = radio.id in state.onlineRNodePorts,
                    )
                },
            )
        }
        is ReticulumNodeState.Failed -> NodeDiagnostics("Failed: ${state.message}", DiagnosticsSeverity.Error)
        is ReticulumNodeState.Unavailable ->
            NodeDiagnostics("Unavailable: ${state.reason}", DiagnosticsSeverity.Neutral)
    }

    val inspectedNetwork = networks.firstOrNull { it.id == activeHubNetworkId }

    val rrcDiagnostics = when (rrcState) {
        ReticulumConnectionState.Disconnected -> RrcDiagnostics("Disconnected", DiagnosticsSeverity.Neutral)
        ReticulumConnectionState.Starting -> RrcDiagnostics("Connecting", DiagnosticsSeverity.Warning)
        ReticulumConnectionState.Connecting -> RrcDiagnostics("Connecting", DiagnosticsSeverity.Warning)
        is ReticulumConnectionState.Connected ->
            RrcDiagnostics(
                "Connected${rrcState.hubName?.takeIf { it.isNotBlank() }?.let { " to $it" }.orEmpty()}",
                DiagnosticsSeverity.OK,
                hubName = rrcState.hubName?.takeIf { it.isNotBlank() },
            )
        is ReticulumConnectionState.Failed ->
            RrcDiagnostics("Failed: ${rrcState.message}", DiagnosticsSeverity.Error, error = rrcState.message)
    }

    return NetworkDiagnostics(
        previewMode = false,
        nodeLabel = nodeDiagnostics.label,
        nodeSeverity = nodeDiagnostics.severity,
        interfaceSummary = nodeDiagnostics.interfaceSummary,
        tcpPeers = nodeDiagnostics.tcpPeers,
        rnodeRadios = nodeDiagnostics.rnodeRadios,
        rrcLabel = rrcDiagnostics.label,
        rrcSeverity = rrcDiagnostics.severity,
        rrcHubName = rrcDiagnostics.hubName,
        rrcDestination = inspectedNetwork?.hubDestination,
        rrcError = rrcDiagnostics.error,
        deliveryAddress = localDeliveryAddress,
    )
}

private data class NodeDiagnostics(
    val label: String,
    val severity: DiagnosticsSeverity,
    val interfaceSummary: String? = null,
    val tcpPeers: List<TcpPeerDiagnostics> = emptyList(),
    val rnodeRadios: List<RNodeRadioDiagnostics> = emptyList(),
)

private data class RrcDiagnostics(
    val label: String,
    val severity: DiagnosticsSeverity,
    val hubName: String? = null,
    val error: String? = null,
)

/**
 * Maps a [DiagnosticsSeverity] to a theme-appropriate color. Called only from
 * composition, so it can read the theme tokens.
 */
internal fun severityColor(severity: DiagnosticsSeverity, ink: Color): Color = when (severity) {
    DiagnosticsSeverity.OK -> ConnectedGreen
    DiagnosticsSeverity.Warning -> ConnectingAmber
    DiagnosticsSeverity.Error -> FailedRed
    DiagnosticsSeverity.Neutral -> ink
}

/**
 * Renders a detailed network diagnostics dialog: node state, per-interface
 * online/retrying status, RRC hub state, and the local delivery address.
 *
 * When [network] is null the diagnostics describe the active hub (if any). When
 * [network] is given, its RRC destination is shown and the hub state is reported
 * relative to whether that network is the active connection.
 */
@Composable
internal fun NetworkDiagnosticsDialog(
    network: ChatNetwork?,
    nodeState: ReticulumNodeState,
    rrcState: ReticulumConnectionState,
    activeHubNetworkId: String?,
    localDeliveryAddress: String?,
    previewMode: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onReconnect: (() -> Unit)? = null,
    isRetrying: Boolean = false,
) {
    val diagnostics = buildNetworkDiagnostics(
        nodeState = nodeState,
        rrcState = rrcState,
        networks = emptyList(),
        activeHubNetworkId = activeHubNetworkId,
        localDeliveryAddress = localDeliveryAddress,
        previewMode = previewMode,
    )
    val palette = LocalStarlingPalette.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Network diagnostics", color = palette.ink, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Node")
                StatusRow(
                    label = diagnostics.nodeLabel,
                    severity = diagnostics.nodeSeverity,
                    color = palette,
                )
                diagnostics.interfaceSummary?.let { summary ->
                    Spacer(Modifier.height(4.dp))
                    Text(summary, color = palette.muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
                if (diagnostics.tcpPeers.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    SectionTitle("TCP peers")
                    diagnostics.tcpPeers.forEach { peer ->
                        PeerRow(
                            label = peer.label,
                            online = peer.online,
                            color = palette,
                        )
                    }
                }
                if (diagnostics.rnodeRadios.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    SectionTitle("RNode radios")
                    diagnostics.rnodeRadios.forEach { radio ->
                        RadioRow(
                            label = radio.port,
                            detail = "${radio.frequencyHz / 1_000_000} MHz · ${radio.bandwidthHz / 1000} kHz BW · SF${radio.spreadingFactor} · CR${radio.codingRate} · ${radio.txPowerDbm} dBm",
                            online = radio.online,
                            color = palette,
                        )
                    }
                }
                SectionTitle("RRC hub")
                StatusRow(
                    label = diagnostics.rrcLabel,
                    severity = diagnostics.rrcSeverity,
                    color = palette,
                )
                diagnostics.rrcHubName?.let {
                    Text("Hub: $it", color = palette.muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
                diagnostics.rrcDestination?.let { destination ->
                    Text("Destination: $destination", color = palette.muted, fontSize = 11.sp)
                    SelectionContainer {
                        Text(destination, color = palette.muted, fontSize = 11.sp)
                    }
                }
                diagnostics.rrcError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = palette.errorText, fontSize = 11.sp, lineHeight = 16.sp)
                }
                if (!previewMode) {
                    diagnostics.deliveryAddress?.let { address ->
                        SectionTitle("Identity")
                        Text("Delivery address", color = palette.muted, fontSize = 11.sp)
                        SelectionContainer {
                            Text(address, color = palette.muted, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onRefresh,
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh status",
                                tint = palette.accent,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Refresh status", color = palette.accent, fontSize = 12.sp)
                        }
                    }
                    val canReconnect = onReconnect != null &&
                        (rrcState is ReticulumConnectionState.Failed || rrcState == ReticulumConnectionState.Disconnected)
                    if (canReconnect) {
                        TextButton(
                            onClick = onReconnect,
                            enabled = !isRetrying,
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) {
                            Text(
                                if (isRetrying) "Reconnecting…" else "Reconnect",
                                color = if (isRetrying) palette.muted else palette.accent,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = palette.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = palette.muted) }
        },
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        color = LocalStarlingPalette.current.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun StatusRow(label: String, severity: DiagnosticsSeverity, color: StarlingPalette) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(severityColor(severity, color.ink)),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = severityColor(severity, color.ink), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PeerRow(label: String, online: Boolean, color: StarlingPalette) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(if (online) ConnectedGreen else FailedRed),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = color.ink, fontSize = 12.sp)
            Text(if (online) "Online" else "Not responding", color = color.muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun RadioRow(label: String, detail: String, online: Boolean, color: StarlingPalette) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(if (online) ConnectedGreen else FailedRed),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = color.ink, fontSize = 12.sp)
            Text(detail, color = color.muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}
