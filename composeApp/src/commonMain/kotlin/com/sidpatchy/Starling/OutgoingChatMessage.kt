package com.sidpatchy.Starling

internal const val MAX_CHAT_MESSAGE_CHARACTERS = 160
internal data class OutgoingChatMessage(val body: String, val isAction: Boolean)

internal fun countChatMessageCharacters(text: String): Int {
    var index = 0
    var count = 0
    while (index < text.length) {
        index += text.codePointWidthAt(index)
        count++
    }
    return count
}

internal fun limitChatMessageLength(text: String): String {
    var index = 0
    var count = 0
    while (index < text.length && count < MAX_CHAT_MESSAGE_CHARACTERS) {
        index += text.codePointWidthAt(index)
        count++
    }
    return text.substring(0, index)
}

internal fun isLocalHelpCommand(text: String): Boolean {
    val command = text.trim().takeWhile { !it.isWhitespace() }.lowercase()
    return command == "/help"
}

internal fun isRrcServerSlashCommand(text: String): Boolean {
    val command = text.trim()
    if (command.length <= 1 || !command.startsWith("/")) return false
    val name = command.drop(1).takeWhile { !it.isWhitespace() }.lowercase()
    return name != "me" && RRC_SLASH_COMMANDS.any { it.name == name }
}

private fun String.codePointWidthAt(index: Int): Int =
    if (this[index].isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) 2 else 1

/** Keep whitespace and IRC-style action parsing consistent in preview and live chat. */
internal fun parseOutgoingChatMessage(text: String): OutgoingChatMessage? {
    val input = text.trim()
    val isAction = input.startsWith("/me ", ignoreCase = true)
    val body = if (isAction) input.drop(4).trimStart() else input
    return if (body.isEmpty() || countChatMessageCharacters(body) > MAX_CHAT_MESSAGE_CHARACTERS) {
        null
    } else {
        OutgoingChatMessage(body, isAction)
    }
}
