package com.finance.tracker.backend

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive

/** Hash the canonical JSON bytes without allocating an encoder and byte array for each token. */
internal class CashWriteSetCanonicalDigest(limit: Int) {
    private val encoder = StandardCharsets.UTF_8.newEncoder()
    private val bytes = ByteBuffer.allocate(16 * 1024)
    private val digest = MessageDigest.getInstance("SHA-256")
    private val pending = StringBuilder(8 * 1024)
    private var remaining = limit

    fun append(token: String) {
        if (token.length > remaining) invalid()
        if (pending.length + token.length > 8 * 1024) flush()
        if (token.length >= 8 * 1024) update(token) else pending.append(token)
    }

    fun quoted(text: String) {
        if (text.length.toLong() + 2 > remaining) invalid()
        var index = 0
        var escaped = false
        var encodedSize = 2L
        while (index < text.length) {
            val char = text[index++]
            encodedSize += when {
                char == '"' || char == '\\' || char in "\b\u000c\n\r\t" -> {
                    escaped = true
                    2
                }
                char.code < 32 -> {
                    escaped = true
                    6
                }
                char.code < 128 -> 1
                char.code < 2048 -> 2
                char.isHighSurrogate() -> {
                    if (index == text.length || !text[index++].isLowSurrogate()) invalid()
                    4
                }
                char.isLowSurrogate() -> invalid()
                else -> 3
            }
        }
        if (encodedSize > remaining) invalid()
        if (escaped) {
            append(JsonPrimitive(text).toString())
        } else {
            append("\"")
            append(text)
            append("\"")
        }
    }

    fun finish(): String {
        flush()
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun flush() {
        if (pending.isEmpty()) return
        update(pending)
        pending.setLength(0)
    }

    private fun update(text: CharSequence) {
        encoder.reset()
        val input = CharBuffer.wrap(text)
        do {
            val result = encoder.encode(input, bytes, true)
            if (result.isError) invalid()
            drain()
        } while (result.isOverflow)
        do {
            val result = encoder.flush(bytes)
            if (result.isError) invalid()
            drain()
        } while (result.isOverflow)
    }

    private fun drain() {
        bytes.flip()
        if (bytes.remaining() > remaining) invalid()
        remaining -= bytes.remaining()
        digest.update(bytes)
        bytes.clear()
    }

    private fun invalid(): Nothing = throw CashWriteSetSubmissionFailure("write_set_payload_invalid")
}
