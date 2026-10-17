package com.sidpatchy.Starling

import kotlinx.coroutines.Dispatchers

/** RRC and LXMF client adapter backed by the desktop Python/RNS sidecar. */
class DesktopReticulumTransport private constructor(
    private val bridge: BridgeReticulumTransport,
) : ReticulumTransport by bridge {
    constructor(nodeController: DesktopReticulumNodeController) : this(
        BridgeReticulumTransport(
            nodeState = nodeController.state,
            bridgeEvents = nodeController.bridgeEvents,
            sendCommand = { command -> nodeController.sendBridgeCommand(command) },
            dispatcher = Dispatchers.IO,
        ),
    )

    suspend fun close() = bridge.close()
}
