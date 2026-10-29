package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.serialization.json.*

class CurrentProjectionSourceMigrationTest {
    @Test fun deletedAndCrossWorkspaceTargetsCannotBeReadOrRelocated() = CurrentProjectionFixture().use { fixture -> testApplication {
        val db = fixture.db
        fixture.useEditor()
        application { financeModule(db) }
        val location = client.get("/api/v1/cash-records/${fixture.identifier}/projection") { bearerAuth("synthetic-admin") }
        assertEquals(200, location.status.value, location.bodyAsText())
        val projection = Json.parseToJsonElement(location.bodyAsText()).jsonObject.getValue("projection_id").jsonPrimitive.content
        fixture.execute("UPDATE cash_transactions SET deleted_at='2026-08-12 03:04:05.123456' WHERE workspace_id=? AND id=?", fixture.workspace, fixture.identifier)
        for (path in listOf("/api/v1/cash-records/${fixture.identifier}/projection", "/api/v1/cash-projections/$projection")) {
            assertEquals(404, client.get(path) { bearerAuth("synthetic-admin") }.status.value)
        }
        CurrentProjectionFixture().use { other ->
            other.execute("DELETE FROM cash_transaction_components WHERE workspace_id=? AND cash_transaction_id=?", other.workspace, other.identifier)
            other.execute("UPDATE cash_transactions SET state='draft',amount=NULL,currency=NULL,occurred_at=NULL,record_type=NULL,record_subtype=NULL WHERE workspace_id=? AND id=?", other.workspace, other.identifier)
            CashProjectionService(db, other.workspace).rebuild()
            val otherProjection = CurrentProjectionReadService(db).location("synthetic-admin", other.identifier).getValue("projection_id").jsonPrimitive.content
            fixture.workspaces.select("synthetic-admin", fixture.workspace)
            assertEquals(1, other.execute("DELETE FROM workspace_memberships WHERE workspace_id=? AND user_id='admin'", other.workspace))
            try {
                val before = currentProjectionDatabaseState(db, fixture.workspace)
                for (path in listOf("/api/v1/cash-records/${other.identifier}/projection", "/api/v1/cash-projections/$otherProjection")) {
                    assertEquals(404, client.get(path) { bearerAuth("synthetic-admin") }.status.value)
                }
                assertEquals(before, currentProjectionDatabaseState(db, fixture.workspace))
            } finally {
                db.write { c -> deletionInsert(c, db, "workspace_memberships", linkedMapOf("workspace_id" to other.workspace,
                    "user_id" to "admin", "role" to "admin", "created_at" to deletionTime)) }
            }
        }
    } }

    @Test fun unreadyCurrentDatasetDoesNotReturnOldProjectionOrLocation() = CurrentProjectionFixture().use { fixture -> testApplication {
        fixture.useEditor()
        application { financeModule(fixture.db) }
        fixture.execute("UPDATE cash_projection_states SET availability='uninitialized' WHERE workspace_id=?", fixture.workspace)
        val before = currentProjectionDatabaseState(fixture.db, fixture.workspace)
        for (path in listOf("/api/v1/cash-records/${fixture.identifier}/projection", "/api/v1/cash-projections/${fixture.projectionId}")) {
            val response = client.get(path) { bearerAuth("synthetic-admin") }
            assertEquals(503, response.status.value, response.bodyAsText())
            assertEquals(JsonPrimitive(true), Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject["retryable"])
        }
        assertEquals(before, currentProjectionDatabaseState(fixture.db, fixture.workspace))
    } }

    @Test fun activeMemberWithoutCurrentProjectionFailsClosed() = CurrentProjectionFixture().use { fixture -> testApplication {
        fixture.useEditor()
        application { financeModule(fixture.db) }
        assertEquals(1, fixture.execute("DELETE FROM cash_projection_members WHERE workspace_id=? AND cash_transaction_id=?", fixture.workspace, fixture.identifier))
        val before = currentProjectionDatabaseState(fixture.db, fixture.workspace)
        for (path in listOf("/api/v1/cash-records/${fixture.identifier}/projection", "/api/v1/cash-projections/${fixture.projectionId}")) {
            val response = client.get(path) { bearerAuth("synthetic-admin") }
            assertEquals(503, response.status.value, response.bodyAsText())
            assertEquals(JsonPrimitive(true), Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject["retryable"])
        }
        assertEquals(before, currentProjectionDatabaseState(fixture.db, fixture.workspace))
    } }

    @Test fun businessHiddenProjectionOnlyOpensWhenCurrentAttentionMakesItMaintainable() = CurrentProjectionFixture().use { fixture -> testApplication {
        fixture.useEditor()
        application { financeModule(fixture.db) }
        fixture.execute("UPDATE cash_transactions SET amount=0 WHERE workspace_id=? AND id=?", fixture.workspace, fixture.identifier)
        fixture.execute("UPDATE cash_transaction_components SET amount=0 WHERE workspace_id=? AND cash_transaction_id=?", fixture.workspace, fixture.identifier)
        CashProjectionService(fixture.db, fixture.workspace).rebuild()
        val location = client.get("/api/v1/cash-records/${fixture.identifier}/projection") { bearerAuth("synthetic-admin") }
        assertEquals(200, location.status.value, location.bodyAsText())
        val current = Json.parseToJsonElement(location.bodyAsText()).jsonObject
        assertEquals(JsonPrimitive(0), current["attention_count"])
        val path = "/api/v1/cash-projections/" + current.getValue("projection_id").jsonPrimitive.content
        val hidden = client.get(path) { bearerAuth("synthetic-admin") }
        assertEquals(404, hidden.status.value)
        assertEquals(JsonPrimitive("projection_not_found"), Json.parseToJsonElement(hidden.bodyAsText()).jsonObject.getValue("error").jsonObject["code"])
        fixture.addIssue()
        CashProjectionService(fixture.db, fixture.workspace).rebuild()
        val before = currentProjectionDatabaseState(fixture.db, fixture.workspace)
        val response = client.get(path) { bearerAuth("synthetic-admin") }
        assertEquals(200, response.status.value, response.bodyAsText())
        val detail = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val projection = detail.getValue("projection").jsonObject
        assertEquals(JsonPrimitive(false), projection["visible"])
        assertEquals(JsonPrimitive("pending"), projection["confirmation_status"])
        assertEquals(0, BigDecimal(projection.getValue("amount").jsonPrimitive.content).compareTo(BigDecimal.ZERO))
        assertTrue(projection.getValue("attention_count").jsonPrimitive.long > 0)
        assertTrue(detail.getValue("projection_version").jsonPrimitive.long > current.getValue("projection_version").jsonPrimitive.long)
        assertFalse("私有" in response.bodyAsText())
        assertEquals(before, currentProjectionDatabaseState(fixture.db, fixture.workspace))
    } }

    @Test fun currentProjectionReadStorageFaultHasSafeRetryableError() = CurrentProjectionFixture().use { fixture -> testApplication {
        fixture.useEditor()
        val fault = AtomicBoolean()
        application { financeModule(fixture.db, currentProjections = CurrentProjectionReadService(fixture.db,
            afterStateRead = { if (fault.get()) throw StorageFailure("storage.connect") })) }
        val location = client.get("/api/v1/cash-records/${fixture.identifier}/projection") { bearerAuth("synthetic-admin") }
        assertEquals(200, location.status.value, location.bodyAsText())
        val projection = Json.parseToJsonElement(location.bodyAsText()).jsonObject.getValue("projection_id").jsonPrimitive.content
        val before = currentProjectionDatabaseState(fixture.db, fixture.workspace)
        fault.set(true)
        for (path in listOf("/api/v1/cash-projections/$projection", "/api/v1/cash-records/${fixture.identifier}/projection")) {
            val response = client.get(path) { bearerAuth("synthetic-admin") }
            assertEquals(503, response.status.value, response.bodyAsText())
            val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
            assertEquals(JsonPrimitive("projection.unavailable"), error["code"])
            assertEquals(JsonPrimitive(true), error["retryable"])
            assertEquals(JsonArray(emptyList()), error["field_errors"])
            assertFalse("storage.connect" in response.bodyAsText())
        }
        assertEquals(before, currentProjectionDatabaseState(fixture.db, fixture.workspace))
    } }
}
