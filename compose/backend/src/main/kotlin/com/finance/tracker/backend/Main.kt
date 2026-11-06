package com.finance.tracker.backend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.netty.Netty

/** Production server entrypoint. Empty databases are initialized from the checked-in baseline. */
fun main() {
    try { runServer() }
    catch (failure: StorageFailure) {
        System.err.println(storageStartupDiagnostic(failure))
        kotlin.system.exitProcess(1)
    }
}

internal fun storageStartupDiagnostic(failure: StorageFailure): String {
    val sql = failure.cause as? java.sql.SQLException
    val state = sql?.sqlState?.takeIf { it.matches(Regex("[A-Z0-9]{5}")) } ?: "unknown"
    return "finance-tracker: ${failure.code}" + if (sql == null) "" else
        " (SQLState=$state, vendorCode=${sql.errorCode}, rollbackFailures=${sql.suppressed.size})"
}

private fun runServer() {
    val environment = System.getenv()
    val port = requiredServerPort(environment)
    val origin = requiredWebOrigin(environment)
    val bindHost = environment["FT_HOST"] ?: "127.0.0.1"
    require(bindHost.isNotBlank() && '/' !in bindHost && '\n' !in bindHost && '\r' !in bindHost) { "invalid server host" }
    val config = databaseConnectionConfig(environment)
    val database = Database(config.jdbcUrl, config.user, config.password)
    database.runtimeNotices.forEach { System.err.println("finance-tracker: $it") }
    database.bootstrapSchema()
    database.validateSchema()
    ParsedImportSettings.load(System.getenv()).build().use { parsedImports ->
        embeddedServer(Netty,environment=applicationEnvironment {},configure={connector {host=bindHost;this.port=port};configureFinanceHttp()}) {
            installParsedImportRuntime(parsedImports)
            financeModule(database, origin, mutationPlans = MutationPlanService(database, staging = parsedImports.store),
                cashImports = CashImportHttpService(database, parsedImports.store))
        }.start(wait = true)
    }
}

internal fun io.ktor.server.netty.NettyApplicationEngine.Configuration.configureFinanceHttp() {
    maxInitialLineLength=16384
}

internal fun requiredServerPort(environment: Map<String, String>): Int {
    val port = (environment["PORT"] ?: environment["FT_KOTLIN_PORT"])?.toIntOrNull()
    require(port != null && port in 1024..65535) { "invalid server port" }
    return port
}

internal fun requiredWebOrigin(environment: Map<String, String>): String =
    validateWebOrigin(requireNotNull(environment["FT_WEB_ORIGIN"]) { "missing Web origin" })
