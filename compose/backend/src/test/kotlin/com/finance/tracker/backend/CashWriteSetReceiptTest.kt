package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CashWriteSetReceiptTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }
    private fun result(rows: Int = 1) = buildJsonObject {
        put("transactions", JsonArray(List(rows) { index -> buildJsonObject {
            put("id", (9007199254740993L + index).toString()); put("row_revision", Long.MAX_VALUE.toString())
            put("component_ids", JsonArray(listOf(JsonPrimitive("9007199254740995"), JsonPrimitive("2"))))
        } }))
        for (field in listOf("source_bindings", "issues", "statement_account_mappings", "relations"))
            put(field, JsonArray(listOf(buildJsonObject { put("id", "3"); put("revision", "4") })))
        put("entries", JsonArray(listOf(buildJsonObject {
            put("id", "条目😀"); put("root_transaction_id", "9007199254740993"); put("identity_transaction_id", "2"); put("revision", "5")
        })))
        put("resolved_issue_ids", JsonArray(listOf(JsonPrimitive("7")))); put("retired_entry_ids", JsonArray(listOf(JsonPrimitive("旧条目"))))
    }
    private fun authenticated(action: (Database, String, String, String) -> Unit) = withCashWriteSetTestWorkspace(backend) { db, ws, _ ->
        val user = UUID.randomUUID().toString(); val token = "receipt-$user"; val at = Instant.parse("2026-11-14T00:00:00Z")
        db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.insertInto(USERS).set(USERS.ID, user).set(USERS.EMAIL, "$user@example.invalid").set(USERS.PASSWORD_HASH, "unused")
                .set(USERS.CREATED_AT, at).execute()
            sql.insertInto(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, ws).set(WORKSPACE_MEMBERSHIPS.USER_ID, user)
                .set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").set(WORKSPACE_MEMBERSHIPS.CREATED_AT, at).execute()
            sql.insertInto(USER_SESSIONS).set(USER_SESSIONS.ID, UUID.randomUUID().toString()).set(USER_SESSIONS.USER_ID, user)
                .set(USER_SESSIONS.TOKEN_DIGEST, tokenDigest(token)).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, ws).set(USER_SESSIONS.CREATED_AT, at)
                .set(USER_SESSIONS.EXPIRES_AT, Instant.parse("2099-01-01T00:00:00Z")).execute()
        }
        try { action(db, ws, token, user) } finally { db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.deleteFrom(CASH_MUTATION_RECEIPTS).where(CASH_MUTATION_RECEIPTS.WORKSPACE_ID.eq(ws)).execute()
            sql.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(user)).execute()
            sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
            sql.deleteFrom(USERS).where(USERS.ID.eq(user)).execute()
        } }
    }
    private fun save(db: Database, ws: String, user: String, value: JsonElement = result(), operation: String = "cash_write_set") = db.write { c ->
        ReceiptRepository(c, ws, db.sqlite).create(user, "original", "a".repeat(64), operation,
            mapOf("status" to "completed", "ledger_seq" to "9007199254740993", "write_set_result" to value), emptyList<Any>())
    }
    @Test fun storedActualIdentitiesSurviveLedgerChangesAndAllPermanentReadPaths() = authenticated { db, ws, token, user ->
        val account = db.read { c -> c.jooq(db.sqlite).select(ACCOUNTS.ID).from(ACCOUNTS).where(ACCOUNTS.WORKSPACE_ID.eq(ws)).fetchOne(ACCOUNTS.ID)!! }
        val transaction = db.write { c ->
            val id = deletionFact(c, db, ws, account, null)
            deletionEntry(c, db, ws, "actual-entry", id, null)
            id
        }
        val value = JsonObject(result() + mapOf(
            "transactions" to JsonArray(listOf(buildJsonObject {
                put("id", transaction.toString()); put("row_revision", "1"); put("component_ids", JsonArray(emptyList()))
            })),
            "entries" to JsonArray(listOf(buildJsonObject {
                put("id", "actual-entry"); put("root_transaction_id", transaction.toString())
                put("identity_transaction_id", transaction.toString()); put("revision", "1")
            })),
        ))
        val first = save(db, ws, user, value); val id = first.getValue("receipt_id").jsonPrimitive.content
        db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.ROW_REVISION, 9L).where(CASH_TRANSACTIONS.ID.eq(transaction)).execute()
            sql.deleteFrom(CASH_ENTRY_MEMBERS).where(CASH_ENTRY_MEMBERS.WORKSPACE_ID.eq(ws)).execute()
            sql.deleteFrom(CASH_ENTRIES).where(CASH_ENTRIES.WORKSPACE_ID.eq(ws)).execute()
            sql.deleteFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.ID.eq(transaction)).execute()
            c.markLedgerChanged(ws)
        }
        val service = ReceiptReadService(db)
        assertEquals(value, service.receipt(token, id)["write_set_result"])
        assertEquals(value, service.receiptByKey(token, "original")["write_set_result"])
        assertEquals(first["ledger_seq"], service.receipt(token, id)["ledger_seq"])
        testApplication {
            application { financeModule(db) }
            for (path in listOf("/api/v1/cash-mutations/receipts/$id", "/api/v1/cash-mutations/receipts/by-key?key=original")) {
                val response = client.get(path) { bearerAuth(token) }
                assertEquals(200, response.status.value, response.bodyAsText())
                assertEquals(value, Json.parseToJsonElement(response.bodyAsText()).jsonObject["write_set_result"])
            }
        }
        db.read { c ->
            val repo = ReceiptRepository(c, ws, db.sqlite)
            assertEquals(first, repo.get(user, id)); assertEquals(first, repo.lookupKey(user, "original"))
            assertEquals(first, repo.replay(user, "original", "a".repeat(64), "cash_write_set"))
        }
    }
    @Test fun permanentResultPagesAcceptOriginalWorkspaceAndReturnStoredExactOutcomes() = authenticated { db, ws, token, user ->
        val receipt = db.write { c -> ReceiptRepository(c, ws, db.sqlite).create(user, "result-pages", "c".repeat(64), "cash_write_set",
            mapOf("status" to "completed", "ledger_seq" to "9", "write_set_result" to result(2)),
            listOf(
                mapOf("result_kind" to "row", "item_ref" to "row:0", "transaction_id" to "9007199254740993", "outcome" to "created"),
                mapOf("result_kind" to "row", "item_ref" to "row:1", "transaction_id" to "9007199254740994", "outcome" to "unchanged"),
                mapOf("result_kind" to "relation", "item_ref" to "relation:0", "relation_id" to "3", "outcome" to "duplicate"))) }
        val id = receipt.getValue("receipt_id").jsonPrimitive.content
        testApplication {
            application { financeModule(db) }
            val prefix = "/api/v1/cash-mutations/receipts/$id"
            suspend fun page(kind: String, after: String = "0") = client.get("$prefix/$kind?workspace_id=$ws&limit=1&after=$after") { bearerAuth(token) }.let {
                assertEquals(200, it.status.value, it.bodyAsText())
                Json.decodeFromString<com.finance.tracker.business.contracts.CashMutationResultPageDto>(it.bodyAsText())
            }
            val first = page("rows"); val row = first.items.single()
            assertEquals("9007199254740993", row.transactionId); assertEquals("created", row.outcome); assertEquals("row:0", row.itemRef)
            assertEquals(row.resultId, first.nextAfter)
            val last = page("rows", first.nextAfter!!)
            assertEquals("9007199254740994", last.items.single().transactionId); assertEquals("unchanged", last.items.single().outcome)
            assertNull(last.nextAfter)
            val relation = page("relations").items.single()
            assertEquals("3", relation.relationId); assertEquals("duplicate", relation.outcome); assertEquals("relation:0", relation.itemRef)
            assertEquals(403, client.get("$prefix/rows?workspace_id=other") { bearerAuth(token) }.status.value)
            val invalid = client.get("$prefix/rows?workspace_id=$ws&workspace_id=$ws") { bearerAuth(token) }
            assertEquals(422, invalid.status.value)
            assertEquals("import_payload_invalid", Json.parseToJsonElement(invalid.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        }
    }
    @Test fun largeGeneratedIdentityResultDoesNotInheritTheLegacyOneMiBSummaryLimit() = authenticated { db, ws, token, user ->
        val value = result(12000)
        assertTrue(value.toString().length > 1024 * 1024)
        val first = save(db, ws, user, value)
        assertEquals(value, ReceiptReadService(db).receiptByKey(token, "original")["write_set_result"])
        assertEquals(value, first["write_set_result"])
        val overhead = receiptAsciiJson(normalizeReceiptSummary(mapOf("legacy_by_account" to mapOf("" to 1)))).length
        assertEquals("mutation_summary_invalid", assertFailsWith<IllegalArgumentException> {
            normalizeReceiptSummary(mapOf("write_set_result" to value, "legacy_by_account" to mapOf("x".repeat(1024 * 1024 - overhead + 1) to 1)))
        }.message)
    }
    @Test fun invalidOrWrongOperationResultsNeverCreateAReceipt() = authenticated { db, ws, _, user ->
        for (value in listOf(JsonNull, JsonObject(result() - "issues"), JsonObject(result() + ("source_payload" to JsonPrimitive("private"))),
            JsonObject(result() + ("resolved_issue_ids" to JsonArray(listOf(JsonPrimitive(1))))))) {
            assertEquals("mutation_summary_invalid", assertFailsWith<IllegalArgumentException> { save(db, ws, user, value) }.message)
        }
        assertEquals("mutation_summary_invalid", assertFailsWith<IllegalArgumentException> { save(db, ws, user, operation = "import") }.message)
        db.read { c -> assertNull(ReceiptRepository(c, ws, db.sqlite).byKey(user, "original")) }
    }
    @Test fun corruptedStoredResultsFailInsteadOfBeingDroppedOrRebuilt() = authenticated { db, ws, token, user ->
        val old = db.write { c -> ReceiptRepository(c, ws, db.sqlite).create(user, "original", "a".repeat(64), "cash_write_set", emptyMap<String, Any>(), emptyList<Any>()) }
        val id = old.getValue("receipt_id").jsonPrimitive.content
        for (invalid in listOf(JsonNull, JsonObject(result() - "entries"), JsonObject(result() + ("resolved_issue_ids" to JsonArray(listOf(JsonPrimitive(7))))))) {
            db.write { c -> c.jooq(db.sqlite).update(CASH_MUTATION_RECEIPTS).set(CASH_MUTATION_RECEIPTS.RESULT_SUMMARY,
                buildJsonObject { put("write_set_result", invalid) }).where(CASH_MUTATION_RECEIPTS.ID.eq(id)).execute() }
            val service = ReceiptReadService(db)
            assertFailsWith<ReceiptUnavailable> { service.receipt(token, id) }
            assertFailsWith<ReceiptUnavailable> { service.receiptByKey(token, "original") }
            db.read { c -> assertFailsWith<IllegalArgumentException> { ReceiptRepository(c, ws, db.sqlite).replay(user, "original", "a".repeat(64), "cash_write_set") } }
        }
    }
    @Test fun normalizationDetachesCallerContainersAndOldReceiptsRemainAbsent() = authenticated { db, ws, token, user ->
        val fields = result().toMutableMap()
        val input = JsonObject(fields)
        val normalized = normalizeReceiptSummary(mapOf("write_set_result" to input))
        fields.clear()
        assertEquals(result(), normalized["write_set_result"])
        val saved = save(db, ws, user, normalized.getValue("write_set_result"))
        val old = db.write { c -> ReceiptRepository(c, ws, db.sqlite).create(user, "old", "b".repeat(64), "import", emptyMap<String, Any>(), emptyList<Any>()) }
        assertFalse("write_set_result" in old)
        assertFalse("write_set_result" in ReceiptReadService(db).receiptByKey(token, "old"))
        assertEquals(result(), ReceiptReadService(db).receipt(token, saved.getValue("receipt_id").jsonPrimitive.content)["write_set_result"])
    }
    @Test fun resultUsesTheOriginalCommandUTF8BudgetBeforeEncodingLargeStrings() {
        fun containing(text: String) = JsonObject(result() + ("retired_entry_ids" to JsonArray(listOf(JsonPrimitive(text)))))
        val overhead = containing("").toString().encodeToByteArray().size
        val room = CashImportProtocol.MAX_PLAN_INPUT_BYTES - overhead
        val unit = "\n\"\\界😀\u0001"
        val unitBytes = JsonPrimitive(unit).toString().encodeToByteArray().size - 2
        val text = unit.repeat(room / unitBytes) + "x".repeat(room % unitBytes)
        val normalized = normalizeReceiptSummary(mapOf("write_set_result" to containing(text)))
        assertEquals(text, normalized.getValue("write_set_result").jsonObject.getValue("retired_entry_ids").jsonArray.single().jsonPrimitive.content)
        assertEquals("mutation_summary_invalid", assertFailsWith<IllegalArgumentException> {
            normalizeReceiptSummary(mapOf("write_set_result" to containing(text + "x")))
        }.message)
    }
}
