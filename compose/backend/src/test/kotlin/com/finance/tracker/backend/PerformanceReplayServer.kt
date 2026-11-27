package com.finance.tracker.backend

import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.URI

fun main() {
    val environment = System.getenv()
    require(environment.getValue("FT_PERF_REPLAY") == "1")
    val location = URI.create(environment.getValue("FT_DATABASE_URL"))
    require(location.scheme in setOf("postgresql", "postgres"))
    require(location.host == "127.0.0.1" && location.path.matches(Regex("/[a-z0-9_]+_test")))
    val config = databaseConnectionConfig(environment)
    val database = Database(config.jdbcUrl, config.user, config.password)
    database.bootstrapSchema()
    database.validateSchema()
    ParsedImportSettings.load(environment).build().use { imports ->
        embeddedServer(Netty, configure = {
            connector {
                host = "127.0.0.1"
                port = requiredServerPort(environment)
            }
            configureFinanceHttp()
        }) {
            installParsedImportRuntime(imports)
            financeModule(
                database, requiredWebOrigin(environment),
                mutationPlans = MutationPlanService(database, staging = imports.store),
                runCashImportWorker = false,
            )
        }.start(wait = true)
    }
}
