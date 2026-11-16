package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.sql.DriverManager
import java.math.BigInteger
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportSliceFlowTest {
    private val directory = "/cash-import-slice/"
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(directory + name)) {
        "Missing self-contained import fixture: $name"
    }.use { it.readBytes() }
    private fun jsonResource(name: String) = Json.parseToJsonElement(resource(name).decodeToString()).jsonObject

    @Test fun realFileBytesCompleteTheImportAndReplayOnSqlite() {
        onSqlite { exercise(it, jsonResource("manifest.json"), jsonResource("expected.json")) }
    }
    @Test fun theSameRealFileContractRunsOnExplicitPostgres() {
        onPostgres { exercise(it, jsonResource("manifest.json"), jsonResource("expected.json")) }
    }
    @Test fun concurrentAppendContractOnSqlite() = onSqlite(::exerciseAppend)
    @Test fun concurrentAppendContractOnExplicitPostgres() = onPostgres(::exerciseAppend)
    private fun onSqlite(action: (Database) -> Unit) {
        val temporary = Files.createTempDirectory("ft-cash-slice-")
        try {
            action(Database("jdbc:sqlite:${temporary.resolve("import_test.db")}"))
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun onPostgres(action: (Database) -> Unit) {
        val configured = System.getenv("FT_TEST_POSTGRES_URL")
        org.junit.Assume.assumeTrue("FT_TEST_POSTGRES_URL is not configured", configured != null)
        val uri = URI(configured!!.replaceFirst("postgresql+psycopg://", "postgresql://").replaceFirst("postgres://", "postgresql://"))
        require(uri.path.removePrefix("/").endsWith("_test")) { "Import tests require an explicit _test database" }
        val credentials = uri.rawUserInfo?.split(':', limit = 2).orEmpty()
        fun credential(index: Int) = credentials.getOrNull(index)?.let { URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8) }
        val user = credential(0)
        val password = credential(1)
        val server = "jdbc:postgresql://${requireNotNull(uri.host)}:${if (uri.port < 0) 5432 else uri.port}/"
        val properties = Properties().apply {
            user?.let { setProperty("user", it) }
            password?.let { setProperty("password", it) }
        }
        val name = "ft_cash_slice_${UUID.randomUUID().toString().replace("-", "")}_test"
        fun administration(sql: String) = DriverManager.getConnection(server + uri.path.removePrefix("/"), properties).use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
        administration("CREATE DATABASE \"$name\"")
        try {
            action(Database(server + name, user, password))
        } finally {
            administration("DROP DATABASE IF EXISTS \"$name\" WITH (FORCE)")
        }
    }

    private class ObservedStore : ParsedImportStore() {
        private val entries = mutableMapOf<String, ParsedImportObject>()
        var conflicts = 0
        var writes = 0
        var conflictAction: (() -> Unit)? = null
        var unavailable = false
        @Synchronized override fun readRaw(id: String) = entries[id] ?: throw ParsedImportNotFound()
        @Synchronized override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) {
            writes++
            if (unavailable) throw ParsedImportStorageUnavailable()
            if (expected != null && conflicts > 0) {
                conflicts--
                val action = conflictAction; conflictAction = null; action?.invoke()
                throw ParsedImportRevisionConflict()
            }
            if (entries[id]?.etag != expected) throw ParsedImportRevisionConflict()
            entries[id] = ParsedImportObject(content.copyOf(), digest(content))
        }
        @Synchronized override fun deleteRaw(id: String) { entries.remove(id) }
        override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean) = ParsedImportSweep(0, 0, null)
    }

    private fun exerciseAppend(database: Database) {
        database.bootstrapSchema()
        val token = AuthenticationService(database).register("append@example.invalid", "synthetic secure password").accessToken
        val workspace = WorkspaceService(database).create(token, "并发追加回归").activeWorkspaceId!!
        val store = ObservedStore()
        val calls = java.util.concurrent.ConcurrentHashMap<String, Int>()
        var barrier: CyclicBarrier? = null
        val service = CashImportHttpService(database, store) { upload, bytes ->
            calls.merge(upload.clientFileRef, 1, Int::plus)
            barrier?.await(10, TimeUnit.SECONDS)
            CashStatementRowNormalization.parseCashStatement(bytes, upload.sourceHint, upload.password)
        }
        val user = database.read { ReadServices(database).context(it, token, false).userId }
        fun upload(ref: String, name: String = "alipay-02.csv") = CashImportUpload(ref, name, resource(name))
        fun create() = service.create(token, listOf(upload("initial")))["session_id"] as String
        fun current(id: String) = store.get(id, workspace, user)
        val id = create()
        barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf("parallel-a", "parallel-b").map { ref ->
                pool.submit<Map<String, Any?>> { service.addFiles(token, id, listOf(upload(ref)), 1) }
            }
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally { barrier = null; pool.shutdownNow() }
        val merged = current(id)
        assertEquals(setOf("initial", "parallel-a", "parallel-b"), merged.files.map { it.clientFileRef }.toSet())
        assertEquals(BigInteger.valueOf(3), merged.revision)
        assertEquals(1, calls["parallel-a"]); assertEquals(1, calls["parallel-b"])
        val before = current(id)
        service.addFiles(token, id, listOf(upload("parallel-a")), 1)
        assertEquals(before, current(id), "Idempotent append must not change revision, lifetime or files")
        assertFailsWith<CashImportInputError> { service.addFiles(token, id, listOf(upload("parallel-a", "alipay-03.csv")), 1) }
        assertFailsWith<ParsedImportRevisionConflict> { service.addFiles(token, id, listOf(upload("future")), 99) }
        assertEquals(before, current(id))
        assertFailsWith<ParsedImportRevisionConflict> { service.removeFile(token, id, merged.files.first().fileRef, 1) }
        assertFailsWith<ParsedImportRevisionConflict> { service.replaceFile(token, id, merged.files.first().fileRef, upload("replacement"), 1) }
        val owner = current(id)
        store.conflicts = 1
        store.conflictAction = {
            store.replace(id, workspace, user, owner.revision,
                owner.files + owner.files.first().copy(fileRef = "f_competitor", clientFileRef = "competitor"), emptyMap(), null)
        }
        service.addFiles(token, id, listOf(upload("cas-retry")), 1)
        assertEquals(setOf("initial", "parallel-a", "parallel-b", "competitor", "cas-retry"), current(id).files.map { it.clientFileRef }.toSet())
        assertEquals(1, calls["cas-retry"], "CAS retry must not parse the same upload again")
        assertEquals(owner.expiresAt, current(id).expiresAt)
        val full = create()
        service.addFiles(token, full, (1..19).map { upload("capacity-$it") }, 1)
        val fullBefore = current(full)
        service.addFiles(token, full, listOf(upload("capacity-1")), 1)
        assertEquals(fullBefore, current(full), "A duplicate at 20 files is still idempotent")
        assertFailsWith<CashImportInputError> { service.addFiles(token, full, listOf(upload("overflow")), 1) }
        assertEquals(fullBefore, current(full))
        val exhaustedBefore = current(id)
        store.conflicts = 100
        val writes = store.writes
        assertFailsWith<ParsedImportRevisionConflict> { service.addFiles(token, id, listOf(upload("exhausted")), 1) }
        assertTrue(store.writes - writes in 2..10, "CAS retry must be bounded")
        assertEquals(exhaustedBefore, current(id)); assertEquals(1, calls["exhausted"])
        store.conflicts = 0; store.unavailable = true
        val unavailableWrites = store.writes
        assertFailsWith<ParsedImportStorageUnavailable> { service.addFiles(token, id, listOf(upload("unavailable")), 1) }
        assertEquals(1, store.writes - unavailableWrites, "Unknown storage results must not be retried")
        store.unavailable = false
        service.cancel(token, id)
        assertFailsWith<ParsedImportClosed> { service.addFiles(token, id, listOf(upload("closed")), 1) }
        database.read { connection -> connection.createStatement().use { statement ->
            for (table in listOf("cash_transactions", "cash_mutation_receipts")) statement.executeQuery("SELECT count(*) FROM $table").use {
                assertTrue(it.next()); assertEquals(0, it.getInt(1), "Append must not write $table")
            }
        } }
    }

    private fun exercise(database: Database, manifest: JsonObject, expected: JsonObject) {
        database.bootstrapSchema()
        val store = InMemoryParsedImportStore()
        testApplication {
            application { financeModule(database, cashImports = CashImportHttpService(database, store)) }
            val auth = response(client.post("/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"slice@example.invalid","password":"identity-anonymized-fixture-test-password"}""")
            })
            val token = auth.text("access_token")
            suspend fun request(method: HttpMethod, path: String, value: JsonObject? = null, key: String? = null, expectedStatus: Int = 200): JsonObject =
                response(client.request(path) {
                    this.method = method
                    bearerAuth(token)
                    key?.let { header("Idempotency-Key", it) }
                    if (value != null) {
                        contentType(ContentType.Application.Json)
                        setBody(value.toString())
                    }
                }, expectedStatus)
            request(HttpMethod.Post, "/api/v1/auth/workspaces", buildJsonObject { put("name", "账单切片回归") })
            val accounts = mutableMapOf<String, JsonObject>()
            val files = manifest.getValue("files").jsonArray.map { it.jsonObject }
            fun rows() = database.read { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM cash_transactions").use { result -> check(result.next()); result.getInt(1) }
                }
            }
            fun assertLedger() {
                val actual = database.read { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT source_type, occurred_at, amount, currency, state FROM cash_transactions").use { result ->
                            buildList { while (result.next()) add(listOf(result.getString(1),
                                (if (database.sqlite) contractInstant(result.getString(2), true) else result.getObject(2, OffsetDateTime::class.java).toInstant()).toString(),
                                result.getString(3).toBigDecimal().stripTrailingZeros().toPlainString(), result.getString(4), result.getString(5)).joinToString("|")) }
                        }
                    }
                }
                assertEquals(expected.getValue("ledger").jsonArray.map { it.jsonPrimitive.content }.sorted(), actual.sorted(), "Independent financial facts, including repeated same-second same-amount facts")
            }
            suspend fun upload(): JsonObject {
                val metadata = buildJsonObject {
                    put("files", buildJsonArray { files.forEachIndexed { index, _ ->
                        add(buildJsonObject { put("client_file_ref", "fixture-$index") })
                    } })
                }
                val parts = formData {
                    append("metadata", metadata.toString())
                    files.forEachIndexed { index, file ->
                        val name = file.text("file")
                        append("file:fixture-$index", resource(name), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
                            append(HttpHeaders.ContentType, "application/octet-stream")
                        })
                    }
                }
                val session = response(client.post("/api/v1/cash-import/sessions") {
                    bearerAuth(token)
                    setBody(MultiPartFormDataContent(parts))
                }, 201)
                assertEquals(files.size, session.getValue("files").jsonArray.size)
                session.getValue("files").jsonArray.forEachIndexed { index, value ->
                    val file = value.jsonObject
                    assertEquals("ready", file.text("status"), "fixture-$index status")
                    assertEquals(files[index].text("source"), file.text("source_type"), "fixture-$index recognition")
                    assertEquals(files[index].getValue("rows"), file.getValue("recognized_row_count"), "fixture-$index full read")
                }
                return session
            }
            suspend fun preview(session: JsonObject): JsonObject {
                val selections = buildJsonArray {
                    for (value in session.getValue("source_accounts").jsonArray) {
                        val group = value.jsonObject
                        val identity = listOf("source_type", "display_name", "session_label").joinToString("|") { group[it].toString() }
                        var account = accounts[identity]
                        if (account == null) {
                            val created = request(HttpMethod.Post, "/api/v1/accounts", buildJsonObject {
                                put("name", "测试资金账户 ${accounts.size + 1}")
                                put("type", "cash")
                                put("currencies", group.getValue("currencies"))
                            }, "account-${accounts.size}", 201)
                            account = request(HttpMethod.Get, "/api/v1/accounts/by-id/${created.text("account_id")}")
                            accounts[identity] = account
                        }
                        add(buildJsonObject {
                            put("source_account_ref", group.getValue("source_account_ref"))
                            put("account_id", account.getValue("account_id"))
                            put("expected_account_revision", account.getValue("account_revision"))
                        })
                    }
                }
                return request(HttpMethod.Post, "/api/v1/cash-import/sessions/${session.text("session_id")}/plans", buildJsonObject {
                    put("expected_session_revision", session.getValue("session_revision"))
                    put("previous_plan_id", JsonNull)
                    put("account_selections", selections)
                    put("row_choices", JsonArray(emptyList()))
                    put("relation_choices", JsonArray(emptyList()))
                    put("timezone_name", "Asia/Shanghai")
                }).also { assertEquals(JsonPrimitive(true), it["can_commit"]) }
            }
            suspend fun pages(path: String, array: String, cursor: String, next: String): List<JsonObject> {
                val result = mutableListOf<JsonObject>()
                val seen = mutableSetOf<String>()
                var after: String? = null
                do {
                    val page = request(HttpMethod.Get, path + "?limit=37" + (after?.let { "&$cursor=$it" } ?: ""))
                    result += page.getValue(array).jsonArray.map { it.jsonObject }
                    after = page[next]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                    if (after != null) assertTrue(seen.add(after), "Pagination must advance")
                } while (after != null)
                return result
            }
            val session = upload()
            assertEquals(0, rows(), "Uploading real files must not write transactions")
            var plan = preview(session)
            val path = "/api/v1/cash-import/plans/${plan.text("plan_id")}"
            val localSummary = plan.getValue("monthly_summaries").jsonObject
            assertEquals(listOf("2026-05"), localSummary.getValue("all").jsonArray.map { it.jsonObject.text("month") })
            assertEquals(plan, request(HttpMethod.Get, "$path?timezone_name=Asia%2FShanghai"))
            val utc = request(HttpMethod.Get, "$path?timezone_name=UTC")
            assertNotEquals(localSummary, utc["monthly_summaries"], "The real month-edge rows must move in UTC")
            assertEquals(utc, request(HttpMethod.Get, path), "The absent timezone defaults to UTC")
            assertEquals(plan, request(HttpMethod.Get, "$path?timezone_name=Asia%2FShanghai"), "The cache must include timezone")
            val principal = database.read { ReadServices(database).context(it, token, false) }
            val frozen = store.get(session.text("session_id"), principal.workspaceId!!, principal.userId)
            fun previewBody(revision: Number, previous: String, zone: JsonElement) = JsonObject(
                sourceJson(frozen.previewInput).jsonObject + mapOf("expected_session_revision" to JsonPrimitive(revision),
                    "previous_plan_id" to JsonPrimitive(previous), "timezone_name" to zone))
            for (invalid in listOf(JsonPrimitive("Not/AZone"), JsonPrimitive(""), JsonNull, JsonPrimitive(42))) {
                val rejected = request(HttpMethod.Post, "/api/v1/cash-import/sessions/${frozen.sessionId}/plans",
                    previewBody(frozen.revision, plan.text("plan_id"), invalid), expectedStatus = 422)
                assertEquals("plan_input_invalid", rejected.getValue("error").jsonObject.text("code"))
                assertEquals(frozen, store.get(frozen.sessionId, principal.workspaceId!!, principal.userId), "Invalid display timezone must not publish a new plan")
            }
            assertEquals("plan_input_invalid", request(HttpMethod.Get, "$path?timezone_name=Not%2FAZone", expectedStatus = 422)
                .getValue("error").jsonObject.text("code"))
            val legacy = frozen.plan!! - "monthly_source"
            store.replace(frozen.sessionId, principal.workspaceId!!, principal.userId, frozen.revision,
                frozen.files, frozen.previewInput, legacy)
            for (suffix in listOf("", "/items", "/processing-details"))
                assertEquals("plan_changed", request(HttpMethod.Get, path + suffix, expectedStatus = 409).getValue("error").jsonObject.text("code"))
            val obsolete = store.get(frozen.sessionId, principal.workspaceId!!, principal.userId)
            suspend fun resolveObsolete() = request(HttpMethod.Post, "/api/v1/cash-mutations/resolve", buildJsonObject {
                put("plan_id", plan.getValue("plan_id")); put("idempotency_key", "obsolete-plan")
            })
            assertEquals("not_committed", resolveObsolete().text("status"))
            val rejectedCommit = request(HttpMethod.Post, "$path/commit", buildJsonObject {
                put("expected_session_revision", JsonPrimitive(obsolete.revision))
            }, "obsolete-plan", 409)
            assertEquals("plan_changed", rejectedCommit.getValue("error").jsonObject.text("code"))
            assertEquals("not_committed", rejectedCommit.text("commit_state"))
            assertEquals(0, rows())
            assertEquals("not_committed", resolveObsolete().text("status"))
            plan = request(HttpMethod.Post, "/api/v1/cash-import/sessions/${frozen.sessionId}/plans",
                previewBody(obsolete.revision, plan.text("plan_id"), JsonPrimitive("Asia/Shanghai")))
            assertEquals(localSummary, plan["monthly_summaries"], "Re-preview rebuilds an obsolete plan from the original choices and facts")
            val previewItems = pages("/api/v1/cash-import/plans/${plan.text("plan_id")}/items", "items", "cursor", "next_cursor")
            assertEquals(plan.getValue("item_counts").jsonObject.values.sumOf { it.jsonPrimitive.int }, previewItems.size)
            assertEquals(previewItems.size, previewItems.map { it.text("preview_item_id") }.toSet().size)
            assertEquals(0, rows(), "Preview must not write transactions")
            val commitBody = buildJsonObject { put("expected_session_revision", plan.getValue("session_revision")) }
            val commitPath = "/api/v1/cash-import/plans/${plan.text("plan_id")}/commit"
            val receipt = request(HttpMethod.Post, commitPath, commitBody, "slice-confirm")
            assertEquals(JsonPrimitive(true), receipt["committed"])
            assertEquals("completed", receipt.text("status"))
            expected.getValue("receipt_summary").jsonObject.forEach { (key, value) ->
                assertEquals(value, receipt.getValue("summary").jsonObject[key], "Receipt $key")
            }
            val count = rows()
            assertEquals(expected.getValue("transaction_count").jsonPrimitive.int, count)
            assertLedger()
            suspend fun receiptRows(value: JsonObject): List<JsonObject> {
                val root = "/api/v1/cash-mutations/receipts/${value.text("receipt_id")}"
                assertEquals(value, request(HttpMethod.Get, root))
                val items = pages("$root/rows", "items", "after", "next_after")
                assertEquals(expected.getValue("raw_rows").jsonPrimitive.int, items.size)
                assertEquals(items.size, items.map { it.text("result_id") }.toSet().size)
                assertEquals(items.size, items.map { it.text("item_ref") }.toSet().size)
                val relations = pages("$root/relations", "items", "after", "next_after")
                assertEquals(value.getValue("summary").jsonObject.filterKeys { it.startsWith("relations_") }.values.sumOf { it.jsonPrimitive.int }, relations.size)
                assertEquals(relations.size, relations.map { it.text("result_id") }.toSet().size)
                assertEquals(relations.size, relations.map { it.text("item_ref") }.toSet().size)
                return items
            }
            val rowResults = receiptRows(receipt)
            assertEquals(377, rowResults.count { it.text("outcome") == "created_posted" })
            assertEquals(382, rowResults.count { it.text("outcome") in setOf("duplicate", "duplicate_in_batch") })
            assertEquals(receipt, request(HttpMethod.Post, commitPath, commitBody, "slice-confirm"), "Same-key replay must return the receipt")
            assertEquals(count, rows(), "Same-key replay must not duplicate transactions")
            assertLedger()
            val secondPlan = preview(upload())
            val secondReceipt = request(HttpMethod.Post, "/api/v1/cash-import/plans/${secondPlan.text("plan_id")}/commit", buildJsonObject {
                put("expected_session_revision", secondPlan.getValue("session_revision"))
            }, "slice-second-session")
            assertEquals(JsonPrimitive(true), secondReceipt["committed"])
            assertTrue(secondReceipt.text("status") in setOf("completed", "no_changes"))
            val secondSummary = secondReceipt.getValue("summary").jsonObject
            for (key in listOf("created_posted", "updated_posted", "saved_draft", "finalized_draft", "skipped", "relations_failed"))
                assertEquals(JsonPrimitive(0), secondSummary[key], "Reimport must not conceal failures or correct earlier facts: $key")
            assertEquals(expected.getValue("raw_rows"), secondSummary["duplicate"])
            assertTrue(receiptRows(secondReceipt).all { it.text("outcome") in setOf("duplicate", "duplicate_in_batch") })
            assertEquals(count, rows(), "A fresh session of the same files must deduplicate")
            assertLedger()
        }
    }

    private suspend fun response(value: HttpResponse, expectedStatus: Int = 200): JsonObject {
        assertEquals(expectedStatus, value.status.value, "Import contract HTTP status")
        return Json.parseToJsonElement(value.bodyAsText()).jsonObject
    }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
