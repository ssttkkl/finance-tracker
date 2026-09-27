package com.finance.tracker.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
private val HTTPS_ORIGIN = Regex("^https://(?:\\[[0-9A-Fa-f:]+\\]|[A-Za-z0-9.-]+)(?::([0-9]{1,5}))?$")
private val HTTP_ORIGIN = Regex("^http://(\\[[0-9A-Fa-f:]+\\]|[A-Za-z0-9.-]+):([0-9]{1,5})$")

internal fun normalizeApiOrigin(value: String, allowInsecureHttp: Boolean): String {
    val candidate = value.trim().removeSuffix("/")
    val secureMatch = HTTPS_ORIGIN.matchEntire(candidate)
    if (secureMatch != null) {
        val port = secureMatch.groupValues.getOrNull(1)?.toIntOrNull()
        if (port != null && port !in 1..65535) throw ApiFailure("api_origin_invalid", 0)
        return candidate
    }
    val insecureMatch = HTTP_ORIGIN.matchEntire(candidate) ?: throw ApiFailure("api_origin_invalid", 0)
    val host = insecureMatch.groupValues[1].removePrefix("[").removeSuffix("]").lowercase()
    val port = insecureMatch.groupValues[2].toIntOrNull()
    val localDevelopmentHost = host == "localhost" || host == "127.0.0.1" || host == "::1"
    if (port !in 1..65535 || (!allowInsecureHttp && !localDevelopmentHost)) {
        throw ApiFailure("api_origin_invalid", 0)
    }
    return candidate
}

internal fun encodePathSegment(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xff
        val character = unsigned.toChar()
        if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "-_.~") {
            append(character)
        } else {
            append('%')
            append("0123456789ABCDEF"[unsigned shr 4])
            append("0123456789ABCDEF"[unsigned and 15])
        }
    }
}

internal fun appendQuery(path: String, values: List<Pair<String, String?>>): String {
    val query = values.asSequence()
        .filter { (_, value) -> value != null && value.isNotEmpty() }
        .joinToString("&") { (key, value) -> "${encodeQueryComponent(key)}=${encodeQueryComponent(value.orEmpty())}" }
    if (query.isEmpty()) return path
    return path + if ('?' in path) "&$query" else "?$query"
}

internal fun encodeQueryComponent(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xff
        val character = unsigned.toChar()
        if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "-_.~") {
            append(character)
        } else {
            append('%')
            append("0123456789ABCDEF"[unsigned shr 4])
            append("0123456789ABCDEF"[unsigned and 15])
        }
    }
}

internal fun categoryWriteBody(name: String, parentId: String?, description: String, expectedRevision: Long) = buildJsonObject {
    put("name", name)
    put("description", description)
    put("parent_id", parentId?.let(::JsonPrimitive) ?: JsonNull)
    put("expected_revision", expectedRevision)
}

internal fun importPayload(options: ImportRequestOptions) = buildJsonObject {
    if (options.importToken != null) put("import_token", options.importToken)
    if (options.batch) put("batch", true)
    put("source", options.source)
    put("currency", options.currency?.let(::JsonPrimitive) ?: JsonNull)
    put("preview_digest", options.previewDigest?.let(::JsonPrimitive) ?: JsonNull)
    if (options.previewRelationDigest != null) put("preview_relation_digest", options.previewRelationDigest)
    put("preview_channel", options.previewChannel?.let(::JsonPrimitive) ?: JsonNull)
    if (options.relations != null) put("relations", apiJson.encodeToJsonElement(options.relations))
    if (options.mapping != null) put("mapping", apiJson.encodeToJsonElement(options.mapping))
}

internal fun base64(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    return buildString(((bytes.size + 2) / 3) * 4) {
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xff
            val second = bytes.getOrNull(index + 1)?.toInt()?.and(0xff)
            val third = bytes.getOrNull(index + 2)?.toInt()?.and(0xff)
            append(alphabet[first shr 2])
            append(alphabet[((first and 0x03) shl 4) or ((second ?: 0) shr 4)])
            append(if (second == null) '=' else alphabet[((second and 0x0f) shl 2) or ((third ?: 0) shr 6)])
            append(if (third == null) '=' else alphabet[third and 0x3f])
            index += 3
        }
    }
}
