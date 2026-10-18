package com.sidpatchy.Starling

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale

internal actual fun formatChatTimestamp(
    timestampMillis: Long,
    timeZonePreference: TimestampTimeZonePreference,
): String {
    if (timeZonePreference == TimestampTimeZonePreference.UTC) return formatUtcChatTimestamp(timestampMillis)
    val formatter = NSDateFormatter().apply {
        dateFormat = "HH:mm"
        locale = NSLocale("en_US_POSIX")
    }
    val date = NSDate(timestampMillis.toDouble() / 1_000.0 - 978_307_200.0)
    return formatter.stringFromDate(date)
}
