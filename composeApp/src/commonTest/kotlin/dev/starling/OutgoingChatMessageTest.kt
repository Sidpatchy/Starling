package dev.starling

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutgoingChatMessageTest {
    @Test
    fun blankInputDoesNotProduceAMessage() {
        for (input in listOf("", " ", "\t\r\n", "\u00A0")) {
            assertNull(parseOutgoingChatMessage(input))
        }
    }

    @Test
    fun ordinaryMessagesTrimEdgesButKeepInternalWhitespaceAndUnicode() {
        assertEquals(
            OutgoingChatMessage("Hello 🌍\nsecond\tline", isAction = false),
            parseOutgoingChatMessage(" \tHello 🌍\nsecond\tline\n "),
        )
    }

    @Test
    fun actionsAreCaseInsensitiveAndTrimWhitespaceAfterTheCommand() {
        assertEquals(
            OutgoingChatMessage("waves 🌍", isAction = true),
            parseOutgoingChatMessage(" \t/ME   waves 🌍\n "),
        )
    }

    @Test
    fun onlyTheMeCommandFollowedByASpaceIsAnAction() {
        for (input in listOf("/me", "/me   ", "/me\twaves", "/me\nwaves", "/message hello", "/unknown")) {
            assertEquals(OutgoingChatMessage(input.trim(), isAction = false), parseOutgoingChatMessage(input))
        }
    }

    @Test
    fun previewRoomsAndDirectMessagesUseTheSameParsing() = runBlocking {
        val repository = LocalPreviewChatRepository()
        val roomId = repository.rooms.value.first().id
        val messageCount = repository.messages(roomId).first().size

        repository.send(roomId, " \t\n ")
        repository.sendDirectMessage("moss", " \t\n ")
        assertEquals(messageCount, repository.messages(roomId).first().size)
        assertTrue(repository.directMessages("moss").first().isEmpty())

        repository.send(roomId, " /ME   waves 🌍 ")
        repository.sendDirectMessage("moss", " /ME   waves 🌍 ")

        val roomMessage = repository.messages(roomId).first().last()
        val directMessage = repository.directMessages("moss").first().single()
        assertEquals("waves 🌍", roomMessage.text)
        assertEquals(roomMessage.text, directMessage.text)
        assertTrue(roomMessage.isAction)
        assertTrue(directMessage.isAction)
    }
}
