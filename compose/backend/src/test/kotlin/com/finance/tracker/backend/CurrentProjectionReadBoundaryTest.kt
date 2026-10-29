package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.serialization.json.*

class CurrentProjectionReadBoundaryTest {
    @Test fun detailAndLocationRetainOneSnapshotWhileARealRebuildPublishesAndPrunes() {
        for (operation in listOf("location", "detail", "evidence")) CurrentProjectionFixture().use { fixture -> fixture.withWal { testApplication {
            val service = CurrentProjectionReadService(fixture.db)
            val before = when (operation) {
                "location" -> service.location("synthetic-admin", fixture.identifier)
                "detail" -> service.detail("synthetic-admin", fixture.projectionId)
                else -> service.evidence("synthetic-admin", fixture.projectionId)
            }
            val triggered = AtomicBoolean()
            application { financeModule(fixture.db, currentProjections = CurrentProjectionReadService(fixture.db, afterStateRead = {
                if (triggered.compareAndSet(false, true)) {
                    fixture.db.write { c ->
                        c.prepareStatement("UPDATE cash_transactions SET amount='-42.230000000000000001',row_revision=row_revision+1 WHERE workspace_id=? AND id=?").use {
                            it.setString(1, fixture.workspace); it.setLong(2, fixture.identifier); assertEquals(1, it.executeUpdate())
                        }
                        c.prepareStatement("UPDATE cash_transaction_components SET amount='-42.230000000000000001' WHERE workspace_id=? AND cash_transaction_id=?").use {
                            it.setString(1, fixture.workspace); it.setLong(2, fixture.identifier); assertEquals(1, it.executeUpdate())
                        }
                    }
                    fixture.addIssue()
                    CashProjectionService(fixture.db, fixture.workspace).rebuild()
                }
            })) }
            val path = path(operation, fixture)
            val response = client.get(path) { bearerAuth("synthetic-admin") }
            assertEquals(200, response.status.value, response.bodyAsText())
            assertTrue(triggered.get())
            assertEquals(before, Json.parseToJsonElement(response.bodyAsText()))
            val after = Json.parseToJsonElement(client.get(path) { bearerAuth("synthetic-admin") }.bodyAsText()).jsonObject
            assertTrue(after.getValue("projection_version").jsonPrimitive.long > before.getValue("projection_version").jsonPrimitive.long)
            if (operation == "detail") {
                assertEquals(JsonPrimitive("-42.230000000000000001"), after.getValue("projection").jsonObject["amount"])
                assertEquals(JsonPrimitive(2), after.getValue("members").jsonArray.single().jsonObject["row_revision"])
                assertEquals(JsonPrimitive("pending"), after.getValue("members").jsonArray.single().jsonObject["confirmation_status"])
                assertEquals(1, after.getValue("issues").jsonArray.size)
            } else if (operation == "evidence") {
                assertEquals(JsonPrimitive("-42.230000000000000001"), after.getValue("projection").jsonObject["amount"])
                assertEquals(JsonPrimitive(2), after.getValue("members").jsonArray.single().jsonObject["row_revision"])
                assertEquals(after.getValue("root_record"), JsonObject(after.getValue("members").jsonArray.single().jsonObject - "roles"))
            } else assertEquals(JsonPrimitive(1), after["attention_count"])
        } } }
    }

    private fun authorizationChange(kind: String) {
        for (phase in listOf("snapshot", "return")) for (operation in listOf("location", "detail", "evidence")) CurrentProjectionFixture().use { fixture -> fixture.withWal { testApplication {
            fun row(table: String, condition: String) = fixture.db.read { c -> c.createStatement().use {
                it.executeQuery("SELECT * FROM $table WHERE $condition").use { rows ->
                    assertTrue(rows.next()); (1..rows.metaData.columnCount).associate { index -> rows.metaData.getColumnName(index) to rows.getObject(index) }
                }
            } }
            val session = row("user_sessions", "token_digest='${tokenDigest("synthetic-admin")}'")
            val membership = row("workspace_memberships", "workspace_id='${fixture.workspace}' AND user_id='admin'")
            fun restore(c: Connection, table: String, values: Map<String, Any?>) {
                c.prepareStatement("INSERT INTO $table(${values.keys.joinToString(",")}) VALUES(${values.values.joinToString(",") { "?" }})").use { statement ->
                    values.values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }; statement.executeUpdate()
                }
            }
            val changed = AtomicBoolean()
            fun disrupt() {
                if (!changed.compareAndSet(false, true)) return
                fixture.db.write { c ->
                    val sql = when (kind) {
                        "viewer" -> "UPDATE workspace_memberships SET role='viewer' WHERE workspace_id=? AND user_id='admin'"
                        "revoke" -> "DELETE FROM workspace_memberships WHERE workspace_id=? AND user_id='admin'"
                        "workspace" -> "UPDATE user_sessions SET active_workspace_id='fixture-a' WHERE active_workspace_id=? AND user_id='admin'"
                        "expiry" -> "UPDATE user_sessions SET expires_at='2020-01-01 00:00:00' WHERE active_workspace_id=? AND user_id='admin'"
                        else -> "DELETE FROM user_sessions WHERE active_workspace_id=? AND user_id='admin'"
                    }
                    c.prepareStatement(sql).use { it.setString(1, fixture.workspace); assertEquals(1, it.executeUpdate()) }
                }
            }
            val service = CurrentProjectionReadService(fixture.db, afterStateRead = { if (phase == "snapshot") disrupt() },
                beforeReturn = { if (phase == "return") disrupt() })
            application { financeModule(fixture.db, currentProjections = service) }
            val before = currentProjectionDatabaseState(fixture.db, fixture.workspace)
            try {
                val path = path(operation, fixture)
                val response = client.get(path) { bearerAuth("synthetic-admin") }
                val status = when (kind) { "viewer" -> 200; "expiry", "logout" -> 401; else -> 403 }
                assertTrue(changed.get())
                assertEquals(status, response.status.value, "$kind $phase $operation ${response.bodyAsText()}")
                if (status != 200) {
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    assertEquals(setOf("error"), body.keys)
                    val error = body.getValue("error").jsonObject
                    assertEquals(JsonPrimitive(if (status == 401) "authentication_required" else "workspace_forbidden"), error["code"])
                    if (operation == "evidence") assertEquals(setOf("code", "message"), error.keys)
                    else {
                        assertEquals(JsonArray(emptyList()), error["field_errors"])
                        assertEquals(JsonPrimitive(false), error["retryable"])
                    }
                }
                assertEquals(before, currentProjectionDatabaseState(fixture.db, fixture.workspace))
            } finally {
                fixture.db.write { c ->
                    c.prepareStatement("DELETE FROM user_sessions WHERE token_digest=?").use { it.setString(1, tokenDigest("synthetic-admin")); it.executeUpdate() }
                    restore(c, "user_sessions", session)
                    c.prepareStatement("DELETE FROM workspace_memberships WHERE workspace_id=? AND user_id='admin'").use { it.setString(1, fixture.workspace); it.executeUpdate() }
                    restore(c, "workspace_memberships", membership)
                }
            }
        } } }
    }

    @Test fun revokedMembershipCannotReturnAnAlreadyReadSnapshot() = authorizationChange("revoke")
    @Test fun workspaceSwitchCannotReturnAnAlreadyReadSnapshot() = authorizationChange("workspace")
    @Test fun logoutCannotReturnAnAlreadyReadSnapshot() = authorizationChange("logout")
    @Test fun expiredSessionCannotReturnAnAlreadyReadSnapshot() = authorizationChange("expiry")
    @Test fun viewerDowngradeStillAllowsBothCurrentReadOperations() = authorizationChange("viewer")

    @Test fun requestPrincipalCannotSilentlyChangeUserOrWorkspace() = CurrentProjectionFixture().use { fixture ->
        val principal = ReadServices(fixture.db).requireContext("synthetic-admin")
        val service = CurrentProjectionReadService(fixture.db)
        for (changed in listOf(principal.copy(workspaceId = "fixture-a"), principal.copy(userId = "viewer"))) {
            assertTrue(assertFailsWith<AccessFailure> { service.location("synthetic-admin", fixture.identifier, changed) }.forbidden)
            assertTrue(assertFailsWith<AccessFailure> { service.detail("synthetic-admin", fixture.projectionId, changed) }.forbidden)
            assertTrue(assertFailsWith<AccessFailure> { service.evidence("synthetic-admin", fixture.projectionId, changed) }.forbidden)
        }
    }

    @Test fun invalidIdentifiersPrecedeUnavailableDataAndProgrammingFailuresRemainInternal() = CurrentProjectionFixture().use { fixture ->
        val service = CurrentProjectionReadService(fixture.db)
        fixture.execute("UPDATE cash_projection_states SET availability='uninitialized' WHERE workspace_id=?", fixture.workspace)
        for (identifier in listOf(null, true, 1.0, -1L, 0L, "١", "9223372036854775808")) {
            assertTrue(assertFailsWith<CurrentProjectionNotFound> { service.location("synthetic-admin", identifier) }.record)
        }
        for (identifier in listOf("", "🦝".repeat(129))) assertFalse(assertFailsWith<CurrentProjectionNotFound> {
            service.detail("synthetic-admin", identifier)
        }.record)
        assertFailsWith<CurrentProjectionUnavailable> { service.detail("synthetic-admin", "🦝".repeat(128)) }
        fixture.execute("UPDATE cash_projection_states SET availability='ready' WHERE workspace_id=?", fixture.workspace)
        val failure = IllegalStateException("private implementation failure")
        val broken = CurrentProjectionReadService(fixture.db, afterStateRead = { throw failure })
        assertSame(failure, assertFailsWith<IllegalStateException> { broken.detail("synthetic-admin", fixture.projectionId) })
        assertSame(failure, assertFailsWith<IllegalStateException> { broken.location("synthetic-admin", fixture.identifier) })
        assertSame(failure, assertFailsWith<IllegalStateException> { broken.evidence("synthetic-admin", fixture.projectionId) })
    }

    private fun path(operation: String, fixture: CurrentProjectionFixture): String = when (operation) {
        "location" -> "/api/v1/cash-records/${fixture.identifier}/projection"
        "detail" -> "/api/v1/cash-projections/${fixture.projectionId}"
        else -> "/api/v1/evidence/cash-projections/${fixture.projectionId}"
    }
}
