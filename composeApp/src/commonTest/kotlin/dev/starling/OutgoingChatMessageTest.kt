package dev.starling

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun messageLengthCountsUnicodeCodePointsAndTruncatesWithoutSplittingSurrogates() {
        val draft = "a".repeat(MAX_CHAT_MESSAGE_CHARACTERS - 1) + "🌍x"
        val limitedDraft = limitChatMessageLength(draft)

        assertEquals(MAX_CHAT_MESSAGE_CHARACTERS + 1, countChatMessageCharacters(draft))
        assertEquals(MAX_CHAT_MESSAGE_CHARACTERS, countChatMessageCharacters(limitedDraft))
        assertEquals("a".repeat(MAX_CHAT_MESSAGE_CHARACTERS - 1) + "🌍", limitedDraft)
        assertEquals(limitedDraft, parseOutgoingChatMessage(limitedDraft)?.body)
        assertNull(parseOutgoingChatMessage(draft))
    }

    @Test
    fun helpIsAClientCommandAndPreviewShowsTheAvailableHubCommands() = runBlocking {
        val repository = LocalPreviewChatRepository()
        val roomId = repository.rooms.value.first().id

        repository.send(roomId, "/HELP")

        val help = repository.messages(roomId).first().last()
        assertTrue(help.isSystem)
        assertTrue(help.text.contains("/list"))
        assertTrue(help.text.contains("/topic"))
    }

    @Test
    fun slashCommandSuggestionsFilterAndCompleteWithoutRemovingArguments() {
        assertEquals(listOf("mode"), slashCommandSuggestions("/mo").map { it.name })
        assertEquals(listOf("mode"), slashCommandSuggestions("/mode general +m").map { it.name })
        assertEquals("/mode ", completeSlashCommand("/mo", RRC_SLASH_COMMANDS.first { it.name == "mode" }))
        assertEquals("/stats ", completeSlashCommand("/stats", RRC_SLASH_COMMANDS.first { it.name == "stats" }))
        assertEquals("/mode general +m", completeSlashCommand("/mode general +m", RRC_SLASH_COMMANDS.first { it.name == "mode" }))
        assertTrue(slashCommandSuggestions("hello").isEmpty())
        assertTrue(slashCommandSuggestions("/not-a-command").isEmpty())
        assertTrue(isRrcServerSlashCommand("/stats"))
        assertFalse(isRrcServerSlashCommand("/not-a-command"))
        assertTrue(isUnrecognizedRrcCommandReply("Server says: Unrecognized command /foo"))
        assertFalse(isUnrecognizedRrcCommandReply("/help returned 12 available commands"))
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
