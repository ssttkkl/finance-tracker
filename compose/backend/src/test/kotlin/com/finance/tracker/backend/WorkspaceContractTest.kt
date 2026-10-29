package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class WorkspaceContractTest {
    @Test fun workspaceOperationsMatchPythonAndPublishAnEmptyLedgerAtomically() = testApplication {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_WORKSPACE_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        database.validateSchema()
        val ids = AtomicInteger(); val tokens = AtomicInteger()
        val service = WorkspaceService(database, now = { Instant.parse("2026-10-28T14:00:00Z") },
            newWorkspaceId = { "workspace-created-${ids.getAndIncrement()}" },
            newInvitationToken = { "anonymous-invite-${tokens.getAndIncrement()}" })
        application { financeModule(database, clock = { Instant.parse("2026-10-28T14:00:00Z") }, workspaces = service) }
        try {
            for (value in Json.parseToJsonElement(root.resolve("workspaces.json").readText()).jsonArray) {
                val case = value.jsonObject
                val response = client.request(case.getValue("path").jsonPrimitive.content) {
                    method = HttpMethod.parse(case.getValue("method").jsonPrimitive.content)
                    case.getValue("headers").jsonObject.forEach { (name, value) -> header(name, value.jsonPrimitive.content) }
                    if (case["request"] != JsonNull) { contentType(ContentType.Application.Json); setBody(case.getValue("request").toString()) }
                }
                val context = case.getValue("path").toString() + " " + case["request"] + " " + case["headers"]
                assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, context)
                assertEquals(case["body"], Json.parseToJsonElement(response.bodyAsText()), context)
                assertEquals(case["effects"], effects(database), context)
            }
        } finally { restoreSeed(database) }
    }

    private fun effects(database: Database): JsonObject = database.read { connection ->
        fun rows(sql: String, convert: (java.sql.ResultSet) -> JsonArray): JsonArray = connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> JsonArray(buildList { while (rows.next()) add(convert(rows)) }) }
        }
        fun strings(rows: java.sql.ResultSet, count: Int) = JsonArray((1..count).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull })
        buildJsonObject {
            put("workspaces", rows("SELECT id,name FROM workspaces ORDER BY id") { strings(it, 2) })
            put("members", rows("SELECT workspace_id,user_id,role FROM workspace_memberships ORDER BY workspace_id,user_id,role") { strings(it, 3) })
            put("sessions", rows("SELECT token_digest,active_workspace_id FROM user_sessions ORDER BY token_digest") { strings(it, 2) })
            put("invitations", rows("SELECT token_digest,workspace_id,role,accepted_at FROM workspace_invitations ORDER BY token_digest") {
                JsonArray((1..3).map { i -> JsonPrimitive(it.getString(i)) } + JsonPrimitive(it.getString(4) != null))
            })
            put("projections", rows("""SELECT s.workspace_id,s.projection_version,s.source_revision,s.rules_version,s.availability,
                s.last_build_status,s.projection_count,s.member_count,d.source_digest,d.state FROM cash_projection_states s
                JOIN cash_projection_datasets d ON d.id=s.active_dataset_id AND d.workspace_id=s.workspace_id ORDER BY s.workspace_id""") {
                JsonArray((1..10).map { i -> if (i in listOf(2, 3, 7, 8)) JsonPrimitive(it.getLong(i)) else JsonPrimitive(it.getString(i)) })
            })
        }
    }

    private fun restoreSeed(database: Database) = database.write { connection ->
        for (table in listOf("cash_projection_states", "cash_projection_datasets", "workspaces")) connection.createStatement().use {
            it.executeUpdate("DELETE FROM $table WHERE ${if (table == "workspaces") "id" else "workspace_id"} IN ('workspace-created-0','workspace-created-1')")
        }
        connection.prepareStatement("DELETE FROM workspace_invitations WHERE token_digest=?").use {
            for (index in 0..9) { it.setString(1, tokenDigest("anonymous-invite-$index")); it.executeUpdate() }
        }
        connection.createStatement().use {
            it.executeUpdate("DELETE FROM workspace_memberships WHERE workspace_id='fixture-a' AND user_id IN ('admin','viewer','empty')")
        }
        connection.prepareStatement("INSERT INTO workspace_memberships(workspace_id,user_id,role,created_at) VALUES('fixture-a',?,?,?)").use {
            for ((user, role) in listOf("admin" to "admin", "viewer" to "viewer")) {
                it.setString(1, user); it.setString(2, role); it.instant(3, Instant.parse("2026-01-01T00:00:00Z"), database.sqlite); it.executeUpdate()
            }
        }
        connection.prepareStatement("UPDATE user_sessions SET active_workspace_id=? WHERE id=?").use {
            for ((name, workspace) in listOf("admin" to "fixture-a", "viewer" to "fixture-a", "empty" to null)) {
                it.setString(1, workspace); it.setString(2, "session-$name"); it.executeUpdate()
            }
        }
    }
}
