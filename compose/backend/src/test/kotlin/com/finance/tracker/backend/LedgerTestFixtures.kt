package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import java.util.UUID
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

internal fun withLedgerTestWorkspace(action: (Database, String, String) -> Unit) {
    val db = ledgerContractDatabase()
    val token = "synthetic-ledger-" + UUID.randomUUID()
    val sessionId = UUID.randomUUID().toString()
    val at = Instant.parse("2026-11-04T00:00:00Z")
    db.write { c ->
        c.jooq(db.sqlite).insertInto(USER_SESSIONS).set(USER_SESSIONS.ID, sessionId)
            .set(USER_SESSIONS.USER_ID, "admin").set(USER_SESSIONS.TOKEN_DIGEST, tokenDigest(token))
            .setNull(USER_SESSIONS.ACTIVE_WORKSPACE_ID).set(USER_SESSIONS.CREATED_AT, at)
            .set(USER_SESSIONS.EXPIRES_AT, Instant.parse("2099-01-01T00:00:00Z")).execute()
    }
    val workspaces = WorkspaceService(db)
    val name = "Ledger catalogue test"
    val workspace = requireNotNull(workspaces.create(token, name).activeWorkspaceId)
    try { action(db, workspace, token) }
    finally {
        setAccountTestRole(db, workspace, "admin")
        workspaces.delete(token, name)
        db.write { c -> c.jooq(db.sqlite).deleteFrom(USER_SESSIONS).where(USER_SESSIONS.ID.eq(sessionId)).execute() }
    }
}
