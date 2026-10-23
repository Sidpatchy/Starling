package com.sidpatchy.Starling

/** Optional, human-readable RRC NOTICE used to refresh Starling's live room roster. */
internal const val RRC_PRESENCE_NOTICE_BODY = "Starling presence: online"

/** Room modes understood by the optional RRCD command extension. */
data class ChatRoomModes(
    val moderated: Boolean = false,
    val inviteOnly: Boolean = false,
    val topicOpsOnly: Boolean = false,
    val noOutsideMessages: Boolean = false,
    val privateRoom: Boolean = false,
    val registered: Boolean = false,
    val hasKey: Boolean = false,
) {
    internal fun toStorageValue(): String = listOf(
        moderated,
        inviteOnly,
        topicOpsOnly,
        noOutsideMessages,
        privateRoom,
        registered,
        hasKey,
    ).joinToString("") { if (it) "1" else "0" }

    companion object {
        internal fun fromStorageValue(value: String): ChatRoomModes? {
            if (value.length != 7 || value.any { it != '0' && it != '1' }) return null
            return ChatRoomModes(
                moderated = value[0] == '1',
                inviteOnly = value[1] == '1',
                topicOpsOnly = value[2] == '1',
                noOutsideMessages = value[3] == '1',
                privateRoom = value[4] == '1',
                registered = value[5] == '1',
                hasKey = value[6] == '1',
            )
        }

        internal fun fromRrcd(modeText: String, registered: Boolean): ChatRoomModes {
            val flags = modeText.substringAfter('+', "")
            return ChatRoomModes(
                moderated = 'm' in flags,
                inviteOnly = 'i' in flags,
                topicOpsOnly = 't' in flags,
                noOutsideMessages = 'n' in flags,
                privateRoom = 'p' in flags,
                registered = registered || 'r' in flags,
                hasKey = 'k' in flags,
            )
        }
    }
}

internal sealed interface RrcRoomStateUpdate {
    data class Snapshot(val topic: String, val modes: ChatRoomModes) : RrcRoomStateUpdate
    data class Topic(val value: String) : RrcRoomStateUpdate
    data class Modes(val value: ChatRoomModes) : RrcRoomStateUpdate
    data class Registration(val registered: Boolean) : RrcRoomStateUpdate
}

/** Parse the topic and mode NOTICE conventions currently emitted by RRCD. */
internal fun parseRrcRoomStateUpdate(roomName: String, notice: String): RrcRoomStateUpdate? {
    val roomPrefix = "room $roomName:"
    if (notice.startsWith(roomPrefix, ignoreCase = true)) {
        val info = notice.drop(roomPrefix.length).trimStart()
        val modeMarker = info.indexOf("; mode=", ignoreCase = true)
        val topicMarker = info.indexOf("; topic=", ignoreCase = true)
        if (modeMarker < 0 || topicMarker <= modeMarker) return null
        val registration = info.substring(0, modeMarker).trim().equals("registered", ignoreCase = true)
        val modeText = info.substring(modeMarker + "; mode=".length, topicMarker).trim()
        val topic = normalizeRoomTopic(info.substring(topicMarker + "; topic=".length))
        return RrcRoomStateUpdate.Snapshot(topic, ChatRoomModes.fromRrcd(modeText, registration))
    }

    val topicChangePrefix = "topic for $roomName is now:"
    if (notice.startsWith(topicChangePrefix, ignoreCase = true)) {
        return RrcRoomStateUpdate.Topic(normalizeRoomTopic(notice.drop(topicChangePrefix.length)))
    }
    val topicQueryPrefix = "topic for $roomName:"
    if (notice.startsWith(topicQueryPrefix, ignoreCase = true)) {
        return RrcRoomStateUpdate.Topic(normalizeRoomTopic(notice.drop(topicQueryPrefix.length)))
    }

    val modeChangePrefix = "mode for $roomName is now:"
    if (notice.startsWith(modeChangePrefix, ignoreCase = true)) {
        val modeToken = notice.drop(modeChangePrefix.length).trim().takeWhile { !it.isWhitespace() }
        if (modeToken == "(none)") {
            return RrcRoomStateUpdate.Modes(ChatRoomModes())
        }
        if (modeToken.startsWith('+') && modeToken.drop(1).isNotEmpty() && modeToken.drop(1).all { it in "mitnprk" }) {
            return RrcRoomStateUpdate.Modes(ChatRoomModes.fromRrcd(modeToken, registered = 'r' in modeToken))
        }
    }

    val registeredPrefix = "registered room $roomName"
    if (notice.equals(registeredPrefix, ignoreCase = true)) {
        return RrcRoomStateUpdate.Registration(registered = true)
    }
    val unregisteredPrefix = "unregistered room $roomName"
    if (notice.equals(unregisteredPrefix, ignoreCase = true)) {
        return RrcRoomStateUpdate.Registration(registered = false)
    }
    return null
}

private fun normalizeRoomTopic(value: String): String = when (val topic = value.trim()) {
    "(none)", "(cleared)" -> ""
    else -> topic
}
