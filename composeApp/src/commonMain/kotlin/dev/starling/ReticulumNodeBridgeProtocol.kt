package dev.starling

/** Parse node status without coupling the wire format to a platform's node lifecycle. */
internal fun parseReticulumNodeBridgeState(
    line: String,
    requestedConfig: ReticulumNodeConfig,
    defaultFailureMessage: String = "The RNS runtime failed.",
    decodeField: (String) -> String = { it.decodeBridgeBase64() },
): ReticulumNodeState? {
    val fields = line.split('\t')
    return when {
        fields.firstOrNull() == "ERROR" -> ReticulumNodeState.Failed(
            fields.getOrNull(1)?.let(decodeField).orEmpty().ifBlank { defaultFailureMessage },
        )
        fields.firstOrNull() == "STOPPED" -> ReticulumNodeState.Stopped
        (fields.firstOrNull() == "READY" || fields.firstOrNull() == "STATUS") && fields.getOrNull(1) == "RUNNING" -> {
            val mode = runCatching { ReticulumRuntimeMode.valueOf(fields.getOrNull(2).orEmpty()) }
                .getOrDefault(requestedConfig.mode)
            val onlineIds = fields.getOrNull(3)?.let(decodeField).orEmpty()
                .split(';')
                .mapNotNull { peerStatus ->
                    val statusFields = peerStatus.split(',')
                    val host = statusFields.getOrNull(0)?.let(decodeField).orEmpty()
                    val port = statusFields.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    if (statusFields.getOrNull(2) == "1") "${host.lowercase()}:$port" else null
                }
                .toSet()
            val onlineRNodePorts = fields.getOrNull(6)?.let(decodeField).orEmpty()
                .split(';')
                .mapNotNull { radioStatus ->
                    val statusFields = radioStatus.split(',')
                    val port = statusFields.getOrNull(0)?.let(decodeField).orEmpty()
                    port.takeIf { it.isNotBlank() && statusFields.getOrNull(1) == "1" }?.lowercase()
                }
                .toSet()
            ReticulumNodeState.Running(
                mode = mode,
                tcpPeers = requestedConfig.tcpPeers,
                onlineTcpPeerIds = onlineIds,
                sharedInstanceConnected = fields.getOrNull(5) == "1",
                rnodeInterfaces = requestedConfig.rnodeInterfaces,
                onlineRNodePorts = onlineRNodePorts,
            )
        }
        fields.firstOrNull() == "READY" && fields.getOrNull(1) == "STOPPED" ->
            ReticulumNodeState.Failed("Reticulum started without a running shared instance.")
        else -> null
    }
}
