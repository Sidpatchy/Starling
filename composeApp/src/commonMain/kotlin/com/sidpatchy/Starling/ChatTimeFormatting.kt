package com.sidpatchy.Starling

internal expect fun formatChatTimestamp(
    timestampMillis: Long,
    timeZonePreference: TimestampTimeZonePreference,
): String

/** A date formatter for this transcript, in the same time zone as the message times. */
internal expect fun chatDateFormatter(timeZonePreference: TimestampTimeZonePreference): (Long) -> String

internal fun formatUtcChatTimestamp(timestampMillis: Long): String {
    val millisPerDay = 86_400_000L
    val millisOfDay = ((timestampMillis % millisPerDay) + millisPerDay) % millisPerDay
    val hour = (millisOfDay / 3_600_000L).toInt()
    val minute = ((millisOfDay % 3_600_000L) / 60_000L).toInt()
    return hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
}

internal fun displayChatTime(
    fallback: String,
    timestampMillis: Long?,
    timeZonePreference: TimestampTimeZonePreference,
): String = timestampMillis?.let { formatChatTimestamp(it, timeZonePreference) } ?: fallback

/** One optional divider label per message, retaining the existing transcript order. */
internal fun chatMessageDayLabels(
    messages: List<ChatMessage>,
    today: String,
    formatDate: (Long) -> String,
): List<String?> {
    var previousDate: String? = null
    return messages.map { message ->
        val date = message.timestampMillis?.let(formatDate)
        if (date == null || date == previousDate) {
            null
        } else {
            previousDate = date
            if (date == today) "Today" else date
        }
    }
}
