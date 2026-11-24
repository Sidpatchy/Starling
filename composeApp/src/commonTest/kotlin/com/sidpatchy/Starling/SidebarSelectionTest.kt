package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertEquals

class SidebarSelectionTest {
    @Test
    fun viewingAChannelHighlightsOnlyThatChannel() {
        assertEquals(SidebarSelection(roomId = "general"), sidebarSelectionForVisiblePage("general", null))
    }

    @Test
    fun viewingADirectMessageDoesNotHighlightTheRememberedChannel() {
        assertEquals(SidebarSelection(directMessageId = "moss"), sidebarSelectionForVisiblePage("general", "moss"))
    }

    @Test
    fun nearbyPeopleHidesBothChatHighlightsAndRestoresThemAfterwards() {
        for (directMessageId in listOf(null, "moss")) {
            assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage("general", directMessageId, showNearby = true))
        }
        assertEquals(SidebarSelection(roomId = "general"), sidebarSelectionForVisiblePage("general", null, showNearby = false))
        assertEquals(SidebarSelection(directMessageId = "moss"), sidebarSelectionForVisiblePage("general", "moss"))
        assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage(null, null, showNearby = true))
    }

    @Test
    fun settingsIdentityAndRequestsHideBothChatHighlights() {
        for (directMessageId in listOf(null, "moss")) {
            assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage("general", directMessageId, showSettings = true))
            assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage("general", directMessageId, showIdentityPage = true))
            assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage("general", directMessageId, showContactRequests = true))
        }
    }

    @Test
    fun returningToChatRestoresTheRememberedChannelHighlight() {
        val rememberedRoom = "field-notes"
        assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage(rememberedRoom, null, showIdentityPage = true))
        assertEquals(SidebarSelection(roomId = rememberedRoom), sidebarSelectionForVisiblePage(rememberedRoom, null))
    }

    @Test
    fun returningFromSettingsToADirectMessageRestoresOnlyTheDirectMessageHighlight() {
        assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage("general", "moss", showSettings = true))
        assertEquals(SidebarSelection(directMessageId = "moss"), sidebarSelectionForVisiblePage("general", "moss"))
    }

    @Test
    fun anEmptyChatDoesNotHighlightAChannel() {
        assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage(null, null))
    }
}
