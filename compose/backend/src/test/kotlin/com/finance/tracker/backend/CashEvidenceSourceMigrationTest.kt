package com.finance.tracker.backend

import java.math.BigDecimal
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashEvidenceSourceMigrationTest {
    private class Fixture : AutoCloseable {
        private val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        private val workspaces = WorkspaceService(db)
        private val name = "收支证据-${UUID.randomUUID()}"
        val workspace = workspaces.create("synthetic-admin", name).activeWorkspaceId!!
        val accounts = listOf("日常账户" to "cash", "信用账户" to "loan", "投资账户" to "security").map { (name, type) ->
            db.write { c -> deletionInsert(c, db, "accounts", linkedMapOf(
                "workspace_id" to workspace, "name" to name, "type" to type, "active" to true, "revision" to 1L,
                "currencies" to JsonArray(listOf(JsonPrimitive("CNY"))), "metadata_json" to JsonObject(emptyMap()),
                "created_at" to deletionTime, "updated_at" to deletionTime,
            ))!! }
        }
        val categories = listOf("餐饮", "日用", "收入").map { CategoryService(db).create("synthetic-admin", it).id }
        val root = fact("-12.50", "2026-07-03T09:00:00Z", "cash-003", 0)
        val other = fact("-100", "2026-07-02T12:00:00Z", "cash-002", 1)
        val income = fact("2000", "2026-07-01T08:00:00Z", "cash-001", 2)

        fun fact(amount: String, at: String, record: String, kind: Int): Long = db.write { c ->
            val account = accounts[if (kind == 1) 1 else 0]
            val id = deletionInsert(c, db, "cash_transactions", linkedMapOf(
                "workspace_id" to workspace, "account_id" to account, "state" to "posted", "record_origin" to "legacy",
                "row_revision" to 1L, "cash_granularity" to "atomic", "occurred_at" to Instant.parse(at),
                "amount" to BigDecimal(amount), "currency" to "CNY", "counterparty" to if (kind == 1) "超市" else if (kind == 2) "工资" else "咖啡店",
                "category_id" to categories[kind.coerceAtMost(2)], "source_type" to "fixture", "record_id" to record,
                "record_type" to if (BigDecimal(amount).signum() > 0) "income" else "consumption", "record_subtype" to "not_applicable",
                "note" to "", "manual_overrides" to JsonObject(emptyMap()), "created_at" to deletionTime,
                "source_payload" to buildJsonObject { put("merchant", "咖啡店"); put("memo", "private token=secret"); put("account", "1234") },
            ))!!
            deletionInsert(c, db, "cash_transaction_components", linkedMapOf(
                "id" to id, "workspace_id" to workspace, "cash_transaction_id" to id, "account_id" to account,
                "amount" to BigDecimal(amount), "currency" to "CNY", "ordinal" to 0L, "label" to "", "source_key" to "",
                "metadata_json" to JsonObject(emptyMap()), "created_at" to deletionTime,
            ))
            id
        }

        fun relation(primary: Long, secondary: Long, kind: String, state: String, anchor: Long = primary) = db.write { c ->
            for (id in listOf(primary, secondary)) {
                val type = when (kind) {
                    "payment_mirror" -> "consumption"
                    "refund_offset" -> if (id == primary) "consumption" else "refund"
                    else -> if (id == primary) "transfer_out" else "transfer_in"
                }
                c.prepareStatement("UPDATE cash_transactions SET record_type=?,record_subtype=? WHERE workspace_id=? AND id=?").use {
                    it.setString(1, type); it.setString(2, if (kind == "transfer_pair") "ordinary_transfer" else "not_applicable")
                    it.setString(3, workspace); it.setLong(4, id); it.executeUpdate()
                }
            }
            deletionInsert(c, db, "transaction_relations", linkedMapOf(
                "workspace_id" to workspace, "kind" to kind, "subtype" to "", "state" to state, "active" to true,
                "row_revision" to 1L, "primary_transaction_id" to primary, "secondary_transaction_id" to secondary, "anchor_transaction_id" to anchor,
                "primary_component_id" to primary, "secondary_component_id" to secondary, "anchor_component_id" to anchor,
                "primary_role" to if (kind == "transfer_pair") "outgoing" else "expense",
                "secondary_role" to if (kind == "refund_offset") "refund" else if (kind == "transfer_pair") "incoming" else "expense",
                "applied_amount" to BigDecimal(if (kind == "transfer_pair") "0" else "12.50"),
                "rule_id" to "mirror.fixture.v1", "evidence" to JsonObject(emptyMap()), "decision_source" to "rule", "created_by" to "system",
                "created_at" to deletionTime, "decided_by" to "", "decision_reason" to "",
            ))!!
        }

        fun rebuild() { CashProjectionService(db, workspace).rebuild() }
        fun read(): JsonObject = CurrentProjectionReadService(db).evidence("synthetic-admin", "cash:$root")
        override fun close() { workspaces.delete("synthetic-admin", name); workspaces.select("synthetic-admin", "fixture-a") }
    }

    @Test fun sourceEvidenceReturnsRootMembersAndExplicitAbsence() = Fixture().use { fixture ->
        fixture.rebuild()
        val evidence = fixture.read()
        val root = evidence.getValue("root_record").jsonObject
        assertEquals(JsonPrimitive("cash:${fixture.root}"), evidence.getValue("projection").jsonObject["projection_id"])
        assertEquals(JsonPrimitive(fixture.root.toString()), root["id"])
        assertEquals(JsonPrimitive("日常账户"), root.getValue("account").jsonObject["name"])
        assertFalse("source_snapshot" in root)
        assertEquals(listOf(fixture.root.toString()), evidence.getValue("members").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(JsonArray(listOf(JsonPrimitive("root"))), evidence.getValue("members").jsonArray.single().jsonObject["roles"])
        for (key in listOf("accepted_relations", "inactive_relation_hints", "refund_timeline")) assertEquals(JsonArray(emptyList()), evidence[key])
    }

    @Test fun sourceMirrorEvidenceKeepsMembersEndpointsAndBoundedReadQueries() = Fixture().use { fixture ->
        fixture.db.write { c ->
            for (table in listOf("cash_transactions", "cash_transaction_components")) c.prepareStatement("UPDATE $table SET amount='-12.50' WHERE workspace_id=? AND id=?").use {
                it.setString(1, fixture.workspace); it.setLong(2, fixture.other); assertEquals(1, it.executeUpdate())
            }
        }
        fixture.relation(fixture.root, fixture.other, "payment_mirror", "confirmed")
        fixture.rebuild()
        val statements = mutableListOf<String>()
        val evidence = fixture.db.read { connection ->
            val observed = java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
                if (method.name == "prepareStatement") statements.add(arguments!![0] as String)
                try { method.invoke(connection, *(arguments ?: emptyArray())) }
                catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
            } as Connection
            CurrentProjectionReadRepository(observed, fixture.workspace, fixture.db.sqlite) {}.evidence("cash:${fixture.root}")
        }
        assertEquals(listOf(fixture.root.toString(), fixture.other.toString()), evidence.getValue("members").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        val relation = evidence.getValue("accepted_relations").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("payment_mirror"), relation["kind"])
        assertEquals(JsonPrimitive("mirror.fixture.v1"), relation["rule_id"])
        assertEquals(JsonPrimitive(fixture.root.toString()), relation.getValue("primary_record").jsonObject["id"])
        assertEquals(JsonPrimitive(fixture.other.toString()), relation.getValue("secondary_record").jsonObject["id"])
        val selects = statements.count { it.trimStart().startsWith("select", true) }
        assertTrue(selects in 1..9, statements.joinToString("\n"))
    }

    @Test fun sourceFullRefundStaysVisibleAndKeepsTimelineWithoutSourceSnapshot() = Fixture().use { fixture ->
        val refund = fixture.fact("12.50", "2026-07-04T00:00:00Z", "cash-004", 0)
        fixture.db.write { c -> c.prepareStatement("UPDATE cash_transactions SET category_id=NULL WHERE id=?").use { it.setLong(1, refund); it.executeUpdate() } }
        fixture.relation(fixture.root, refund, "refund_offset", "confirmed", anchor = refund)
        fixture.rebuild()
        val evidence = fixture.read()
        val projection = evidence.getValue("projection").jsonObject
        val root = evidence.getValue("root_record").jsonObject
        assertEquals(JsonPrimitive(true), projection["visible"]); assertEquals(JsonNull, projection["hidden_reason"])
        assertFalse("source_snapshot" in root)
        assertEquals(JsonPrimitive("consumption"), root["record_type"])
        assertEquals(JsonPrimitive("not_applicable"), root["record_subtype"])
        assertEquals(JsonPrimitive("日常账户"), root["account_name"])
        assertEquals(listOf(fixture.root.toString(), refund.toString()), evidence.getValue("members").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(Json.parseToJsonElement("""[{"record_id":"cash-004","occurred_at":"2026-07-04T00:00:00+00:00","amount":"12.5","currency":"CNY","source_type":"fixture"}]"""), evidence["refund_timeline"])
    }

    @Test fun sourceInactiveHintsExposeOnlyCashEndpoints() = Fixture().use { fixture ->
        fixture.relation(fixture.root, fixture.other, "transfer_pair", "draft")
        fixture.rebuild()
        val hint = fixture.read().getValue("inactive_relation_hints").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("pending_review"), hint["status"])
        assertEquals(JsonPrimitive(fixture.root.toString()), hint.getValue("primary_record").jsonObject["id"])
        assertEquals(JsonPrimitive(fixture.other.toString()), hint.getValue("secondary_record").jsonObject["id"])
    }

    @Test fun sourceHttpEvidenceKeepsEnvelopeAndStringComponentIdentifiers() = Fixture().use { fixture -> testApplication {
        fixture.rebuild()
        application { financeModule(fixture.db) }
        val response = client.get("/api/v1/evidence/cash-projections/cash:${fixture.root}") { bearerAuth("synthetic-admin") }
        assertEquals(200, response.status.value)
        val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(JsonPrimitive("cash:${fixture.root}"), payload.getValue("projection").jsonObject["projection_id"])
        val root = payload.getValue("root_record").jsonObject
        assertEquals(JsonPrimitive(fixture.root.toString()), root["id"])
        assertEquals(JsonPrimitive("咖啡店"), root["counterparty"])
        assertFalse("record_id" in root); assertFalse("source_snapshot" in root)
        assertEquals(JsonArray(listOf(JsonPrimitive("root"))), payload.getValue("members").jsonArray[0].jsonObject["roles"])
        for (key in listOf("accepted_relations", "inactive_relation_hints", "refund_timeline")) assertEquals(JsonArray(emptyList()), payload[key])
        val component = root.getValue("components").jsonArray.first().jsonObject
        assertTrue(component.getValue("id").jsonPrimitive.isString)
        assertTrue(component.getValue("cash_transaction_id").jsonPrimitive.isString)
    } }

    @Test fun sourceHttpEvidenceKeepsNotFoundUnavailableAndOmittedSnapshots() = Fixture().use { fixture -> testApplication {
        application { financeModule(fixture.db) }
        fun code(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject.getValue("code")
        fixture.db.write { c -> c.prepareStatement("UPDATE cash_projection_states SET availability='uninitialized' WHERE workspace_id=?").use {
            it.setString(1, fixture.workspace); it.executeUpdate()
        } }
        assertEquals(JsonPrimitive("projection.unavailable"), code(client.get("/api/v1/evidence/cash-projections/cash:${fixture.root}") { bearerAuth("synthetic-admin") }.bodyAsText()))
        fixture.rebuild()
        assertEquals(JsonPrimitive("not_found"), code(client.get("/api/v1/evidence/cash-projections/cash:missing") { bearerAuth("synthetic-admin") }.bodyAsText()))
        val body = Json.parseToJsonElement(client.get("/api/v1/evidence/cash-projections/cash:${fixture.other}") { bearerAuth("synthetic-admin") }.bodyAsText()).jsonObject
        assertFalse("source_snapshot" in body.getValue("root_record").jsonObject)
    } }
}
