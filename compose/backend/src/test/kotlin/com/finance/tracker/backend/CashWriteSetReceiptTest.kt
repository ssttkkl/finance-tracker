package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.CASH_IMPORT_RECEIPT_PAGE_MAX_BYTES
import com.finance.tracker.business.contracts.LEDGER_SNAPSHOT_PAGE_MAX_BYTES
import com.finance.tracker.business.contracts.CashImportReceiptSection
import com.finance.tracker.business.contracts.LedgerSnapshotSection
import com.finance.tracker.business.contracts.ReadPageChunk
import com.finance.tracker.business.contracts.ReadPagePosition
import java.util.Base64
import java.util.zip.GZIPInputStream
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
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
    @Test fun originalRepresentationsAndOptInPagesRequireTheSameWorkspace() = authenticated { db, ws, token, user ->
        val original = save(db, ws, user)
        testApplication {
            application { financeModule(db, runCashImportWorker = false) }
            val session = client.get("/api/v1/auth/session") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, session.status)
            assertEquals("1", session.headers["X-FT-Read-Pages"])
            val legacySnapshot = client.get("/api/v1/ledger/snapshot") { bearerAuth(token); parameter("workspace_id", ws) }
            assertEquals(HttpStatusCode.OK, legacySnapshot.status)
            val snapshot = Json.parseToJsonElement(legacySnapshot.bodyAsText()).jsonObject
            val legacyReceipt = client.get("/api/v1/cash-import/write-set/receipt") {
                bearerAuth(token)
                parameter("workspace_id", ws)
                header("Idempotency-Key", "original")
            }
            assertEquals(HttpStatusCode.OK, legacyReceipt.status)
            assertEquals(original["receipt_id"], Json.parseToJsonElement(legacyReceipt.bodyAsText()).jsonObject["receipt_id"])
            for ((path, field, identity) in listOf(
                Triple("/api/v1/ledger/snapshot/pages", "ledger_seq", snapshot.getValue("ledger_seq")),
                Triple("/api/v1/cash-import/write-set/receipt/pages", "receipt_id", original.getValue("receipt_id")),
            )) {
                val first = client.get(path) {
                    bearerAuth(token)
                    parameter("workspace_id", ws)
                    if (field == "receipt_id") header("Idempotency-Key", "original")
                }
                assertEquals(HttpStatusCode.OK, first.status)
                val page = Json.parseToJsonElement(first.bodyAsText()).jsonObject
                assertEquals(JsonPrimitive(ws), page["workspace_id"])
                assertEquals(identity, page[field])
                assertNotNull(page["chunk"])
                val forbidden = client.get(path) {
                    bearerAuth(token)
                    parameter("workspace_id", "$ws-wrong")
                    if (field == "receipt_id") header("Idempotency-Key", "original")
                }
                assertEquals(HttpStatusCode.Forbidden, forbidden.status)
            }
        }
    }

    @Test fun allPagesRebuildTheLegacyRepresentationsAndRejectChangedIdentity() = authenticated { db, ws, token, user ->
        save(db, ws, user, result(1025))
        testApplication {
            application { financeModule(db, runCashImportWorker = false) }
            suspend fun read(path: String, continuation: Map<String, String> = emptyMap()): HttpResponse = client.get(path) {
                bearerAuth(token)
                parameter("workspace_id", ws)
                if (path.endsWith("/pages")) header("Accept-Encoding", "gzip")
                if ("/receipt" in path) header("Idempotency-Key", "original")
                continuation.forEach { (name, value) -> parameter(name, value) }
            }
            suspend fun collect(path: String, pin: String, limit: Int): Triple<JsonObject, Map<String, JsonArray>, Int> {
                val data = linkedMapOf<String, MutableList<JsonElement>>()
                var first: JsonObject? = null
                var continuation = emptyMap<String, String>()
                var requests = 0
                do {
                    val response = read("$path/pages", continuation)
                    assertEquals(HttpStatusCode.OK, response.status)
                    val encoded = response.bodyAsBytes()
                    assertTrue(encoded.size <= limit)
                    val encoding = response.headers["Content-Encoding"]
                    val decoded = when (encoding) {
                        "gzip" -> GZIPInputStream(encoded.inputStream()).use { it.readBytes() }
                        null -> encoded
                        else -> error("Unexpected page encoding")
                    }
                    assertEquals(encoded.size, response.headers["Content-Length"]?.toIntOrNull())
                    assertTrue(decoded.size <= limit)
                    val envelope = Json.parseToJsonElement(decoded.decodeToString()).jsonObject
                    val position = Json.decodeFromJsonElement<ReadPageChunk>(envelope.getValue("chunk"))
                    val fields = JsonObject(envelope - "chunk")
                    if (first == null) first = fields else assertEquals(first, fields)
                    assertEquals(0, position.position.fragmentOffset)
                    assertNull(position.fragment)
                    val values = data.getOrPut(position.position.section, ::mutableListOf)
                    assertEquals(values.size, position.position.offset)
                    values.addAll(position.items)
                    if (position.next?.section != position.position.section) assertEquals(position.total, values.size)
                    continuation = position.next?.let { next -> mapOf(
                        pin to fields.getValue(pin).jsonPrimitive.content,
                        "section" to next.section,
                        "offset" to next.offset.toString(),
                    ) } ?: emptyMap()
                    requests++
                } while (continuation.isNotEmpty())
                return Triple(checkNotNull(first), data.mapValues { JsonArray(it.value) }, requests)
            }
            val snapshotPath = "/api/v1/ledger/snapshot"
            val legacySnapshot = Json.parseToJsonElement(read(snapshotPath).bodyAsText()).jsonObject
            val (snapshotHead, facts, snapshotRequests) = collect(snapshotPath, "ledger_seq", LEDGER_SNAPSHOT_PAGE_MAX_BYTES)
            assertEquals(LedgerSnapshotSection.entries.map { it.wireName }, facts.keys.toList())
            assertEquals(facts.size, snapshotRequests)
            val directory = snapshotHead.getValue("category_directory_revision")
            val assembledSnapshot = JsonObject((snapshotHead - "category_directory_revision") +
                ("facts" to JsonObject(facts + ("category_directory_revision" to directory))))
            assertEquals(legacySnapshot, assembledSnapshot)
            val receiptPath = "/api/v1/cash-import/write-set/receipt"
            val legacyReceipt = Json.parseToJsonElement(read(receiptPath).bodyAsText()).jsonObject
            val (receiptHead, sections, receiptRequests) = collect(receiptPath, "receipt_id", CASH_IMPORT_RECEIPT_PAGE_MAX_BYTES)
            assertEquals(CashImportReceiptSection.entries.map { it.wireName }, sections.keys.toList())
            assertEquals(sections.size + 1, receiptRequests)
            val header = sections.getValue("header").single().jsonObject
            val result = JsonObject(sections - setOf("header", "pending_transaction_ids"))
            assertEquals(legacyReceipt, JsonObject(header.getValue("receipt").jsonObject + ("write_set_result" to result)))
            assertEquals(legacyReceipt["receipt_id"], receiptHead["receipt_id"])

            db.write { connection ->
                check(connection.lockWorkspace(db.sqlite, ws))
                connection.markLedgerChanged(ws)
            }
            assertEquals(HttpStatusCode.Conflict, read("$snapshotPath/pages", mapOf(
                "ledger_seq" to legacySnapshot.getValue("ledger_seq").jsonPrimitive.content,
                "section" to "account_aliases", "offset" to "0",
            )).status)
            assertEquals(HttpStatusCode.Conflict, read("$receiptPath/pages", mapOf(
                "receipt_id" to "other-original", "section" to "transactions", "offset" to "0",
            )).status)
            val (after, afterSections) = collect(receiptPath, "receipt_id", CASH_IMPORT_RECEIPT_PAGE_MAX_BYTES)
            assertEquals(receiptHead, after)
            assertEquals(sections, afterSections)
        }
    }

    @Test fun pageEncodingRespectsQualityRefusalAndRepeatedFields() = authenticated { db, ws, token, _ ->
        testApplication {
            application { financeModule(db, runCashImportWorker = false) }
            suspend fun page(vararg values: String): HttpResponse = client.get("/api/v1/ledger/snapshot/pages") {
                bearerAuth(token)
                parameter("workspace_id", ws)
                values.forEach { header("Accept-Encoding", it) }
            }
            val preferredIdentity = page("gzip;q=0.2", "identity")
            assertEquals(HttpStatusCode.OK, preferredIdentity.status)
            assertNull(preferredIdentity.headers["Content-Encoding"])
            val forcedGzip = page("gzip", "identity;q=0")
            assertEquals(HttpStatusCode.OK, forcedGzip.status)
            assertEquals("gzip", forcedGzip.headers["Content-Encoding"])
            val wire = forcedGzip.bodyAsBytes()
            assertEquals(wire.size, forcedGzip.headers["Content-Length"]?.toInt())
            val plain = GZIPInputStream(wire.inputStream()).use { it.readBytes() }
            assertContentEquals(preferredIdentity.bodyAsBytes(), plain)
            assertEquals(HttpStatusCode.NotAcceptable, page("gzip;q=0, identity;q=0").status)
        }
    }

    @Test fun snapshotPageFragmentsOneLargeFactAtATime() = authenticated { db, ws, token, _ ->
        val largeText = "账😀".repeat(750_000)
        db.write { c ->
            val sql = c.jooq(db.sqlite)
            val account = sql.select(ACCOUNTS.ID).from(ACCOUNTS).where(ACCOUNTS.WORKSPACE_ID.eq(ws)).fetchOne(ACCOUNTS.ID)!!
            repeat(2) {
                val fact = deletionFact(c, db, ws, account, null)
                sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.SOURCE_PAYLOAD, buildJsonObject { put("synthetic", largeText) })
                    .where(CASH_TRANSACTIONS.ID.eq(fact)).execute()
            }
        }
        val version = LedgerReadService(db).version(token).ledgerSeq
        testApplication {
            application { financeModule(db, runCashImportWorker = false) }
            suspend fun page(offset: Int, fragment: Int, encodedPath: Boolean = false): ReadPageChunk {
                val path = if (encodedPath) "/api/v1/ledger/snapshot/%70ages" else "/api/v1/ledger/snapshot/pages"
                val response = client.get(path) {
                    bearerAuth(token)
                    parameter("workspace_id", ws)
                    parameter("ledger_seq", version)
                    parameter("section", "cash_transactions")
                    parameter("offset", offset)
                    parameter("fragment_offset", fragment)
                    header("Accept-Encoding", "gzip;q=0")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                assertNull(response.headers["Content-Encoding"])
                val body = response.bodyAsText()
                assertTrue(body.encodeToByteArray().size <= LEDGER_SNAPSHOT_PAGE_MAX_BYTES)
                return Json.decodeFromJsonElement(Json.parseToJsonElement(body).jsonObject.getValue("chunk"))
            }
            val first = page(0, 0)
            assertEquals(2, first.total)
            assertTrue(first.items.isEmpty())
            val initialFragment = assertNotNull(first.fragment)
            val next = assertNotNull(first.next)
            assertEquals(0, next.offset)
            assertTrue(next.fragmentOffset > 0)
            val last = page(0, next.fragmentOffset)
            val lastFragment = assertNotNull(last.fragment)
            val raw = Base64.getDecoder().decode(initialFragment.base64) + Base64.getDecoder().decode(lastFragment.base64)
            assertEquals(initialFragment.totalBytes, raw.size)
            val fact = Json.parseToJsonElement(raw.decodeToString()).jsonObject
            assertTrue(largeText == fact.getValue("source_payload").jsonObject.getValue("synthetic").jsonPrimitive.content)
            assertEquals(1, assertNotNull(last.next).offset)
            assertEquals(0, last.next?.fragmentOffset)
            val second = page(1, 0, encodedPath = true)
            assertEquals(1, second.position.offset)
            assertNotNull(second.fragment)
        }
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
    @Test fun receiptPageRejectsAnExplicitNullPendingListButAllowsAnAbsentPendingList() = authenticated { db, ws, token, user ->
        val saved = save(db, ws, user)
        val page = ReadPageRequest(ws, null, ReadPagePosition(CashImportReceiptSection.HEADER.wireName, 0))
        val service = ReceiptReadService(db)
        val encoding = ReadPageEncoding(0.0, 1.0)
        service.importReceiptPage(token, "original", page, encoding)
        db.write { connection ->
            val summary = buildJsonObject {
                put("status", "completed")
                put("ledger_seq", "9007199254740993")
                put("write_set_result", result())
                put("pending_transaction_ids", JsonNull)
            }
            connection.jooq(db.sqlite).update(CASH_MUTATION_RECEIPTS).set(CASH_MUTATION_RECEIPTS.RESULT_SUMMARY, summary)
                .where(CASH_MUTATION_RECEIPTS.ID.eq(saved.getValue("receipt_id").jsonPrimitive.content)).execute()
        }
        assertFailsWith<ReceiptUnavailable> { service.importReceiptPage(token, "original", page, encoding) }
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
