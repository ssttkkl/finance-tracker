package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashEvidenceReadMigrationTest {
    @Test fun standaloneEvidenceKeepsFullRecordAndExplicitEmptyRelationsWithoutWrites() = CurrentProjectionFixture().use { fixture -> testApplication {
        application { financeModule(fixture.db) }
        val before = currentProjectionDatabaseState(fixture.db, fixture.workspace)
        val response = client.get("/api/v1/evidence/cash-projections/${fixture.projectionId}") { bearerAuth("synthetic-admin") }
        assertEquals(200, response.status.value, response.bodyAsText())
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val root = body.getValue("root_record").jsonObject
        assertEquals(JsonPrimitive(fixture.identifier.toString()), root["id"])
        assertEquals(JsonPrimitive("账户"), root["account_name"])
        assertEquals(JsonPrimitive("-12.340000000000000001"), root["amount"])
        assertEquals(JsonPrimitive("2026-08-12T03:04:05.123456+00:00"), root["occurred_at"])
        assertFalse("source_snapshot" in root)
        val member = body.getValue("members").jsonArray.single().jsonObject
        assertEquals(root, JsonObject(member - "roles"))
        assertEquals(JsonArray(listOf(JsonPrimitive("root"))), member["roles"])
        val component = root.getValue("components").jsonArray.single().jsonObject
        assertTrue(component.getValue("id").jsonPrimitive.isString)
        assertEquals(JsonPrimitive(fixture.identifier.toString()), component["cash_transaction_id"])
        assertEquals(root["amount"], component["amount"])
        for (key in listOf("accepted_relations", "inactive_relation_hints", "refund_timeline")) assertEquals(JsonArray(emptyList()), body[key])
        assertEquals(JsonNull, body["funding_relation"])
        assertEquals(before, currentProjectionDatabaseState(fixture.db, fixture.workspace))
    } }

    @Test fun hiddenEvidenceRemainsReadableAndKeepsOriginalMissingAndUnavailableErrors() = CurrentProjectionFixture().use { fixture -> testApplication {
        application { financeModule(fixture.db) }
        val path = "/api/v1/evidence/cash-projections/${fixture.projectionId}"
        fixture.execute("UPDATE cash_projections SET visible=false,attention_count=0 WHERE workspace_id=?", fixture.workspace)
        val hidden = client.get(path) { bearerAuth("synthetic-admin") }
        assertEquals(200, hidden.status.value, hidden.bodyAsText())
        assertEquals(JsonPrimitive(false), Json.parseToJsonElement(hidden.bodyAsText()).jsonObject.getValue("projection").jsonObject["visible"])
        fixture.execute("UPDATE cash_transactions SET deleted_at='2026-08-12 03:04:05.123456' WHERE workspace_id=? AND id=?", fixture.workspace, fixture.identifier)
        val missing = client.get(path) { bearerAuth("synthetic-admin") }
        assertEquals(404, missing.status.value)
        assertEquals(Json.parseToJsonElement("""{"error":{"code":"not_found","message":"当前工作区中找不到该收支记录。"}}"""), Json.parseToJsonElement(missing.bodyAsText()))
        fixture.execute("UPDATE cash_projection_states SET availability='uninitialized' WHERE workspace_id=?", fixture.workspace)
        val unavailable = client.get(path) { bearerAuth("synthetic-admin") }
        assertEquals(503, unavailable.status.value)
        assertEquals(Json.parseToJsonElement("""{"error":{"code":"projection.unavailable","message":"账本数据暂不可用，请先完成更新。"}}"""), Json.parseToJsonElement(unavailable.bodyAsText()))
    } }

    @Test fun missingRootMembershipReturnsOriginalNotFoundWithoutRepairingData() = CurrentProjectionFixture().use { fixture -> testApplication {
        application { financeModule(fixture.db) }
        fixture.execute("DELETE FROM cash_projection_members WHERE workspace_id=? AND cash_transaction_id=?", fixture.workspace, fixture.identifier)
        val before = currentProjectionDatabaseState(fixture.db, fixture.workspace)
        val response = client.get("/api/v1/evidence/cash-projections/${fixture.projectionId}") { bearerAuth("synthetic-admin") }
        assertEquals(404, response.status.value, response.bodyAsText())
        assertEquals(JsonPrimitive("not_found"), Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject["code"])
        assertEquals(before, currentProjectionDatabaseState(fixture.db, fixture.workspace))
    } }

    @Test fun invalidStoredFundingTypeStopsTheReadInsteadOfPublishingPartialEvidence() {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_EVIDENCE_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val original = db.read { c -> c.createStatement().use { s -> s.executeQuery("SELECT evidence FROM cash_investment_funding_relations WHERE id=5001").use {
            assertTrue(it.next()); it.getString(1)
        } } }
        fun set(value: String) = db.write { c -> c.prepareStatement("UPDATE cash_investment_funding_relations SET evidence=" +
            (if (db.sqlite) "?" else "CAST(? AS json)") + " WHERE id=5001").use { it.setString(1, value); assertEquals(1, it.executeUpdate()) } }
        try {
            for (value in listOf("[]", "{}", "[1]")) {
                set("""{"cash_record_type":$value}""")
                val before = currentProjectionDatabaseState(db)
                assertFailsWith<IllegalStateException> { CurrentProjectionReadService(db).evidence("synthetic-admin", "cash:1006") }
                assertEquals(before, currentProjectionDatabaseState(db))
            }
        } finally { set(original) }
    }
}
