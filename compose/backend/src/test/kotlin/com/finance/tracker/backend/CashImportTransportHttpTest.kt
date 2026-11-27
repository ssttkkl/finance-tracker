package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.ByteArrayContent
import io.ktor.server.testing.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CashImportTransportHttpTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }

    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val clock = { Instant.parse("2026-11-14T00:00:00Z") }
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())

    private fun sequence(db: Database, workspace: String) = db.read { c ->
        c.jooq(db.sqlite).select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES).where(WORKSPACES.ID.eq(workspace)).fetchOne(WORKSPACES.LEDGER_SEQ)!!
    }

    private fun authenticated(action: (Database, String, Long, String) -> Unit) =
        withCashWriteSetTestWorkspace(backend) { db, workspace, account ->
            val user = UUID.randomUUID().toString()
            val token = "transport-$user"
            db.write { c ->
                val sql = c.jooq(db.sqlite)
                sql.insertInto(USERS).set(USERS.ID, user).set(USERS.EMAIL, "$user@example.invalid")
                    .set(USERS.PASSWORD_HASH, "unused-test-hash").set(USERS.CREATED_AT, clock()).execute()
                sql.insertInto(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, workspace)
                    .set(WORKSPACE_MEMBERSHIPS.USER_ID, user).set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").set(WORKSPACE_MEMBERSHIPS.CREATED_AT, clock()).execute()
                sql.insertInto(USER_SESSIONS).set(USER_SESSIONS.ID, UUID.randomUUID().toString()).set(USER_SESSIONS.USER_ID, user)
                    .set(USER_SESSIONS.TOKEN_DIGEST, tokenDigest(token)).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, workspace)
                    .set(USER_SESSIONS.CREATED_AT, clock()).set(USER_SESSIONS.EXPIRES_AT, Instant.parse("2099-01-01T00:00:00Z")).execute()
            }
            try { action(db, workspace, account, token) }
            finally { db.write { c ->
                val sql = c.jooq(db.sqlite)
                sql.deleteFrom(CASH_MUTATION_RECEIPTS).where(CASH_MUTATION_RECEIPTS.WORKSPACE_ID.eq(workspace)).execute()
                sql.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(user)).execute()
                sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
                sql.deleteFrom(USERS).where(USERS.ID.eq(user)).execute()
            } }
        }

    private fun changes(account: Long): CashWriteSetDto {
        val target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("transport-row"), null)
        val row = CashTransactionWriteDto(target, CashTransactionWriteValuesDto(account.toString(), "posted", "import", null, "atomic",
            "ccb_debit", "transport-source", empty, empty, null, empty, "2026-11-14T00:00:00Z", "-12.30", "CNY", "Party", "", array,
            "S", null, "consumption", "not_applicable"),
            listOf(CashComponentWriteDto(NewCashWriteReferenceDto("transport-part"), account.toString(), "-12.30", "CNY", "0", "", "", empty)))
        val values = buildJsonObject {
            put("category_id", JsonNull); put("note", "S"); put("counterparty", "Party")
            put("counterparty_account", ""); put("counterparty_account_attrs", array)
        }
        val entry = CashEntryWriteDto(target, null, "S", "Party", "", array, emptyList(),
            listOf(CashEntryMemberWriteDto(target, values, emptyList())))
        return CashWriteSetDto(transactions = listOf(row), entries = listOf(entry))
    }

    private fun manyChanges(account: Long, count: Int): CashWriteSetDto {
        val values = buildJsonObject {
            put("category_id", JsonNull); put("note", "S"); put("counterparty", "Party")
            put("counterparty_account", ""); put("counterparty_account_attrs", array)
        }
        return CashWriteSetDto(
            transactions = (0 until count).map { index ->
                val target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("transport-row-$index"), null)
                CashTransactionWriteDto(target, CashTransactionWriteValuesDto(account.toString(), "posted", "import", null, "atomic",
                    "ccb_debit", "transport-source-$index", empty, empty, null, empty, "2026-11-14T00:00:00Z", "-12.30", "CNY",
                    "Party", "", array, "S", null, "consumption", "not_applicable"),
                    listOf(CashComponentWriteDto(NewCashWriteReferenceDto("transport-part-$index"), account.toString(), "-12.30", "CNY", "0", "", "", empty)))
            },
            entries = (0 until count).map { index ->
                val target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("transport-row-$index"), null)
                CashEntryWriteDto(target, null, "S", "Party", "", array, emptyList(),
                    listOf(CashEntryMemberWriteDto(target, values, emptyList())))
            })
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
    }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    @Test fun capabilityProbeIsAuthenticatedAndAdvertisesIdentityAndGzip() = authenticated { db, ws, _, token ->
        testApplication {
            application { financeModule(db, clock = clock) }
            val unauthenticated = client.options("/api/v1/cash-import/write-set?workspace_id=$ws")
            assertEquals(401, unauthenticated.status.value)
            val response = client.options("/api/v1/cash-import/write-set?workspace_id=$ws") { bearerAuth(token) }
            assertEquals(200, response.status.value)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), body["command_versions"])
            assertEquals(JsonArray(listOf(JsonPrimitive("identity"), JsonPrimitive("gzip"))), body["request_encodings"])
            assertTrue(JsonPrimitive("gzip") in body.getValue("response_encodings").jsonArray)
        }
    }

    @Test fun confirmationAcceptsAndRemainsQueryableWhileTheWriteTransactionIsOpen() = authenticated { db, ws, account, token ->
        org.junit.Assume.assumeTrue("Asynchronous confirmation is validated on PostgreSQL", backend == "postgres")
        val base = sequence(db, ws)
        val key = "transport-long-confirmation"
        val input = json.encodeToString(CashWriteSetRequestDto(base.toString(), CASH_RULES_VERSION, changes(account)))
        val executing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val laterStages = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val service = CashImportWriteSetSubmissionService(db, now = clock, afterStep = { step ->
            if (step == "sources") {
                executing.countDown()
                check(finish.await(12, TimeUnit.SECONDS)) { "test held the write transaction too long" }
            } else laterStages.add(step)
        })
        testApplication {
            application { financeModule(db, clock = clock, writeSetSubmissions = service) }
            val path = "/api/v1/cash-import/write-set"
            suspend fun status() = client.get("$path/status?workspace_id=$ws") {
                bearerAuth(token)
                header("Idempotency-Key", key)
            }
            fun state(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("status").jsonPrimitive.content
            try {
                val submitted = withTimeout(2_000) {
                    client.post("$path?workspace_id=$ws") {
                        bearerAuth(token)
                        header("Idempotency-Key", key)
                        contentType(ContentType.Application.Json)
                        setBody(input)
                    }
                }
                assertEquals(202, submitted.status.value, submitted.bodyAsText())
                assertEquals("accepted", state(submitted.bodyAsText()))
                assertTrue(executing.await(5, TimeUnit.SECONDS), "worker did not enter the write transaction")
                val running = withTimeout(2_000) { status() }
                assertEquals(200, running.status.value, running.bodyAsText())
                assertEquals("running", state(running.bodyAsText()))
                assertEquals(base, sequence(db, ws))
                val cancelling = withTimeout(2_000) {
                    client.post("$path/cancel?workspace_id=$ws") {
                        bearerAuth(token)
                        header("Idempotency-Key", key)
                    }
                }
                assertEquals(200, cancelling.status.value, cancelling.bodyAsText())
                assertEquals("cancel_requested", state(cancelling.bodyAsText()))
            } finally {
                finish.countDown()
            }
            val terminal = withTimeout(5_000) {
                var current = state(status().bodyAsText())
                while (current in setOf("accepted", "running", "cancel_requested")) {
                    delay(30)
                    current = state(status().bodyAsText())
                }
                current
            }
            assertEquals("cancelled", terminal)
            assertTrue(laterStages.isEmpty(), "cancelled operation continued into ${laterStages.toList()}")
            assertEquals(base, sequence(db, ws))
            val receipt = client.get("/api/v1/cash-mutations/receipts/by-key?workspace_id=$ws&key=$key") { bearerAuth(token) }
            assertEquals(404, receipt.status.value, receipt.bodyAsText())
        }
    }

    @Test fun acceptedWriteSurvivesWorkerRestartAndAnUnstartedWriteCanBeCancelled() = authenticated { db, ws, account, token ->
        org.junit.Assume.assumeTrue("Asynchronous confirmation is validated on PostgreSQL", backend == "postgres")
        val base = sequence(db, ws)
        val first = CashImportConfirmationService(db, CashImportWriteSetSubmissionService(db, now = clock), clock)
        val payload = json.encodeToJsonElement(CashWriteSetRequestDto(base.toString(), CASH_RULES_VERSION, changes(account))).jsonObject
        val cancelledKey = "async-cancel-queued"
        val completeKey = "async-restarted"
        assertEquals(CashImportConfirmationStatus.ACCEPTED, first.accept(token, ws, cancelledKey, payload).status)
        assertEquals(CashImportConfirmationStatus.CANCELLED, first.cancel(token, ws, cancelledKey).status)
        assertEquals(CashImportConfirmationStatus.ACCEPTED, first.accept(token, ws, completeKey, payload).status)
        assertEquals(CashImportConfirmationStatus.ACCEPTED, first.accept(token, ws, completeKey, payload).status)
        val different = JsonObject(payload + ("base_ledger_seq" to JsonPrimitive((base + 1).toString())))
        assertFailsWith<ReceiptConflict> { first.accept(token, ws, completeKey, different) }
        val restarted = CashImportConfirmationService(db, CashImportWriteSetSubmissionService(db, now = clock), clock)
        restarted.workOnce()
        assertEquals(CashImportConfirmationStatus.CANCELLED, restarted.status(token, ws, cancelledKey).status)
        val committed = restarted.status(token, ws, completeKey)
        assertEquals(CashImportConfirmationStatus.COMMITTED, committed.status)
        assertEquals(committed, restarted.cancel(token, ws, completeKey))
        assertEquals(committed, restarted.accept(token, ws, completeKey, payload))
        assertEquals(base + 1, sequence(db, ws))
        db.read { c ->
            val jobs = CASH_IMPORT_CONFIRMATIONS
            assertEquals(0, c.jooq(db.sqlite).selectCount().from(jobs).where(jobs.WORKSPACE_ID.eq(ws)).and(jobs.PAYLOAD.isNotNull).fetchOne(0))
            val permanent = c.jooq(db.sqlite).select(CASH_MUTATION_RECEIPTS.ID).from(CASH_MUTATION_RECEIPTS)
                .where(CASH_MUTATION_RECEIPTS.WORKSPACE_ID.eq(ws)).fetch(CASH_MUTATION_RECEIPTS.ID)
            assertEquals(listOf(committed.receiptId), permanent)
        }
    }

    @Test fun expiredClaimCannotReplaceAnExecutingPostgresTransaction() = authenticated { db, ws, account, token ->
        org.junit.Assume.assumeTrue("Asynchronous confirmation is validated on PostgreSQL", backend == "postgres")
        val base = sequence(db, ws)
        val time = AtomicLong(1_000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = CashImportConfirmationService(db,
            CashImportWriteSetSubmissionService(db, now = clock, afterStep = { stage ->
                if (stage == "sources") {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }), clock, time::get)
        val second = CashImportConfirmationService(db, CashImportWriteSetSubmissionService(db, now = clock), clock, time::get)
        val body = json.encodeToJsonElement(CashWriteSetRequestDto(base.toString(), CASH_RULES_VERSION, changes(account))).jsonObject
        val key = "async-expired-active"
        first.accept(token, ws, key, body)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val active = executor.submit { first.workOnce() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            time.addAndGet(31_000)
            second.workOnce()
            assertEquals(CashImportConfirmationStatus.RUNNING, second.status(token, ws, key).status)
            release.countDown()
            active.get(5, TimeUnit.SECONDS)
            second.workOnce()
            val committed = second.status(token, ws, key)
            assertEquals(CashImportConfirmationStatus.COMMITTED, committed.status)
            assertEquals(base + 1, sequence(db, ws))
            db.read { connection ->
                val sql = connection.jooq(false)
                val jobs = CASH_IMPORT_CONFIRMATIONS
                assertEquals(1L, sql.select(jobs.GENERATION).from(jobs).where(jobs.WORKSPACE_ID.eq(ws)).fetchOne(jobs.GENERATION))
                val receipts = CASH_MUTATION_RECEIPTS
                assertEquals(listOf(committed.receiptId), sql.select(receipts.ID).from(receipts)
                    .where(receipts.WORKSPACE_ID.eq(ws)).fetch(receipts.ID))
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun expiredGrantDoesNotUseLaterAuthorization() = authenticated { db, ws, account, token ->
        org.junit.Assume.assumeTrue("Asynchronous confirmation is validated on PostgreSQL", backend == "postgres")
        val base = sequence(db, ws)
        val payload = json.encodeToJsonElement(CashWriteSetRequestDto(base.toString(), CASH_RULES_VERSION, changes(account))).jsonObject
        val service = CashImportConfirmationService(db, CashImportWriteSetSubmissionService(db, now = clock), clock)
        val key = "async-revoked-grant"
        service.accept(token, ws, key, payload)
        val replacement = "$token-new"
        db.write { c ->
            val sql = c.jooq(db.sqlite)
            val sessions = USER_SESSIONS
            val user = sql.select(sessions.USER_ID).from(sessions).where(sessions.TOKEN_DIGEST.eq(tokenDigest(token))).fetchOne(sessions.USER_ID)!!
            sql.deleteFrom(sessions).where(sessions.TOKEN_DIGEST.eq(tokenDigest(token))).execute()
            sql.insertInto(sessions).set(sessions.ID, UUID.randomUUID().toString()).set(sessions.USER_ID, user)
                .set(sessions.ACTIVE_WORKSPACE_ID, ws).set(sessions.TOKEN_DIGEST, tokenDigest(replacement))
                .set(sessions.EXPIRES_AT, Instant.parse("2099-01-01T00:00:00Z")).set(sessions.CREATED_AT, clock()).execute()
        }
        service.workOnce()
        val result = service.status(replacement, ws, key)
        assertEquals(CashImportConfirmationStatus.REJECTED, result.status)
        assertEquals("workspace_forbidden", result.rejection?.code)
        assertEquals(base, sequence(db, ws))
    }

    @Test fun previousImportReceiptAndPageCanBeReadWhileAnotherImportHoldsTheWorkspace() = authenticated { db, ws, account, token ->
        org.junit.Assume.assumeTrue("Asynchronous confirmation is validated on PostgreSQL", backend == "postgres")
        val priorKey = "async-previous-receipt"
        val priorBody = json.encodeToJsonElement(CashWriteSetRequestDto(sequence(db, ws).toString(), CASH_RULES_VERSION, changes(account))).jsonObject
        val prior = CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, priorBody, priorKey)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nextService = CashImportWriteSetSubmissionService(db, now = clock, afterStep = { step ->
            if (step == "sources") {
                held.countDown()
                check(release.await(12, TimeUnit.SECONDS)) { "test transaction waited too long" }
            }
        })
        val next = json.encodeToString(CashWriteSetRequestDto(sequence(db, ws).toString(), CASH_RULES_VERSION, CashWriteSetDto()))
        testApplication {
            application { financeModule(db, clock = clock, writeSetSubmissions = nextService) }
            val path = "/api/v1/cash-import/write-set"
            try {
                val submitted = client.post("$path?workspace_id=$ws") {
                    bearerAuth(token); header("Idempotency-Key", "async-next-import")
                    contentType(ContentType.Application.Json); setBody(next)
                }
                assertEquals(202, submitted.status.value, submitted.bodyAsText())
                assertTrue(held.await(5, TimeUnit.SECONDS))
                val actual = withTimeout(2_000) {
                    client.get("$path/receipt?workspace_id=$ws") { bearerAuth(token); header("Idempotency-Key", priorKey) }
                }
                assertEquals(200, actual.status.value, actual.bodyAsText())
                val id = prior.getValue("receipt_id").jsonPrimitive.content
                assertEquals(id, Json.parseToJsonElement(actual.bodyAsText()).jsonObject.getValue("receipt_id").jsonPrimitive.content)
                val page = withTimeout(2_000) {
                    client.get("$path/receipt/$id/rows?workspace_id=$ws&after=0&limit=20") { bearerAuth(token) }
                }
                assertEquals(200, page.status.value, page.bodyAsText())
                assertEquals(1, Json.parseToJsonElement(page.bodyAsText()).jsonObject.getValue("items").jsonArray.size)
            } finally {
                release.countDown()
            }
            withTimeout(5_000) {
                while (true) {
                    val confirmation = client.get("$path/status?workspace_id=$ws") {
                        bearerAuth(token); header("Idempotency-Key", "async-next-import")
                    }
                    if (Json.parseToJsonElement(confirmation.bodyAsText()).jsonObject.getValue("status") == JsonPrimitive("committed"))
                        break
                    delay(30)
                }
            }
        }
    }

    @Test fun gzipRequestBodyIsDigestedAsTheDecodedCommandAndReplaysByKey() = authenticated { db, ws, account, token ->
        val base = sequence(db, ws)
        val command = CashWriteSetRequestDto(base.toString(), CASH_RULES_VERSION, changes(account))
        val input = Json(json) { encodeDefaults = false }.encodeToString(command)
        assertEquals(command, json.decodeFromString<CashWriteSetRequestDto>(input))
        val compressed = gzip(input.encodeToByteArray())
        testApplication {
            application { financeModule(db, clock = clock) }
            suspend fun submit(key: String, bytes: ByteArray) = client.post("/api/v1/cash-import/write-set?workspace_id=$ws") {
                bearerAuth(token); header("Idempotency-Key", key)
                header(HttpHeaders.ContentEncoding, "gzip")
                contentType(ContentType.Application.Json)
                setBody(ByteArrayContent(bytes, ContentType.Application.Json))
            }
            val saved = client.awaitCashImport(submit("transport-gzip", compressed), ws, token, "transport-gzip")
            val receipt = Json.parseToJsonElement(client.originalCashImportReceipt(saved, ws, token).bodyAsText()).jsonObject
            assertEquals(JsonPrimitive((base + 1).toString()), receipt["ledger_seq"])
            val replay = submit("transport-gzip", compressed)
            assertEquals(200, replay.status.value)
            val replayed = client.awaitCashImport(replay, ws, token, "transport-gzip")
            assertEquals(saved, replayed)
            assertEquals(receipt, Json.parseToJsonElement(client.originalCashImportReceipt(replayed, ws, token).bodyAsText()).jsonObject)
        }
    }

    @Test fun unsupportedCorruptAndOversizedGzipBodiesFailBeforeAnyWrite() = authenticated { db, ws, account, token ->
        val base = sequence(db, ws)
        val input = json.encodeToString(CashWriteSetRequestDto(base.toString(), CASH_RULES_VERSION, changes(account)))
        testApplication {
            application { financeModule(db, clock = clock) }
            suspend fun submit(key: String, encoding: String, bytes: ByteArray) = client.post("/api/v1/cash-import/write-set?workspace_id=$ws") {
                bearerAuth(token); header("Idempotency-Key", key)
                header(HttpHeaders.ContentEncoding, encoding)
                contentType(ContentType.Application.Json)
                setBody(ByteArrayContent(bytes, ContentType.Application.Json))
            }
            val unsupported = submit("transport-br", "br", gzip(input.encodeToByteArray()))
            assertEquals(415, unsupported.status.value, unsupported.bodyAsText())
            assertEquals("unsupported_content_encoding",
                Json.parseToJsonElement(unsupported.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            val corrupt = gzip(input.encodeToByteArray()).also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
            val invalid = submit("transport-corrupt", "gzip", corrupt)
            assertEquals(400, invalid.status.value, invalid.bodyAsText())
            val bomb = gzip(ByteArray(70 * 1024 * 1024) { ' '.code.toByte() })
            val oversized = submit("transport-bomb", "gzip", bomb)
            assertEquals(413, oversized.status.value, oversized.bodyAsText())
            val refusal = Json.parseToJsonElement(oversized.bodyAsText()).jsonObject
            assertEquals(JsonPrimitive("payload_too_large"), refusal.getValue("error").jsonObject.getValue("code"))
            assertEquals(JsonPrimitive("not_committed"), refusal["commit_state"])
            val raw = submit("transport-identity-overflow", "identity", ByteArray(CashImportProtocol.MAX_PLAN_INPUT_BYTES + 1))
            assertEquals(413, raw.status.value, raw.bodyAsText())
            assertEquals(refusal, Json.parseToJsonElement(raw.bodyAsText()).jsonObject)
            assertEquals(base, sequence(db, ws))
            assertEquals(0, db.read { c -> c.jooq(db.sqlite).selectCount().from(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(ws)).fetchOne(0) })
        }
    }

    @Test fun crossOriginPreflightAllowsTheProbeAndCompressedWrites() = authenticated { db, ws, _, _ ->
        testApplication {
            application { financeModule(db, clock = clock) }
            val preflight = client.options("/api/v1/cash-import/write-set?workspace_id=$ws") {
                header(HttpHeaders.Origin, "http://127.0.0.1:5173")
                header(HttpHeaders.AccessControlRequestMethod, "OPTIONS")
                header(HttpHeaders.AccessControlRequestHeaders, "authorization,content-encoding,content-type,idempotency-key")
            }
            assertEquals(200, preflight.status.value, preflight.bodyAsText())
            assertTrue(preflight.headers[HttpHeaders.AccessControlAllowMethods]?.contains("OPTIONS") == true)
            assertTrue(preflight.headers[HttpHeaders.AccessControlAllowHeaders]?.contains("Content-Encoding", ignoreCase = true) == true)
            assertEquals("http://127.0.0.1:5173", preflight.headers[HttpHeaders.AccessControlAllowOrigin])
        }
    }

    @Test fun versionTwoBodyBindsToTheLogicalRequestDigest() = authenticated { db, ws, account, token ->
        val request = CashWriteSetRequestDto(sequence(db, ws).toString(), CASH_RULES_VERSION, changes(account))
        val full = json.encodeToJsonElement(CashWriteSetRequestDto.serializer(), request).jsonObject
        fun compact(digest: String) = buildJsonObject {
            put("command_version", 2); put("request_digest", digest)
            full.forEach { (key, value) -> put(key, value) }
        }.toString()
        testApplication {
            application { financeModule(db, clock = clock) }
            suspend fun submit(body: String, key: String) = client.post("/api/v1/cash-import/write-set?workspace_id=$ws") {
                bearerAuth(token); header("Idempotency-Key", key)
                contentType(ContentType.Application.Json); setBody(body)
            }
            val rejected = client.awaitCashImport(submit(compact("0".repeat(64)), "transport-digest-bad"), ws, token, "transport-digest-bad")
            assertEquals(CashImportConfirmationStatus.REJECTED, rejected.status)
            assertEquals(CashImportConfirmationRejection("write_set_replay_mismatch", 422), rejected.rejection)
            assertEquals(0, db.read { c -> c.jooq(db.sqlite).selectCount().from(CASH_TRANSACTIONS)
                .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(ws)).fetchOne(0) })
            val accepted = submit(compact(com.finance.tracker.business.domain.cashWriteSetRequestDigest(request)), "transport-digest-good")
            assertEquals(CashImportConfirmationStatus.COMMITTED,
                client.awaitCashImport(accepted, ws, token, "transport-digest-good").status)
            assertEquals(1, db.read { c -> c.jooq(db.sqlite).selectCount().from(CASH_TRANSACTIONS)
                .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(ws)).fetchOne(0) })
        }
    }

    @Test fun aSmallCoverageTableReferencedManyTimesIsRejectedBeforeExpansion() = authenticated { db, ws, _, token ->
        val coverage = buildJsonObject {
            put("start", "2026-11-01T00:00:00Z"); put("end", "2026-12-01T00:00:00Z"); put("complete", true)
            put("evidence", ""); put("filters", JsonObject(emptyMap())); put("strategy_version", "fixture-v1")
            put("document_key", "f".repeat(64)); put("page_total", 1)
            put("page_fingerprints", buildJsonObject { put("1", JsonPrimitive("f".repeat(700_000))) })
        }
        val bindings = (0 until 120).map { index -> buildJsonObject {
            put("target", buildJsonObject { put("reference", buildJsonObject {
                put("type", "new"); put("ref", "binding$index")
            }); put("expected_revision", JsonNull) })
            put("transaction", buildJsonObject { put("type", "new"); put("ref", "row$index") })
            put("source_type", "ccb_debit"); put("source_scope_kind", "file_local"); put("source_scope_key", "scope")
            put("identity_kind", "file_occurrence"); put("identity_key", "occ$index"); put("identity_strength", "file_local")
            put("stable_evidence_key", JsonNull); put("strategy_version", "fixture-v1"); put("coverage_ref", 0)
        } }
        val body = buildJsonObject {
            put("command_version", 2); put("request_digest", "0".repeat(64))
            put("base_ledger_seq", sequence(db, ws).toString()); put("rules_version", CASH_RULES_VERSION)
            put("coverage_table", JsonArray(listOf(coverage)))
            put("changes", buildJsonObject { put("source_bindings", JsonArray(bindings)) })
        }.toString()
        testApplication {
            application { financeModule(db, clock = clock) }
            val response = client.post("/api/v1/cash-import/write-set?workspace_id=$ws") {
                bearerAuth(token); header("Idempotency-Key", "transport-amplified")
                contentType(ContentType.Application.Json); setBody(body)
            }
            val rejected = client.awaitCashImport(response, ws, token, "transport-amplified")
            assertEquals(CashImportConfirmationStatus.REJECTED, rejected.status)
            assertEquals(CashImportConfirmationRejection("payload_too_large", 413), rejected.rejection)
            assertEquals(0, db.read { c -> c.jooq(db.sqlite).selectCount().from(CASH_TRANSACTIONS)
                .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(ws)).fetchOne(0) })
        }
    }

    @Test fun largeWriteSetReceiptsAreCompressedWhenAdvertised() = authenticated { db, ws, account, token ->
        val input = json.encodeToString(CashWriteSetRequestDto(sequence(db, ws).toString(), CASH_RULES_VERSION, manyChanges(account, 80)))
        testApplication {
            application { financeModule(db, clock = clock) }
            val saved = client.post("/api/v1/cash-import/write-set?workspace_id=$ws") {
                bearerAuth(token); header("Idempotency-Key", "transport-receipt")
                contentType(ContentType.Application.Json); setBody(input)
            }
            val operation = client.awaitCashImport(saved, ws, token, "transport-receipt")
            val receipt = client.originalCashImportReceipt(operation, ws, token, "gzip")
            assertEquals(200, receipt.status.value)
            assertEquals("gzip", receipt.headers[HttpHeaders.ContentEncoding])
            val decoded = Json.parseToJsonElement(gunzip(receipt.bodyAsBytes()).decodeToString()).jsonObject
            assertTrue(decoded.getValue("receipt_id").jsonPrimitive.content.isNotEmpty())
            assertEquals(80, decoded.getValue("write_set_result").jsonObject.getValue("transactions").jsonArray.size)
        }
    }

    @Test fun largeJsonResponsesAreCompressedWhenAdvertised() = authenticated { db, ws, account, token ->
        val input = json.encodeToString(CashWriteSetRequestDto(sequence(db, ws).toString(), CASH_RULES_VERSION, changes(account)))
        testApplication {
            application { financeModule(db, clock = clock) }
            val saved = client.post("/api/v1/cash-import/write-set?workspace_id=$ws") {
                bearerAuth(token); header("Idempotency-Key", "transport-response")
                contentType(ContentType.Application.Json); setBody(input)
            }
            assertEquals(CashImportConfirmationStatus.COMMITTED,
                client.awaitCashImport(saved, ws, token, "transport-response").status)
            val identitySnapshot = client.get("/api/v1/ledger/snapshot?workspace_id=$ws") {
                bearerAuth(token); header(HttpHeaders.AcceptEncoding, "identity")
            }
            assertEquals(200, identitySnapshot.status.value)
            assertEquals("identity", identitySnapshot.headers[HttpHeaders.ContentEncoding] ?: "identity")

            val snapshot = client.get("/api/v1/ledger/snapshot?workspace_id=$ws") {
                bearerAuth(token); header(HttpHeaders.AcceptEncoding, "gzip")
            }
            assertEquals(200, snapshot.status.value)
            assertEquals("gzip", snapshot.headers[HttpHeaders.ContentEncoding], "snapshot must negotiate gzip")
            assertTrue(snapshot.headers[HttpHeaders.Vary]?.contains(HttpHeaders.AcceptEncoding, ignoreCase = true) == true)
            val decoded = Json.parseToJsonElement(gunzip(snapshot.bodyAsBytes()).decodeToString()).jsonObject
            assertEquals(JsonPrimitive(ws), decoded["workspace_id"])
            assertTrue(decoded.getValue("facts").jsonObject.getValue("cash_transactions").jsonArray.isNotEmpty())
        }
    }

}
