package dev.starling

internal data class OutgoingChatMessage(val body: String, val isAction: Boolean)

/** Keep whitespace and IRC-style action parsing consistent in preview and live chat. */
internal fun parseOutgoingChatMessage(text: String): OutgoingChatMessage? {
    val input = text.trim()
    val isAction = input.startsWith("/me ", ignoreCase = true)
    val body = if (isAction) input.drop(4).trimStart() else input
    return if (body.isEmpty()) null else OutgoingChatMessage(body, isAction)
}
