package dev.starling

internal data class RrcSlashCommand(
    val name: String,
    val usage: String,
    val description: String,
)

internal val RRC_SLASH_COMMANDS = listOf(
    RrcSlashCommand("help", "/help", "Ask the current hub for its command list."),
    RrcSlashCommand("me", "/me <action>", "Send an action to the room."),
    RrcSlashCommand("list", "/list", "List registered public rooms."),
    RrcSlashCommand("who", "/who [room]", "List members in a room."),
    RrcSlashCommand("names", "/names [room]", "Alias for /who."),
    RrcSlashCommand("topic", "/topic <room> [text]", "Show or change a room topic."),
    RrcSlashCommand("mode", "/mode <room> <flag> [value]", "View or change room modes; see /help for flags."),
    RrcSlashCommand("register", "/register <room>", "Register a room."),
    RrcSlashCommand("unregister", "/unregister <room>", "Unregister a room."),
    RrcSlashCommand("kick", "/kick <room> <nick|hash>", "Remove a member from a room."),
    RrcSlashCommand("ban", "/ban <room> <add|del|list> [nick|hash]", "Manage room bans."),
    RrcSlashCommand("invite", "/invite <room> <add|del|list> [nick|hash]", "Manage room invites."),
    RrcSlashCommand("op", "/op <room> <nick|hash>", "Grant room operator status."),
    RrcSlashCommand("deop", "/deop <room> <nick|hash>", "Remove room operator status."),
    RrcSlashCommand("voice", "/voice <room> <nick|hash>", "Grant voice in a moderated room."),
    RrcSlashCommand("devoice", "/devoice <room> <nick|hash>", "Remove voice in a moderated room."),
    RrcSlashCommand("stats", "/stats", "Show server statistics (operator only)."),
    RrcSlashCommand("reload", "/reload", "Reload hub configuration (operator only)."),
    RrcSlashCommand("kline", "/kline <add|del|list> [identity]", "Manage global bans (operator only)."),
)

internal val RRC_COMMAND_HELP = buildString {
    appendLine("Starling commands:")
    appendLine("/help — show this guide in preview, or ask the current hub for its command list in live chat")
    appendLine("/me <action> — send an action to the room")
    appendLine()
    appendLine("Common hub commands (availability and permissions depend on the hub):")
    RRC_SLASH_COMMANDS.filterNot { it.name == "help" || it.name == "me" }.forEach { command ->
        appendLine("${command.usage} — ${command.description}")
    }
    appendLine("Mode flags: +m/-m moderated, +i/-i invite-only, +t/-t topic-protected, +n/-n no outside messages, +p/-p private, +r/-r registered.")
    appendLine("Mode keys: /mode <room> +k <key> and /mode <room> -k.")
    appendLine("Mode roles: /mode <room> (+o|-o|+v|-v) <nick|hash>.")
}

internal fun slashCommandSuggestions(draft: String): List<RrcSlashCommand> {
    val input = draft.trimStart()
    if (!input.startsWith('/')) return emptyList()

    val commandToken = input.drop(1).takeWhile { !it.isWhitespace() }.lowercase()
    val exactMatch = RRC_SLASH_COMMANDS.firstOrNull { it.name == commandToken }
    return if (input.drop(1).any(Char::isWhitespace)) {
        listOfNotNull(exactMatch)
    } else {
        RRC_SLASH_COMMANDS.filter { it.name.startsWith(commandToken) }
    }
}

internal fun completeSlashCommand(draft: String, command: RrcSlashCommand): String {
    val leadingWhitespace = draft.takeWhile(Char::isWhitespace)
    val remaining = draft.drop(leadingWhitespace.length)
    val token = remaining.takeWhile { !it.isWhitespace() }
    val suffix = remaining.drop(token.length)
    return leadingWhitespace + "/${command.name}" + if (suffix.isEmpty()) " " else suffix
}

internal fun isUnrecognizedRrcCommandReply(text: String): Boolean {
    val normalized = text.trim().lowercase()
    return listOf(
        "unknown command",
        "unrecognized command",
        "unrecognised command",
        "command not found",
        "unsupported command",
    ).any { phrase -> normalized.contains(phrase) }
}
