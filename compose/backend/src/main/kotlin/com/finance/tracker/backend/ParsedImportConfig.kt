package com.finance.tracker.backend

import java.net.URI
import kotlinx.serialization.json.JsonPrimitive

private fun String.pythonStrip() = trim { it.code in pythonWhitespace }

internal class ParsedStorageConfigurationError(message: String) : IllegalArgumentException(message)

internal class ParsedImportRuntime(val store: ParsedImportStore, val cleanup: ParsedImportCleanup,
    private val resource: AutoCloseable? = null) : AutoCloseable {
    private var closed = false
    @Synchronized override fun close() {
        if (closed) return
        try { cleanup.stop() } finally { closed = true; resource?.close() }
    }
}

internal class ParsedImportSettings private constructor(val backend: String, val ttlSeconds: Int, val prefix: String,
    private val environment: Map<String, String>) {
    fun build(): ParsedImportRuntime {
        var client: S3ParsedObjectClient? = null
        try {
            val interval = integer(environment["FT_IMPORT_STAGING_SWEEP_SECONDS"] ?: "30")
            val pageSize = integer(environment["FT_IMPORT_STAGING_SWEEP_PAGE_SIZE"] ?: "100")
            val store = if (backend == "memory") InMemoryParsedImportStore(ttlSeconds = ttlSeconds) else {
                client = S3ParsedObjectClient.connect(URI(environment.getValue("FT_R2_ENDPOINT").pythonStrip()),
                    environment.getValue("FT_R2_ACCESS_KEY_ID").pythonStrip(), environment.getValue("FT_R2_SECRET_ACCESS_KEY").pythonStrip(),
                    environment["FT_R2_REGION"] ?: "auto")
                R2ParsedImportStore(client, environment.getValue("FT_R2_BUCKET").pythonStrip(), prefix, ttlSeconds)
            }
            return ParsedImportRuntime(store, ParsedImportCleanup(store, interval, pageSize), client)
        } catch (_: Exception) {
            try { client?.close() } catch (_: Exception) { /* Configuration errors expose no provider details. */ }
            throw ParsedStorageConfigurationError("import_session_storage_config_invalid")
        }
    }

    companion object {
        private fun integer(value: String): Int = pythonIntegerValue(JsonPrimitive(value)).intValueExact()
        fun load(environment: Map<String, String>): ParsedImportSettings {
            val values = environment.toMap()
            val backend = values["FT_IMPORT_STAGING_BACKEND"].orEmpty().pythonStrip().lowercase(java.util.Locale.ROOT)
            if (backend !in setOf("memory", "r2")) throw ParsedStorageConfigurationError("FT_IMPORT_STAGING_BACKEND must be explicitly set to r2 or memory")
            if (backend == "memory" && values["FT_IMPORT_STAGING_ALLOW_MEMORY"] != "1")
                throw ParsedStorageConfigurationError("FT_IMPORT_STAGING_ALLOW_MEMORY=1 is required for the memory backend")
            val ttl = try { integer(values["FT_IMPORT_STAGING_TTL_SECONDS"] ?: "1800") }
                catch (_: CategoryFailure) { throw ParsedStorageConfigurationError("FT_IMPORT_STAGING_TTL_SECONDS is invalid") }
                catch (_: IllegalArgumentException) { throw ParsedStorageConfigurationError("FT_IMPORT_STAGING_TTL_SECONDS is invalid") }
                catch (_: ArithmeticException) { throw ParsedStorageConfigurationError("FT_IMPORT_STAGING_TTL_SECONDS is invalid") }
            if (ttl !in 1..1800) throw ParsedStorageConfigurationError("FT_IMPORT_STAGING_TTL_SECONDS must be between 1 and 1800")
            val prefix = (values["FT_R2_PREFIX"] ?: "cash-import").pythonStrip().trim('/')
            if (prefix.isEmpty() || ".." in prefix) throw ParsedStorageConfigurationError("FT_R2_PREFIX is invalid")
            if (backend == "r2") {
                for (name in listOf("FT_R2_ENDPOINT", "FT_R2_BUCKET", "FT_R2_ACCESS_KEY_ID", "FT_R2_SECRET_ACCESS_KEY"))
                    if (values[name].orEmpty().pythonStrip().isEmpty()) throw ParsedStorageConfigurationError("$name is required for the r2 backend")
                val endpoint = try { URI(values.getValue("FT_R2_ENDPOINT").pythonStrip()) }
                    catch (_: IllegalArgumentException) { throw ParsedStorageConfigurationError("FT_R2_ENDPOINT is invalid") }
                    catch (_: java.net.URISyntaxException) { throw ParsedStorageConfigurationError("FT_R2_ENDPOINT is invalid") }
                if (endpoint.scheme?.lowercase() != "https" || endpoint.rawAuthority.isNullOrEmpty() || endpoint.rawPath !in listOf("", "/") ||
                    endpoint.rawQuery != null || endpoint.rawFragment != null) throw ParsedStorageConfigurationError("FT_R2_ENDPOINT is invalid")
            }
            return ParsedImportSettings(backend, ttl, prefix, values)
        }
    }
}
