package com.finance.tracker.backend

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

internal fun strictUtf8(value: String): ByteArray {
    val buffer = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value))
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}

internal fun tokenDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(strictUtf8(value)).joinToString("") { "%02x".format(it) }

internal fun secureToken(): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

class Passwords {
    private val base64 = Base64.getEncoder().withoutPadding()

    fun hash(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val output = derive(password, Argon2Parameters.ARGON2_id, 19, 65536, 3, 4, salt, 32)
        return "\$argon2id\$v=19\$m=65536,t=3,p=4\$${base64.encodeToString(salt)}\$${base64.encodeToString(output)}"
            .also { output.fill(0); salt.fill(0) }
    }

    fun verify(encoded: String, password: String): Boolean {
        val match = Regex("^[$](argon2id|argon2i|argon2d)[$]v=(16|19)[$]m=([0-9]+),t=([0-9]+),p=([0-9]+)[$]([A-Za-z0-9+/]+)[$]([A-Za-z0-9+/]+)$")
            .matchEntire(encoded) ?: return false
        val values = match.groupValues
        val memory = values[3].toIntOrNull() ?: return false
        val iterations = values[4].toIntOrNull() ?: return false
        val lanes = values[5].toIntOrNull() ?: return false
        if (lanes !in 1..0xffffff || iterations < 1 || memory.toLong() < 8L * lanes) return false
        val salt: ByteArray
        val expected: ByteArray
        try {
            salt = Base64.getDecoder().decode(values[6])
            expected = Base64.getDecoder().decode(values[7])
        } catch (_: IllegalArgumentException) { return false }
        if (salt.size < 8 || expected.size < 4) return false
        val type = when (values[1]) { "argon2id" -> Argon2Parameters.ARGON2_id; "argon2i" -> Argon2Parameters.ARGON2_i; else -> Argon2Parameters.ARGON2_d }
        val actual = derive(password, type, values[2].toInt(), memory, iterations, lanes, salt, expected.size)
        return MessageDigest.isEqual(actual, expected).also { actual.fill(0); expected.fill(0); salt.fill(0) }
    }

    private fun derive(password: String, type: Int, version: Int, memory: Int, iterations: Int, lanes: Int, salt: ByteArray, length: Int): ByteArray {
        val bytes = strictUtf8(password)
        val parameters = Argon2Parameters.Builder(type).withVersion(version).withMemoryAsKB(memory)
            .withIterations(iterations).withParallelism(lanes).withSalt(salt).build()
        return try {
            ByteArray(length).also { output -> Argon2BytesGenerator().apply { init(parameters) }.generateBytes(bytes, output) }
        } finally { bytes.fill(0); parameters.clear() }
    }
}
