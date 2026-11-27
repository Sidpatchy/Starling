package com.sidpatchy.Starling

/** Highlight the visible chat without forgetting the channel or DM to return to. */
internal data class SidebarSelection(val roomId: String? = null, val directMessageId: String? = null)

internal enum class ChatSidebarSection(val label: String) {
    CHANNELS("Channels"),
    DIRECT_MESSAGES("DMs"),
}

internal fun defaultChatSidebarSection(selection: SidebarSelection, page: MainPage): ChatSidebarSection =
    if (selection.directMessageId != null || page == MainPage.NEARBY || page == MainPage.CONTACT_REQUESTS) {
        ChatSidebarSection.DIRECT_MESSAGES
    } else {
        ChatSidebarSection.CHANNELS
    }

/** Use the current channel's network before falling back to the connected hub. */
internal fun channelActionNetwork(
    networks: List<ChatNetwork>,
    currentRoom: ChatRoom?,
    activeHubNetworkId: String?,
): ChatNetwork? =
    networks.firstOrNull { it.id == currentRoom?.networkId }
        ?: networks.firstOrNull { it.id == activeHubNetworkId }
        ?: networks.firstOrNull()

internal fun toggleSidebarNetwork(collapsedIds: Set<String>, networkId: String): Set<String> =
    if (networkId in collapsedIds) collapsedIds - networkId else collapsedIds + networkId

internal fun sidebarSelectionForVisiblePage(
    roomId: String?,
    directMessageId: String?,
    page: MainPage = MainPage.CHAT,
): SidebarSelection = when {
    page != MainPage.CHAT -> SidebarSelection()
    directMessageId != null -> SidebarSelection(directMessageId = directMessageId)
    else -> SidebarSelection(roomId = roomId)
}
