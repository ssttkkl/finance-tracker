package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportPlanHttpTest {
    private fun database() = Json.parseToJsonElement(Path.of(requireNotNull(
        System.getenv("FT_KOTLIN_PLAN_BUILD_CONTRACT_DIR"))).resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    private fun seed(store: ParsedImportStore): Pair<String, Map<String, Any?>> {
        val document = ParsedCashDocument("alipay", "csv:0", "native-v1", listOf(ParsedCashRow(1,
            mutableMapOf("amount" to "-1.00", "currency" to "CNY"), "fixture", "1")),
            "fixture", "fixture", CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), 1, 1)
        val file = ParsedImportFile("f1", "a".repeat(64), "parsed", document,
            clientFileRef = "client-f1", filename = "synthetic.csv", size = 100, sourceType = "alipay")
        val session = store.create("plan-a", "admin", listOf(file))
        val golden = Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-import-plan-views.json")!!
            .bufferedReader().use { it.readText() }).jsonObject
        @Suppress("UNCHECKED_CAST") val original = cashSourceValue(golden.getValue("plan")) as Map<String, Any?>
        val planId = "ip_${session.sessionId}." + "b".repeat(16)
        val plan = original + mapOf("plan_id" to planId, "session_id" to session.sessionId,
            "session_revision" to 2, "expires_at" to session.expiresAt.toString())
        store.replace(session.sessionId, "plan-a", "admin", 1, session.files, emptyMap(), plan)
        return planId to plan
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.getPlan(path: String, token: String = "synthetic-admin") =
        client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun code(response: io.ktor.client.statement.HttpResponse) =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
            .getValue("code").jsonPrimitive.content

    @Test fun storedPlanReadRoutesUseFrozenPublicViewsAndRejectStaleOrForeignPlans() = testApplication {
        val db = database()
        val store = InMemoryParsedImportStore()
        val (planId, plan) = seed(store)
        application { financeModule(db, cashImports = CashImportHttpService(db, store)) }
        val before = detailDatabaseState(db)
        val root = "/api/v1/cash-import/plans/$planId"
        val view = getPlan(root)
        assertEquals(200, view.status.value, view.bodyAsText())
        assertEquals(sourceJson(CashImportPlanViews.view(plan)), Json.parseToJsonElement(view.bodyAsText()))
        assertEquals(view.bodyAsText(), getPlan(root).bodyAsText())
        val page = getPlan("$root/items?limit=1&start_index=1")
        assertEquals(200, page.status.value, page.bodyAsText())
        assertEquals(sourceJson(CashImportPlanViews.page(plan, "items", startIndex = 1, limit = 1, indexed = true)),
            Json.parseToJsonElement(page.bodyAsText()))
        assertEquals(page.bodyAsText(), getPlan("$root/items?limit=1&start_index=1").bodyAsText())
        val detail = getPlan("$root/items/it_1")
        assertEquals(200, detail.status.value, detail.bodyAsText())
        val detailInput = (plan.getValue("item_details") as Map<*, *>)["it_1"]!!
        val detailExpected = CashImportPlanViews.publicDetail(CashImportPlanViews.resolveDetail(plan, detailInput)) +
            ("preview_item_id" to "it_1")
        assertEquals(sourceJson(detailExpected), Json.parseToJsonElement(detail.bodyAsText()))
        val processing = getPlan("$root/processing-details?limit=1")
        assertEquals(200, processing.status.value, processing.bodyAsText())
        assertEquals(sourceJson(CashImportPlanViews.page(plan, "processing_details", limit = 1)),
            Json.parseToJsonElement(processing.bodyAsText()))
        assertEquals("plan_cursor_invalid", code(getPlan("$root/items?cursor=broken!")))
        assertEquals("import_payload_invalid", code(getPlan("$root/items?limit=0")))
        assertEquals("plan_item_not_found", code(getPlan("$root/items/missing")))
        assertEquals("plan_not_found", code(getPlan("/api/v1/cash-import/plans/nope")))
        assertEquals("session_not_found", code(getPlan(root, "synthetic-viewer")))
        assertEquals(before, detailDatabaseState(db))
        val current = store.get(CashImportPlanViews.sessionId(planId), "plan-a", "admin")
        val replacementId = "ip_${current.sessionId}." + "c".repeat(16)
        store.replace(current.sessionId, "plan-a", "admin", current.revision, current.files, emptyMap(),
            plan + ("plan_id" to replacementId))
        assertEquals("plan_changed", code(getPlan(root)))
        val replacement = store.get(current.sessionId, "plan-a", "admin")
        store.replace(replacement.sessionId, "plan-a", "admin", replacement.revision, replacement.files, emptyMap(),
            plan + mapOf("plan_id" to replacementId, "item_details" to "damaged"))
        val damaged = getPlan("/api/v1/cash-import/plans/$replacementId/items/it_1")
        assertEquals(503, damaged.status.value)
        assertEquals("plan_build_failed", code(damaged))
        val safeError = Json.parseToJsonElement(damaged.bodyAsText()).jsonObject.getValue("error").jsonObject
        assertTrue(safeError.getValue("retryable").jsonPrimitive.boolean)
        assertNotNull(safeError["diagnostic"])
        val receipt = db.write { connection ->
            ReceiptRepository(connection, "plan-a", db.sqlite).create(user = "admin",
                key = "cash-import-plan-read-" + java.util.UUID.randomUUID(), requestDigest = "a".repeat(64),
                operation = "import", resultSummary = mapOf("status" to "no_changes"),
                results = emptyList<Any?>(), planId = planId,
                sessionDigest = MessageDigest.getInstance("SHA-256").digest(current.sessionId.encodeToByteArray())
                    .joinToString("") { "%02x".format(it) })
        }
        val committed = getPlan(root)
        assertEquals(409, committed.status.value)
        assertEquals("session_committed", code(committed))
        assertEquals(receipt.getValue("receipt_id"), Json.parseToJsonElement(committed.bodyAsText()).jsonObject["receipt_id"])
    }
}
