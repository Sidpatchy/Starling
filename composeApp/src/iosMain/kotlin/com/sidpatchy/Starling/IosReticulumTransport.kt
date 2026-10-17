package com.sidpatchy.Starling

import kotlinx.coroutines.Dispatchers

/** RRC and LXMF control path over the embedded iOS Reticulum runtime. */
class IosReticulumTransport private constructor(
    private val bridge: BridgeReticulumTransport,
) : ReticulumTransport by bridge {
    constructor(nodeController: IosReticulumNodeController) : this(
        BridgeReticulumTransport(
            nodeState = nodeController.state,
            bridgeEvents = nodeController.bridgeEvents,
            sendCommand = { command -> nodeController.sendBridgeCommand(command) },
            dispatcher = Dispatchers.Default,
        ),
    )

    suspend fun close() = bridge.close()
}
