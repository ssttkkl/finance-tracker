package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun ledgerContractDatabase(): Database {
    val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_LEDGER_CONTRACT_DIR")))
    val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
    val jdbc = config.getValue("jdbc_url").jsonPrimitive.content
    if (jdbc.startsWith("jdbc:postgresql:")) {
        val explicit = java.net.URI(requireNotNull(System.getenv("FT_TEST_POSTGRES_URL")).replace("postgresql+psycopg://", "postgresql://"))
        val target = java.net.URI(jdbc.removePrefix("jdbc:"))
        require(explicit.scheme == "postgresql" && explicit.path.endsWith("_test") &&
            explicit.host == target.host && explicit.port == target.port && explicit.path == target.path)
    }
    return Database(jdbc, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
}
