package com.sidpatchy.Starling

import kotlin.io.encoding.Base64

/** UTF-8 fields shared by the chat-state format and private Python bridge. */
internal object Base64TextCodec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(value: String): String = Base64.encode(value.encodeToByteArray())

    // Preserve legacy padding handling. Bridge fields also skip non-alphabet characters;
    // persisted fields instead reject them by returning an empty string.
    fun decode(value: String, ignoreInvalidCharacters: Boolean = false): String {
        val bytes = ByteArray(value.length * 3 / 4)
        var outputIndex = 0
        var buffer = 0
        var bits = 0
        for (character in value) {
            if (character == '=') break
            val digit = ALPHABET.indexOf(character)
            if (digit < 0) {
                if (ignoreInvalidCharacters) continue
                return ""
            }
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (outputIndex < bytes.size) bytes[outputIndex++] = ((buffer shr bits) and 0xff).toByte()
            }
        }
        return bytes.copyOf(outputIndex).decodeToString(throwOnInvalidSequence = false)
    }
}
