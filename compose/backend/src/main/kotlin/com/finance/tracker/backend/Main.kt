package com.finance.tracker.backend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.netty.Netty
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Local migration preview only. The existing production entrypoint remains authoritative. */
fun main() {
    check(System.getenv("FT_KOTLIN_PREVIEW") == "true") { "incomplete Kotlin migration requires FT_KOTLIN_PREVIEW=true" }
    val value = System.getenv("FT_DATABASE_URL") ?: throw StorageFailure("storage.config")
    val database = when {
        value.startsWith("sqlite+pysqlite:///") -> {
            if ('?' in value || '#' in value) throw StorageFailure("storage.config")
            Database("jdbc:sqlite:" + value.removePrefix("sqlite+pysqlite:///"))
        }
        value.startsWith("postgresql+psycopg://") -> {
            val uri = try { URI(value.replaceFirst("postgresql+psycopg://", "postgresql://")) }
                catch (_: java.net.URISyntaxException) { throw StorageFailure("storage.config") }
            if (uri.host == null || uri.path.isNullOrBlank() || uri.rawQuery != null || uri.fragment != null)
                throw StorageFailure("storage.config")
            val credentials = uri.rawUserInfo?.split(':', limit = 2).orEmpty()
            fun decode(value: String?) = value?.let { URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8) }
            Database("jdbc:postgresql://${uri.host}:${if (uri.port < 0) 5432 else uri.port}${uri.rawPath}",
                decode(credentials.getOrNull(0)), decode(credentials.getOrNull(1)))
        }
        else -> throw StorageFailure("storage.config")
    }
    database.validateSchema()
    val port = System.getenv("FT_KOTLIN_PORT")?.toIntOrNull() ?: 18240
    require(port in 1024..65535) { "invalid local preview port" }
    val origin = validateWebOrigin(System.getenv("FT_WEB_ORIGIN") ?: "http://127.0.0.1:5173")
    embeddedServer(Netty,environment=applicationEnvironment {},configure={connector {host="127.0.0.1";this.port=port};configureFinanceHttp()}) { financeModule(database, origin) }.start(wait = true)
}

internal fun io.ktor.server.netty.NettyApplicationEngine.Configuration.configureFinanceHttp() {
    maxInitialLineLength=16384
}
