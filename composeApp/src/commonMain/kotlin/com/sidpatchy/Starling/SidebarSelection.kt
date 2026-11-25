package com.sidpatchy.Starling

/** Highlight the visible chat without forgetting the channel or DM to return to. */
internal data class SidebarSelection(val roomId: String? = null, val directMessageId: String? = null)

internal fun sidebarSelectionForVisiblePage(
    roomId: String?,
    directMessageId: String?,
    page: MainPage = MainPage.CHAT,
): SidebarSelection = when {
    page != MainPage.CHAT -> SidebarSelection()
    directMessageId != null -> SidebarSelection(directMessageId = directMessageId)
    else -> SidebarSelection(roomId = roomId)
}
