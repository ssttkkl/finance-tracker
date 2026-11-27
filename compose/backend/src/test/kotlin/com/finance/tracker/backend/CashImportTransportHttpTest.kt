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
import java.util.zip.GZIPOutputStream
import kotlin.test.*
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
            val saved = submit("transport-gzip", compressed)
            assertEquals(200, saved.status.value, saved.bodyAsText())
            val receipt = Json.parseToJsonElement(saved.bodyAsText()).jsonObject
            assertEquals(JsonPrimitive((base + 1).toString()), receipt["ledger_seq"])
            val replay = submit("transport-gzip", compressed)
            assertEquals(200, replay.status.value)
            assertEquals(receipt, Json.parseToJsonElement(replay.bodyAsText()).jsonObject)
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
            val rejected = submit(compact("0".repeat(64)), "transport-digest-bad")
            assertEquals(422, rejected.status.value, rejected.bodyAsText())
            val failure = Json.parseToJsonElement(rejected.bodyAsText()).jsonObject
            assertEquals(JsonPrimitive("write_set_replay_mismatch"), failure.getValue("error").jsonObject.getValue("code"))
            assertEquals(JsonPrimitive("not_committed"), failure["commit_state"])
            assertEquals(0, db.read { c -> c.jooq(db.sqlite).selectCount().from(CASH_TRANSACTIONS)
                .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(ws)).fetchOne(0) })
            val accepted = submit(compact(com.finance.tracker.business.domain.cashWriteSetRequestDigest(request)), "transport-digest-good")
            assertEquals(200, accepted.status.value, accepted.bodyAsText())
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
            assertEquals(413, response.status.value, response.bodyAsText())
            val refusal = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(JsonPrimitive("payload_too_large"), refusal.getValue("error").jsonObject.getValue("code"))
            assertEquals(JsonPrimitive("not_committed"), refusal["commit_state"])
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
            assertEquals(200, saved.status.value, saved.bodyAsText())
            val receipt = client.get("/api/v1/cash-mutations/receipts/by-key?workspace_id=$ws&key=transport-receipt") {
                bearerAuth(token); header(HttpHeaders.AcceptEncoding, "gzip")
            }
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
            assertEquals(200, saved.status.value)
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
