package com.finance.tracker.backend

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal class DatabaseConnectionConfig(val jdbcUrl: String, val user: String? = null, val password: String? = null)

internal fun databaseConnectionConfig(environment: Map<String, String>): DatabaseConnectionConfig {
    val value = environment["FT_DATABASE_URL"] ?: environment["DATABASE_URL"]
        ?: throw StorageFailure("storage.config")
    return when {
        value.startsWith("sqlite+pysqlite:///") -> {
            if ('?' in value || '#' in value) throw StorageFailure("storage.config")
            DatabaseConnectionConfig("jdbc:sqlite:" + value.removePrefix("sqlite+pysqlite:///"))
        }
        value.startsWith("postgresql+psycopg://") || value.startsWith("postgresql://") || value.startsWith("postgres://") -> {
            val normalized = value.replaceFirst("postgresql+psycopg://", "postgresql://")
                .replaceFirst("postgres://", "postgresql://")
            val uri = try { URI(normalized) }
                catch (_: java.net.URISyntaxException) { throw StorageFailure("storage.config") }
            if (uri.host == null || uri.path.isNullOrBlank() || uri.fragment != null)
                throw StorageFailure("storage.config")
            val credentials = uri.rawUserInfo?.split(':', limit = 2).orEmpty()
            fun decode(value: String?) = value?.let { URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8) }
            val query = postgresSecurityQuery(uri.rawQuery)
            DatabaseConnectionConfig("jdbc:postgresql://${uri.host}:${if (uri.port < 0) 5432 else uri.port}${uri.rawPath}$query",
                decode(credentials.getOrNull(0)), decode(credentials.getOrNull(1)))
        }
        else -> throw StorageFailure("storage.config")
    }
}

private fun postgresSecurityQuery(rawQuery: String?): String {
    if (rawQuery == null) return ""
    val options = linkedMapOf<String, String>()
    for (part in rawQuery.split('&')) {
        val pair = part.split('=', limit = 2)
        if (pair.size != 2) throw StorageFailure("storage.config")
        fun decode(value: String): String = try { URLDecoder.decode(value, StandardCharsets.UTF_8) }
            catch (_: IllegalArgumentException) { throw StorageFailure("storage.config") }
        val name = decode(pair[0])
        val value = decode(pair[1])
        val key = when (name) {
            "sslmode" -> "sslmode"
            "channel_binding" -> "channelBinding"
            else -> throw StorageFailure("storage.config")
        }
        val normalized = if (key == "sslmode") value.lowercase(java.util.Locale.ROOT) else value
        val allowed = if (key == "sslmode") setOf("disable", "allow", "prefer", "require", "verify-ca", "verify-full")
            else setOf("disable", "prefer", "require")
        if (normalized !in allowed || options.putIfAbsent(key, normalized) != null) throw StorageFailure("storage.config")
    }
    return options.entries.joinToString("&", prefix = "?") { (key, value) -> "$key=$value" }
}
