package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.server.application.*
import io.ktor.server.request.path
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CashImportWriteSetHttpTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val clock = { Instant.parse("2026-11-14T00:00:00Z") }
    private val json = Json { encodeDefaults = true }
    private fun sequence(db: Database, workspace: String) = db.read { c ->
        c.jooq(db.sqlite).select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES).where(WORKSPACES.ID.eq(workspace)).fetchOne(WORKSPACES.LEDGER_SEQ)!!
    }
    private fun facts(db: Database, workspace: String) = db.read { LedgerSnapshotRepository(it, workspace, db.sqlite).facts() }
    private fun authenticated(action: (Database, String, Long, String, String) -> Unit) = withCashWriteSetTestWorkspace(backend) { db, workspace, account ->
        val user = UUID.randomUUID().toString(); val token = "write-set-$user"
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
        try { action(db, workspace, account, token, user) }
        finally { db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.deleteFrom(CASH_MUTATION_RECEIPTS).where(CASH_MUTATION_RECEIPTS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(user)).execute()
            sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
            sql.deleteFrom(USERS).where(USERS.ID.eq(user)).execute()
        } }
    }
    private fun changes(account: Long, suffix: String = ""): CashWriteSetDto {
        val target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("行:$suffix" + "x".repeat(260)), null)
        val row = CashTransactionWriteDto(target, CashTransactionWriteValuesDto(account.toString(), "posted", "import", null, "atomic",
            "ccb_debit", "source$suffix", json.parseToJsonElement("""{"large":9007199254740993,"decimal":1.2300}"""), empty, null, empty,
            "2026-11-14T00:00:00Z", "-12.30", "CNY", "Party", "", array, "S", null, "consumption", "not_applicable"),
            listOf(CashComponentWriteDto(NewCashWriteReferenceDto("part$suffix"), account.toString(), "-12.30", "CNY", "0", "", "", empty)))
        val values = buildJsonObject {
            put("category_id", JsonNull); put("note", "S"); put("counterparty", "Party"); put("counterparty_account", ""); put("counterparty_account_attrs", array)
        }
        val entry = CashEntryWriteDto(target, null, "S", "Party", "", array, emptyList(), listOf(CashEntryMemberWriteDto(target, values, emptyList())))
        val binding = CashSourceBindingWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("binding$suffix"), null), target.reference,
            "ccb_debit", "file_local", "file-scope", "file_occurrence", "occurrence$suffix", "file_local", null, "fixture-v1", empty)
        return CashWriteSetDto(transactions = listOf(row), entries = listOf(entry), sourceBindings = listOf(binding))
    }
    private fun payload(db: Database, workspace: String, changes: CashWriteSetDto = CashWriteSetDto(), rules: String = CASH_RULES_VERSION) =
        json.encodeToJsonElement(CashWriteSetRequestDto(sequence(db, workspace).toString(), rules, changes)).jsonObject

    private suspend fun ApplicationTestBuilder.submit(workspace: String?, token: String?, body: String, key: String? = "original"): HttpResponse =
        client.post("/api/v1/cash-import/write-set" + (workspace?.let { "?workspace_id=$it" } ?: "")) {
            if (token != null) bearerAuth(token)
            if (key != null) header("Idempotency-Key", key)
            contentType(ContentType.Application.Json); setBody(body)
        }
    private suspend fun response(response: HttpResponse, status: Int, code: String? = null): JsonObject {
        assertEquals(status, response.status.value, response.bodyAsText())
        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        if (code != null) assertEquals(code, result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        return result
    }
    @Test fun retiredMaterializedProjectionRoutesDoNotReplaceWriteSetOrSnapshot() = authenticated { db, ws, account, token, _ ->
        val input = payload(db, ws, changes(account)).toString()
        testApplication {
            application { financeModule(db, clock = clock) }
            val saved = response(submit(ws, token, input), 200)
            val result = Json.decodeFromJsonElement<CashWriteSetResultDto>(saved.getValue("write_set_result"))
            val originalFacts = facts(db, ws)
            val originalSequence = sequence(db, ws)
            val transaction = result.transactions.single().id
            val requests = listOf(
                HttpMethod.Get to "/api/v1/cash-projections",
                HttpMethod.Get to "/api/v1/cash-projections/cash:$transaction",
                HttpMethod.Get to "/api/v1/evidence/cash-projections/cash:$transaction",
                HttpMethod.Get to "/api/v1/cash-records/$transaction/projection",
                HttpMethod.Get to "/api/v1/operations/projections",
                HttpMethod.Post to "/api/v1/operations/projections/rebuild",
            )
            val statuses = requests.associate { (method, path) ->
                "$method $path" to client.request(path) { this.method = method; bearerAuth(token) }.status.value
            }
            val snapshot = Json.decodeFromJsonElement<LedgerSnapshotDto>(response(client.get("/api/v1/ledger/snapshot") {
                bearerAuth(token)
            }, 200))
            assertEquals(originalFacts, snapshot.facts)
            assertEquals(originalSequence.toString(), snapshot.ledgerSeq)
            assertEquals(saved, response(submit(ws, token, input), 200))
            assertEquals(saved, response(client.get("/api/v1/cash-mutations/receipts/" + saved.getValue("receipt_id").jsonPrimitive.content) {
                bearerAuth(token)
            }, 200))
            assertEquals(originalFacts, facts(db, ws))
            assertEquals(originalSequence, sequence(db, ws))
            assertEquals(requests.associate { (method, path) ->
                "$method $path" to if (path == "/api/v1/cash-projections") 405 else 404
            }, statuses)
        }
    }

    @Test fun publicSubmitPersistsActualFactsAndOriginalReceiptWithoutStagingService() = authenticated { db, ws, account, token, _ ->
        val input = payload(db, ws, changes(account)).toString(); val base = sequence(db, ws)
        testApplication {
            application { financeModule(db, clock = clock) }
            val saved = response(submit(ws, token, input), 200)
            assertEquals((base + 1).toString(), saved.getValue("ledger_seq").jsonPrimitive.content)
            val delivered = Json.decodeFromJsonElement<CashMutationReceiptDto>(saved)
            assertEquals(listOf("created_posted", "updated_posted", "saved_draft", "finalized_draft", "duplicate",
                "relations_confirmed", "relations_deferred", "relations_ignored", "relations_duplicate").associateWith { if (it == "created_posted") 1L else 0L }, delivered.summary)
            assertEquals(0L, delivered.needsAttentionCount); assertEquals(0L, delivered.pendingItemCount)
            assertEquals(CashImportCommitOutcomeDto(false, true, emptyList()), delivered.commitOutcome)
            val result = Json.decodeFromJsonElement<CashWriteSetResultDto>(saved.getValue("write_set_result"))
            val actual = facts(db, ws)
            assertEquals(actual.cashTransactions.single().id, result.transactions.single().id)
            assertEquals(actual.cashTransactionComponents.single().id, result.transactions.single().componentIds.single())
            assertEquals(actual.cashEntries.single().id, result.entries.single().id)
            assertEquals(actual.cashSourceBindings.single().id, result.sourceBindings.single().id)
            assertEquals(saved, response(submit(ws, token, input), 200))
            for (path in listOf("/api/v1/cash-mutations/receipts/" + saved.getValue("receipt_id").jsonPrimitive.content,
                "/api/v1/cash-mutations/receipts/by-key?key=original")) {
                assertEquals(saved, response(client.get(path) { bearerAuth(token) }, 200))
            }
            assertEquals(base + 1, sequence(db, ws))
            assertEquals(actual, facts(db, ws))
        }
    }
    @Test fun aNewWriteSetEntryCanBeMaintainedAndLocallyDerivedWithoutMaterializedProjections() = authenticated { db, ws, account, token, _ ->
        val category = CategoryService(db, clock).create(token, "导入后分类").id
        val input = payload(db, ws, changes(account)).toString()
        testApplication {
            application { financeModule(db, clock = clock) }
            val saved = response(submit(ws, token, input), 200)
            val result = Json.decodeFromJsonElement<CashWriteSetResultDto>(saved.getValue("write_set_result"))
            val entry = result.entries.single().id
            val originalFacts = facts(db, ws)
            val savedVersion = saved.getValue("ledger_seq").jsonPrimitive.content.toLong()
            assertTrue(db.read { c -> c.jooq(db.sqlite).fetchCount(CASH_PROJECTION_STATES, CASH_PROJECTION_STATES.WORKSPACE_ID.eq(ws)) == 0 })
            val classified = CashClassificationService(db, clock).setEntryCategory(token, listOf(entry), savedVersion, category, ws)
            assertEquals(JsonPrimitive(savedVersion + 1), classified["projection_version"])
            val snapshot = Json.decodeFromJsonElement<LedgerSnapshotDto>(response(client.get("/api/v1/ledger/snapshot") { bearerAuth(token) }, 200))
            assertEquals((savedVersion + 1).toString(), snapshot.ledgerSeq)
            assertEquals(originalFacts.cashTransactions, snapshot.facts.cashTransactions)
            assertEquals(originalFacts.cashTransactionComponents, snapshot.facts.cashTransactionComponents)
            assertEquals(originalFacts.cashSourceBindings, snapshot.facts.cashSourceBindings)
            val query = com.finance.tracker.business.domain.LocalCashLedgerQuery(snapshot) { instant, zone ->
                java.time.Instant.parse(instant.toString()).atZone(java.time.ZoneId.of(zone))
                    .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM"))
            }
            val filters = com.finance.tracker.business.domain.normalizeLocalCashLedgerQueryInput(CashFiltersDto(), "UTC") { from, to, zone ->
                assertNull(from); assertNull(to)
                com.finance.tracker.business.domain.CashLedgerDateBounds(null, null, checkNotNull(zone), null, null)
            }
            val view = query.page(filters).items.single()
            assertEquals("cash-entry:$entry", view.projectionId)
            assertEquals(category, view.category?.id)
            assertEquals(saved, response(client.get("/api/v1/cash-mutations/receipts/" + saved.getValue("receipt_id").jsonPrimitive.content) {
                bearerAuth(token)
            }, 200))
            val impact = CashDeletionService(db, clock).previewEntries(token, listOf(entry), savedVersion + 1)
            assertEquals(JsonPrimitive(1), impact["transaction_count"])
            val deleted = CashDeletionService(db, clock).deleteEntries(token, listOf(entry), savedVersion + 1, ws)
            assertEquals(JsonPrimitive(savedVersion + 2), deleted["projection_version"])
            val afterDelete = Json.decodeFromJsonElement<LedgerSnapshotDto>(response(client.get("/api/v1/ledger/snapshot") { bearerAuth(token) }, 200))
            assertEquals((savedVersion + 2).toString(), afterDelete.ledgerSeq)
            assertTrue(afterDelete.facts.cashEntries.isEmpty()); assertTrue(afterDelete.facts.cashEntryMembers.isEmpty())
            val deletedQuery = com.finance.tracker.business.domain.LocalCashLedgerQuery(afterDelete) { instant, zone ->
                java.time.Instant.parse(instant.toString()).atZone(java.time.ZoneId.of(zone))
                    .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM"))
            }
            assertTrue(deletedQuery.page(filters).items.isEmpty())
            assertEquals(saved, response(submit(ws, token, input), 200))
            assertEquals(savedVersion + 2, sequence(db, ws))
            assertTrue(db.read { c -> c.jooq(db.sqlite).fetchCount(CASH_PROJECTION_STATES, CASH_PROJECTION_STATES.WORKSPACE_ID.eq(ws)) == 0 })
        }
    }

    @Test fun digestPreservesRawNumberLexemesButSortsObjectKeys() = authenticated { db, ws, account, token, _ ->
        val input = payload(db, ws, changes(account)); val text = input.toString()
        assertTrue("1.2300" in text)
        testApplication {
            application { financeModule(db, clock = clock) }
            val saved = response(submit(ws, token, text), 200)
            assertEquals(saved, response(submit(ws, token, JsonObject(input.toList().reversed().toMap()).toString()), 200))
            val changed = response(submit(ws, token, text.replace("1.2300", "1.23")), 409, "idempotency_conflict")
            assertFalse("commit_state" in changed)
            assertEquals(saved["receipt_id"], changed["receipt_id"])
            assertEquals(1, facts(db, ws).cashTransactions.size)
        }
    }
    @Test fun staleBaselineAndRulesRejectWholeNewCommandButOriginalKeyStillReplays() = authenticated { db, ws, account, token, _ ->
        val input = payload(db, ws, changes(account)).toString(); val base = sequence(db, ws)
        testApplication {
            application { financeModule(db, clock = clock) }
            val saved = response(submit(ws, token, input), 200)
            val stale = response(submit(ws, token, input, "stale"), 409, "ledger_base_changed")
            assertEquals(JsonPrimitive("not_committed"), stale["commit_state"])
            val oldRules = response(submit(ws, token, payload(db, ws, rules = "old").toString(), "rules"), 409, "rules_version_changed")
            assertEquals(JsonPrimitive("not_committed"), oldRules["commit_state"])
            assertEquals(saved, response(submit(ws, token, input), 200))
            assertEquals(base + 1, sequence(db, ws)); assertEquals(1, facts(db, ws).cashTransactions.size)
            db.read { c -> assertNull(ReceiptRepository(c, ws, db.sqlite).lookupKey(
                c.jooq(db.sqlite).select(USER_SESSIONS.USER_ID).from(USER_SESSIONS).where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).fetchOne(USER_SESSIONS.USER_ID)!!, "stale")) }
        }
    }
    @Test fun unauthenticatedAndViewerRequestsCannotWriteOrReplay() = authenticated { db, ws, _, token, user ->
        val input = payload(db, ws).toString()
        testApplication {
            application { financeModule(db, clock = clock) }
            response(submit(ws, null, input), 401)
            response(submit(ws, token, input), 200)
            db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer")
                .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(ws)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute() }
            response(submit(ws, token, input), 403, "workspace_forbidden")
            response(submit(ws, token, input, "new"), 403, "workspace_forbidden")
            assertTrue(facts(db, ws).cashTransactions.isEmpty())
        }
    }
    @Test fun malformedOrDuplicateJsonCannotClaimAnEarlierKeyWasNotCommitted() = authenticated { db, ws, _, token, _ ->
        val input = payload(db, ws).toString()
        testApplication {
            application { financeModule(db, clock = clock) }
            val saved = response(submit(ws, token, input), 200)
            for (bad in listOf("[]", "{", input.dropLast(1) + ",\"changes\":{}}", "{\"x\":NaN}", "{\"x\":Infinity}", "{\"x\":01}")) {
                val failure = response(submit(ws, token, bad), 422, "import_payload_invalid")
                assertFalse("commit_state" in failure)
            }
            assertEquals(saved, response(submit(ws, token, input), 200))
            response(submit(ws, token, input, null), 422, "idempotency_key_required")
        }
    }
    @Test fun invalidTypedPayloadAndBusinessEffectsNeverPersistAReceipt() = authenticated { db, ws, account, token, user ->
        val before = facts(db, ws); val base = sequence(db, ws)
        testApplication {
            application { financeModule(db, clock = clock) }
            val invalid = JsonObject(payload(db, ws) + ("extra" to JsonPrimitive(true))).toString()
            val rejected = response(submit(ws, token, invalid), 422, "write_set_payload_invalid")
            assertEquals(JsonPrimitive("not_committed"), rejected["commit_state"])
            val withoutEntries = payload(db, ws, changes(account).copy(entries = emptyList())).toString()
            val effects = response(submit(ws, token, withoutEntries, "effects"), 422)
            assertEquals(JsonPrimitive("not_committed"), effects["commit_state"])
            assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws))
            db.read { c -> for (key in listOf("original", "effects")) assertNull(ReceiptRepository(c, ws, db.sqlite).lookupKey(user, key)) }
        }
    }    @Test fun lateProgramFailureRollsBackAndReturns500WithSafeDiagnostic() = authenticated { db, ws, account, token, user ->
        val input = payload(db, ws, changes(account)).toString(); val before = facts(db, ws); val base = sequence(db, ws)
        val service = CashImportWriteSetSubmissionService(db, now = clock, afterStep = { if (it == "receipt") error("private-bill-detail") })
        testApplication {
            application { financeModule(db, clock = clock, writeSetSubmissions = service) }
            val failed = response(submit(ws, token, input), 500, "internal_error")
            assertEquals(JsonPrimitive("not_committed"), failed["commit_state"])
            val error = failed.getValue("error").jsonObject
            assertEquals(JsonPrimitive(false), error["retryable"])
            assertEquals(JsonPrimitive("receipt"), error.getValue("diagnostic").jsonObject["stage"])
            assertFalse("private-bill-detail" in failed.toString())
            assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws))
            db.read { c -> assertNull(ReceiptRepository(c, ws, db.sqlite).lookupKey(user, "original")) }
        }
    }
    @Test fun responseLostAfterCommitKeepsUnknownStateAndResolvesTheOriginalPermanentResult() = authenticated { db, ws, account, token, _ ->
        val input = payload(db, ws, changes(account)).toString(); val base = sequence(db, ws)
        var loseResponse = true
        val service = CashImportWriteSetSubmissionService(db, now = clock, afterCommit = { if (loseResponse) error("private-response-detail") })
        testApplication {
            application { financeModule(db, clock = clock, writeSetSubmissions = service) }
            val failed = response(submit(ws, token, input), 500, "internal_error")
            assertEquals(JsonPrimitive("unknown"), failed["commit_state"])
            assertFalse("private-response-detail" in failed.toString())
            val saved = response(client.get("/api/v1/cash-mutations/receipts/by-key?key=original") { bearerAuth(token) }, 200)
            val actual = facts(db, ws)
            assertEquals((base + 1).toString(), saved.getValue("ledger_seq").jsonPrimitive.content)
            assertEquals(actual.cashTransactions.single().id, Json.decodeFromJsonElement<CashWriteSetResultDto>(saved.getValue("write_set_result")).transactions.single().id)
            loseResponse = false
            assertEquals(saved, response(submit(ws, token, input), 200))
            assertEquals(actual, facts(db, ws)); assertEquals(base + 1, sequence(db, ws))
        }
    }
    @Test fun recognizedTemporaryStorageFailureUses503WithoutMisclassifyingPrograms() = authenticated { db, ws, _, token, _ ->
        val service = CashImportWriteSetSubmissionService(db, now = clock, afterStep = { if (it == "receipt") throw StorageFailure("storage.busy") })
        testApplication {
            application { financeModule(db, clock = clock, writeSetSubmissions = service) }
            val failed = response(submit(ws, token, payload(db, ws).toString()), 503, "import_unavailable")
            assertEquals(JsonPrimitive("not_committed"), failed["commit_state"])
            assertEquals(JsonPrimitive(true), failed.getValue("error").jsonObject["retryable"])
        }
    }
    @Test fun transportRejectsMalformedUtf8AndEscapedLoneSurrogatesWithoutWrites() = authenticated { db, ws, _, token, _ ->
        testApplication {
            application { financeModule(db, clock = clock) }
            val raw = client.post("/api/v1/cash-import/write-set?workspace_id=$ws") {
                bearerAuth(token); header("Idempotency-Key", "invalid"); contentType(ContentType.Application.Json)
                setBody(byteArrayOf(0x7b, 0x22, 0x78, 0x22, 0x3a, 0x22, 0xc3.toByte(), 0x28, 0x22, 0x7d))
            }
            val invalid = response(raw, 422, "import_payload_invalid"); assertFalse("commit_state" in invalid)
            val surrogate = payload(db, ws).toString().dropLast(1) + ",\"bad\":\"\\ud800\"}"
            val bad = response(submit(ws, token, surrogate), 422, "write_set_payload_invalid"); assertFalse("commit_state" in bad)
            assertTrue(facts(db, ws).cashTransactions.isEmpty())
        }
    }
    @Test fun concurrentNewKeysWithOneBaselineCommitOnlyOneWholeCommand() = authenticated { db, ws, account, token, _ ->
        val input = payload(db, ws, changes(account)).toString(); val base = sequence(db, ws)
        testApplication {
            application { financeModule(db, clock = clock) }
            val replies = coroutineScope { listOf("race-a", "race-b").map { key -> async { submit(ws, token, input, key) } }.map { it.await() } }
            assertEquals(listOf(200, 409), replies.map { it.status.value }.sorted())
            val failed = response(replies.single { it.status.value == 409 }, 409, "ledger_base_changed")
            assertEquals(JsonPrimitive("not_committed"), failed["commit_state"])
            assertEquals(1, facts(db, ws).cashTransactions.size); assertEquals(base + 1, sequence(db, ws))
        }
    }
    @Test fun concurrentOriginalKeyReturnsOnePermanentResultAndOneVersion() = authenticated { db, ws, account, token, _ ->
        val input = payload(db, ws, changes(account)).toString(); val base = sequence(db, ws)
        testApplication {
            application { financeModule(db, clock = clock) }
            val replies = coroutineScope { List(2) { async { submit(ws, token, input) } }.map { it.await() } }
            assertEquals(response(replies[0], 200), response(replies[1], 200))
            assertEquals(1, facts(db, ws).cashTransactions.size); assertEquals(base + 1, sequence(db, ws))
        }
    }
    @Test fun originalWorkspaceIsRequiredAndCannotFollowALaterSessionSwitch() = authenticated { db, ws, account, token, user ->
        val other = UUID.randomUUID().toString()
        db.write { c ->
            deletionInsert(c, db, "workspaces", linkedMapOf("id" to other, "name" to "Other", "created_at" to deletionTime))
            c.jooq(db.sqlite).insertInto(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, other).set(WORKSPACE_MEMBERSHIPS.USER_ID, user)
                .set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").set(WORKSPACE_MEMBERSHIPS.CREATED_AT, clock()).execute()
        }
        try {
            val source = changes(account)
            val draft = source.copy(transactions = source.transactions.map { it.copy(values = it.values.copy(accountId = null, state = "draft", amount = null, currency = null), components = emptyList()) })
            val input = payload(db, ws, draft).toString()
            assertEquals(sequence(db, ws), sequence(db, other))
            testApplication {
                application { financeModule(db, clock = clock) }
                response(submit(null, token, input), 422, "import_payload_invalid")
                val duplicate = client.post("/api/v1/cash-import/write-set?workspace_id=$ws&workspace_id=$ws") {
                    bearerAuth(token); header("Idempotency-Key", "original"); setBody(input)
                }
                response(duplicate, 422, "import_payload_invalid")
                db.write { c -> c.jooq(db.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, other).where(USER_SESSIONS.USER_ID.eq(user)).execute() }
                response(submit(ws, token, input), 403, "workspace_forbidden")
                assertTrue(facts(db, ws).cashTransactions.isEmpty()); assertTrue(facts(db, other).cashTransactions.isEmpty())
                db.read { c -> assertNull(ReceiptRepository(c, other, db.sqlite).lookupKey(user, "original")) }
                db.write { c -> c.jooq(db.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, ws).where(USER_SESSIONS.USER_ID.eq(user)).execute() }
                response(submit(ws, token, input), 200)
                assertEquals(1, facts(db, ws).cashTransactions.size); assertTrue(facts(db, other).cashTransactions.isEmpty())
            }
        } finally { db.write { c -> c.jooq(db.sqlite).deleteFrom(WORKSPACES).where(WORKSPACES.ID.eq(other)).execute() } }
    }
    @Test fun receiptScopeRejectsBothAnAlreadySwitchedSessionAndASwitchAfterMiddleware() = authenticated { db, ws, _, token, user ->
        val other = UUID.randomUUID().toString()
        db.write { c ->
            deletionInsert(c, db, "workspaces", linkedMapOf("id" to other, "name" to "Other", "created_at" to deletionTime))
            c.jooq(db.sqlite).insertInto(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, other).set(WORKSPACE_MEMBERSHIPS.USER_ID, user)
                .set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").set(WORKSPACE_MEMBERSHIPS.CREATED_AT, clock()).execute()
        }
        fun select(workspace: String) { db.write { c -> c.jooq(db.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, workspace).where(USER_SESSIONS.USER_ID.eq(user)).execute() } }
        try {
            val service = CashImportWriteSetSubmissionService(db, now = clock)
            val first = service.submit(token, ws, payload(db, ws), "original")
            select(other); val second = service.submit(token, other, payload(db, other), "original")
            assertNotEquals(first["receipt_id"], second["receipt_id"])
            var switchBeforeRead = false
            testApplication {
                application {
                    intercept(ApplicationCallPipeline.Call) {
                        if (switchBeforeRead && call.request.path().startsWith("/api/v1/cash-mutations/receipts/")) select(other)
                    }
                    financeModule(db, clock = clock)
                }
                response(client.get("/api/v1/cash-mutations/receipts/by-key?key=original&workspace_id=$ws") { bearerAuth(token) }, 403, "workspace_forbidden")
                select(ws)
                assertEquals(first, response(client.get("/api/v1/cash-mutations/receipts/by-key?key=original&workspace_id=$ws") { bearerAuth(token) }, 200))
                val id = first.getValue("receipt_id").jsonPrimitive.content
                assertEquals(first, response(client.get("/api/v1/cash-mutations/receipts/$id?workspace_id=$ws") { bearerAuth(token) }, 200))
                switchBeforeRead = true
                for (path in listOf("by-key?key=original&workspace_id=$ws", "$id?workspace_id=$ws", "by-key?key=original")) {
                    select(ws)
                    response(client.get("/api/v1/cash-mutations/receipts/$path") { bearerAuth(token) }, 403, "workspace_forbidden")
                }
            }
        } finally { db.write { c -> c.jooq(db.sqlite).deleteFrom(WORKSPACES).where(WORKSPACES.ID.eq(other)).execute() } }
    }

}
