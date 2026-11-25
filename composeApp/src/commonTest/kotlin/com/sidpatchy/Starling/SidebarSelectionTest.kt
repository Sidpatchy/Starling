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
            assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage("general", directMessageId, MainPage.NEARBY))
        }
        assertEquals(SidebarSelection(roomId = "general"), sidebarSelectionForVisiblePage("general", null))
        assertEquals(SidebarSelection(directMessageId = "moss"), sidebarSelectionForVisiblePage("general", "moss"))
        assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage(null, null, MainPage.NEARBY))
    }

    @Test
    fun settingsAndRequestsHideBothChatHighlights() {
        for (directMessageId in listOf(null, "moss")) {
            assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage("general", directMessageId, MainPage.SETTINGS))
            assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage("general", directMessageId, MainPage.CONTACT_REQUESTS))
        }
    }

    @Test
    fun returningToChatRestoresTheRememberedChannelHighlight() {
        val rememberedRoom = "field-notes"
        assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage(rememberedRoom, null, MainPage.SETTINGS))
        assertEquals(SidebarSelection(roomId = rememberedRoom), sidebarSelectionForVisiblePage(rememberedRoom, null))
    }

    @Test
    fun returningFromSettingsToADirectMessageRestoresOnlyTheDirectMessageHighlight() {
        assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage("general", "moss", MainPage.SETTINGS))
        assertEquals(SidebarSelection(directMessageId = "moss"), sidebarSelectionForVisiblePage("general", "moss"))
    }

    @Test
    fun anEmptyChatDoesNotHighlightAChannel() {
        assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage(null, null))
    }
}
