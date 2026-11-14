package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CashImportWriteSetSubmissionTest(private val backend: String) {
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

    private fun existingEntries(source: LedgerFactsDto): CashWriteSetDto {
        val parents = source.cashTransactions.associateBy { it.id }
        fun target(id: String) = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(id), parents.getValue(id).rowRevision)
        return CashWriteSetDto(entries = source.cashEntries.map { row -> CashEntryWriteDto(target(row.rootTransactionId), row.categoryId, row.note, row.counterparty,
            row.counterpartyAccount, row.counterpartyAccountAttrs, row.manualFields.jsonArray.map { it.jsonPrimitive.content },
            source.cashEntryMembers.filter { it.entryId == row.id }.map { CashEntryMemberWriteDto(target(it.transactionId), it.standaloneValues,
                it.standaloneManualFields.jsonArray.map { field -> field.jsonPrimitive.content }) }) },
            entryDependencies = source.cashEntries.map { CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(it.id), it.revision) })
    }
    @Test fun actualFactsEntriesBalanceAndReceiptCommitInOneVersionWithoutLimitingOpaqueRefs() = authenticated { db, ws, account, token, user ->
        val base = sequence(db, ws); val input = changes(account)
        val receipt = CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, payload(db, ws, input), "first")
        val after = facts(db, ws)
        assertEquals(base + 1, sequence(db, ws)); assertEquals(JsonPrimitive((base + 1).toString()), receipt["ledger_seq"])
        assertEquals("S", after.cashEntries.single().note); assertEquals(1, after.cashSourceBindings.size)
        val storedPayload = after.cashTransactions.single().sourcePayload.jsonObject
        assertEquals("9007199254740993", storedPayload.getValue("large").jsonPrimitive.content)
        assertEquals(0, "1.2300".toBigDecimal().compareTo(storedPayload.getValue("decimal").jsonPrimitive.content.toBigDecimal()))
        assertFalse("request_digest" in receipt)
        db.read { c ->
            val repository = ReceiptRepository(c, ws, db.sqlite)
            val results = repository.results(user, receipt.getValue("receipt_id").jsonPrimitive.content)!!.getValue("items").jsonArray
            assertEquals("row:0", results.single().jsonObject.getValue("item_ref").jsonPrimitive.content)
            assertEquals(after.cashTransactions.single().id, results.single().jsonObject.getValue("transaction_id").jsonPrimitive.content)
            val snapshot = c.jooq(db.sqlite).select(LEDGER_SNAPSHOTS.PAYLOAD).from(LEDGER_SNAPSHOTS).where(LEDGER_SNAPSHOTS.WORKSPACE_ID.eq(ws)).fetchOne(LEDGER_SNAPSHOTS.PAYLOAD)
            assertNotNull(snapshot); assertTrue(snapshot.toString().contains("12.3"))
        }
    }
    @Test fun emptyCommandDoesNotAdvanceVersionAndReplayPrecedesRulesAndBaseline() = authenticated { db, ws, _, token, _ ->
        val input = payload(db, ws, rules = "old-rules")
        val rejected = assertFailsWith<CashWriteSetSubmissionFailure> { CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, input, "empty") }
        assertEquals("rules_version_changed", rejected.code)
        val valid = payload(db, ws); val version = sequence(db, ws)
        val service = CashImportWriteSetSubmissionService(db, now = clock)
        val first = service.submit(token, ws, valid, "empty")
        assertEquals(version, sequence(db, ws)); assertEquals(JsonPrimitive("no_changes"), first["status"])
        db.write { it.markLedgerChanged(ws) }
        assertEquals(first, service.submit(token, ws, JsonObject(valid.entries.reversed().associate { it.toPair() }), "empty"))
        assertEquals(version + 1, sequence(db, ws))
    }
    @Test fun originalKeyReplaysWithoutSavingAndDifferentBodyConflicts() = authenticated { db, ws, account, token, _ ->
        val input = payload(db, ws, changes(account)); val service = CashImportWriteSetSubmissionService(db, now = clock)
        val first = service.submit(token, ws, input, "original"); val state = detailDatabaseState(db)
        val replay = CashImportWriteSetSubmissionService(db, now = clock, afterStep = { if (it == "sources") error("replay wrote") })
        assertEquals(first, replay.submit(token, ws, input, "original")); assertEquals(state, detailDatabaseState(db))
        val conflict = assertFailsWith<ReceiptConflict> { service.submit(token, ws, JsonObject(input + ("base_ledger_seq" to JsonPrimitive(sequence(db, ws).toString()))), "original") }
        assertEquals("idempotency_conflict", conflict.code); assertEquals(state, detailDatabaseState(db))
    }
    @Test fun staleNewKeyAndInvalidFinalEffectsRollBackAllFactsAndReceipt() = authenticated { db, ws, account, token, _ ->
        val input = payload(db, ws, changes(account)); db.write { it.markLedgerChanged(ws) }; val state = detailDatabaseState(db)
        assertFailsWith<LedgerBaseChanged> { CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, input, "stale") }
        assertEquals(state, detailDatabaseState(db))
        val original = changes(account); val invalid = original.copy(entries = original.entries.map { it.copy(note = "forged") })
        val failure = assertFailsWith<CashWriteSetSubmissionFailure> { CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, payload(db, ws, invalid), "forged") }
        assertEquals("entry_result_invalid", failure.code); assertEquals("not_committed", failure.commitState)
        assertEquals(state, detailDatabaseState(db))
    }
    @Test fun failureAfterPermanentReceiptInsertRollsBackEverything() = authenticated { db, ws, account, token, _ ->
        val state = detailDatabaseState(db)
        val failure = assertFailsWith<CashImportCommitFailure> {
            CashImportWriteSetSubmissionService(db, now = clock, afterStep = { if (it == "receipt") error("injected") })
                .submit(token, ws, payload(db, ws, changes(account)), "rollback")
        }
        assertEquals("not_committed", failure.commitState); assertEquals("injected", failure.failure.message)
        assertEquals(state, detailDatabaseState(db))
    }
    @Test fun committedButLostResponseResolvesByOriginalKeyWithoutNewIdsOrBalances() = authenticated { db, ws, account, token, user ->
        val input = payload(db, ws, changes(account))
        val failure = assertFailsWith<CashImportCommitFailure> {
            CashImportWriteSetSubmissionService(db, now = clock, afterCommit = { error("response lost") }).submit(token, ws, input, "lost")
        }
        assertEquals("unknown", failure.commitState); val state = detailDatabaseState(db)
        val actual = db.read { ReceiptRepositoryView.strip(ReceiptRepository(it, ws, db.sqlite).byKey(user, "lost")!!) }
        assertEquals(actual, ReceiptReadService(db, clock).receiptByKey(token, "lost"))
        assertEquals(actual, CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, input, "lost"))
        assertEquals(state, detailDatabaseState(db))
    }
    @Test fun workspaceMismatchAndViewerCannotWriteOrReplay() = authenticated { db, ws, _, token, user ->
        val service = CashImportWriteSetSubmissionService(db, now = clock); val input = payload(db, ws)
        service.submit(token, ws, input, "auth")
        assertFailsWith<AccessFailure> { service.submit(token, UUID.randomUUID().toString(), input, "auth") }
        db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer")
            .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(ws).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user))).execute() }
        val state = detailDatabaseState(db)
        assertFailsWith<AccessFailure> { service.submit(token, ws, input, "auth") }; assertEquals(state, detailDatabaseState(db))
    }
    @Test fun rechecksAuthorizationAfterLockAndBeforeReturningReplay() = authenticated { db, ws, _, token, user ->
        val input = payload(db, ws); val state = detailDatabaseState(db)
        fun revoke(c: java.sql.Connection) { c.jooq(db.sqlite).deleteFrom(WORKSPACE_MEMBERSHIPS)
            .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(ws).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user))).execute() }
        assertFailsWith<AccessFailure> { CashImportWriteSetSubmissionService(db, now = clock, beforeWorkspaceLock = ::revoke).submit(token, ws, input, "revoked") }
        assertEquals(state, detailDatabaseState(db))
        CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, input, "existing")
        val committed = detailDatabaseState(db)
        assertFailsWith<AccessFailure> { CashImportWriteSetSubmissionService(db, now = clock, beforeFinalAuthorization = ::revoke).submit(token, ws, input, "existing") }
        assertEquals(committed, detailDatabaseState(db))
    }
    @Test fun malformedDeepOrNonUtf8InputFailsBeforeRecursiveDecodeOrWrites() = authenticated { db, ws, _, token, _ ->
        var deep: JsonElement = empty; repeat(140) { deep = JsonArray(listOf(deep)) }
        val state = detailDatabaseState(db); val service = CashImportWriteSetSubmissionService(db, now = clock)
        for (input in listOf(JsonObject(mapOf("deep" to deep)), JsonObject(mapOf("invalid" to JsonPrimitive(charArrayOf(0xd800.toChar()).concatToString()))), empty)) {
            assertEquals("write_set_payload_invalid", assertFailsWith<CashWriteSetSubmissionFailure> { service.submit(token, ws, input, "invalid") }.code)
            assertEquals(state, detailDatabaseState(db))
        }
    }
    @Test fun concurrentSameKeyReturnsOneReceiptAndDifferentKeysRejectStaleBase() = authenticated { db, ws, account, token, _ ->
        val input = payload(db, ws, changes(account)); val pool = Executors.newFixedThreadPool(2); val ready = CountDownLatch(2); val start = CountDownLatch(1)
        try {
            val futures = (0..1).map { pool.submit<JsonObject> { ready.countDown(); check(start.await(20, TimeUnit.SECONDS));
                CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, input, "concurrent") } }
            assertTrue(ready.await(20, TimeUnit.SECONDS)); start.countDown()
            assertEquals(futures[0].get(40, TimeUnit.SECONDS), futures[1].get(40, TimeUnit.SECONDS))
            assertEquals(1, facts(db, ws).cashTransactions.size)
            val state = detailDatabaseState(db)
            assertFailsWith<LedgerBaseChanged> { CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, input, "other-key") }
            assertEquals(state, detailDatabaseState(db))
        } finally { start.countDown(); pool.shutdownNow() }
    }
    @Test fun oldRulesAndStaleBaseReplayAnAlreadyCommittedOriginalReceipt() = authenticated { db, ws, _, token, user ->
        val input = Json.parseToJsonElement("""{"rules_version":"old-rules","changes":{},"base_ledger_seq":"0"}""").jsonObject
        val digest = tokenDigest("""{"base_ledger_seq":"0","changes":{},"rules_version":"old-rules"}""")
        val receipt = db.write { c -> ReceiptRepository(c, ws, db.sqlite, clock).create(user, "prior-release", digest, "cash_write_set",
            mapOf("status" to "no_changes", "ledger_seq" to "0"), emptyList<Any>()) }
        db.write { it.markLedgerChanged(ws) }; val state = detailDatabaseState(db)
        assertEquals(ReceiptRepositoryView.strip(receipt), CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, input, "prior-release"))
        assertEquals(state, detailDatabaseState(db))
    }
    @Test fun requestDigestPreservesExactNumericLexemesEvenWhenStorageNormalizesThem() = authenticated { db, ws, account, token, _ ->
        val input = payload(db, ws, changes(account)); val service = CashImportWriteSetSubmissionService(db, now = clock)
        service.submit(token, ws, input, "lexemes"); val state = detailDatabaseState(db)
        val changed = Json.parseToJsonElement(input.toString().replace("1.2300", "1.23")).jsonObject
        assertNotEquals(input, changed)
        assertEquals("idempotency_conflict", assertFailsWith<ReceiptConflict> { service.submit(token, ws, changed, "lexemes") }.code)
        assertEquals(state, detailDatabaseState(db))
    }
    @Test fun draftOnlyCommandStillReconcilesTheDeclaredDraftEntry() = authenticated { db, ws, account, token, _ ->
        val original = changes(account); val input = original.copy(transactions = original.transactions.map { it.copy(values = it.values.copy(state = "draft")) })
        CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, payload(db, ws, input), "draft")
        val after = facts(db, ws)
        assertEquals("draft", after.cashTransactions.single().state); assertEquals(1, after.cashEntries.size)
        assertEquals(after.cashTransactions.single().id, after.cashEntryMembers.single().transactionId)
    }
    @Test fun sourcesPreEditBRelationReconciliationAndDUseActualSingleTransaction() = authenticated { db, ws, account, token, user ->
        val a = changes(account); val b = changes(account, "other")
        val first = a.transactions.single().copy(entryEdit = CashEntryBeforeRelationEditDto(null, buildJsonObject { put("note", "C") }))
        val second = b.transactions.single().let { it.copy(values = it.values.copy(sourceType = "wechat")) }
        val edge = CashRelationWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("relation"), null), "payment_mirror",
            first.target, second.target, first.components.single().reference, second.components.single().reference,
            first.target, first.components.single().reference, "expense", "expense", "confirmed", true, null, "manual", empty, "12.30", "fixture-v1", "choice", emptyList())
        val provider = b.sourceBindings.single().copy(sourceType = "wechat", sourceScopeKind = "account",
            sourceScopeKey = requireNotNull(com.finance.tracker.business.domain.CashSourceScope.sourceTransactionScopeKey("wechat")),
            identityKind = "provider_id", identityStrength = "provider", identityKey = "mirror-provider")
        val oldMember = a.entries.single().members.single()
        val entry = b.entries.single().copy(note = "D", manualFields = listOf("note"), editedFields = listOf("note"),
            members = listOf(oldMember.copy(standaloneValues = JsonObject(oldMember.standaloneValues.jsonObject + ("note" to JsonPrimitive("C"))),
                standaloneManualFields = listOf("note")), b.entries.single().members.single()))
        val input = CashWriteSetDto(transactions = listOf(first, second), sourceBindings = listOf(a.sourceBindings.single(), provider), entries = listOf(entry),
            relations = listOf(edge), relationGroups = listOf(CashRelationWriteGroupDto(listOf(edge.target.reference), buildJsonObject { put("note", "B") })))
        val base = sequence(db, ws)
        val receipt = CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, payload(db, ws, input), "combined")
        val after = facts(db, ws)
        assertEquals(setOf("S"), after.cashTransactions.map { it.note }.toSet()); assertEquals("D", after.cashEntries.single().note)
        assertEquals(setOf("C", "S"), after.cashEntryMembers.map { it.standaloneValues.jsonObject.getValue("note").jsonPrimitive.content }.toSet())
        assertEquals(base + 1, sequence(db, ws)); assertEquals(user, after.transactionRelations.single().decidedBy)
        val results = db.read { ReceiptRepository(it, ws, db.sqlite).results(user, receipt.getValue("receipt_id").jsonPrimitive.content)!! }.getValue("items").jsonArray
        assertEquals(after.transactionRelations.single().id, results.single { it.jsonObject["result_kind"] == JsonPrimitive("relation") }.jsonObject.getValue("relation_id").jsonPrimitive.content)
    }
    @Test fun rawInputBudgetIsEnforcedBeforeContractDecodeAndNewWrites() = authenticated { db, ws, _, token, _ ->
        val state = detailDatabaseState(db)
        val input = JsonObject(mapOf("large" to JsonPrimitive("x".repeat(CashImportProtocol.MAX_PLAN_INPUT_BYTES))))
        assertEquals("write_set_payload_invalid", assertFailsWith<CashWriteSetSubmissionFailure> {
            CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, input, "budget") }.code)
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun unchangedExistingSourceUsesActualEntryDependenciesAndDoesNotAdvanceVersion() = authenticated { db, ws, account, token, user ->
        val service = CashImportWriteSetSubmissionService(db, now = clock)
        service.submit(token, ws, payload(db, ws, changes(account)), "create")
        val before = facts(db, ws); val base = sequence(db, ws); val parent = before.cashTransactions.single()
        val row = CashTransactionWriteDto(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(parent.id), parent.rowRevision),
            CashTransactionWriteValuesDto(parent.accountId, parent.state, parent.recordOrigin, parent.manualTransferId, parent.cashGranularity,
                parent.sourceType, parent.recordId, parent.sourcePayload, parent.relationMetadata, parent.sourceFingerprint, parent.manualOverrides,
                parent.occurredAt, parent.amount, parent.currency, parent.counterparty, parent.counterpartyAccount, parent.counterpartyAccountAttrs,
                parent.note, parent.categoryId, parent.recordType, parent.recordSubtype), before.cashTransactionComponents.map { part ->
                CashComponentWriteDto(ExistingCashWriteReferenceDto(part.id), part.accountId, part.amount, part.currency, part.ordinal, part.label, part.sourceKey, part.metadataJson) })
        val input = existingEntries(before).copy(transactions = listOf(row))
        val receipt = service.submit(token, ws, payload(db, ws, input), "same-value")
        assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws)); assertEquals(JsonPrimitive("no_changes"), receipt["status"])
        val result = db.read { ReceiptRepository(it, ws, db.sqlite).results(user, receipt.getValue("receipt_id").jsonPrimitive.content)!! }.getValue("items").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("unchanged"), result["outcome"])
    }
    @Test fun entryOnlyEditReadsDeclaredRealGroupWithoutIncludingUnrelatedEntries() = authenticated { db, ws, account, token, _ ->
        val service = CashImportWriteSetSubmissionService(db, now = clock)
        service.submit(token, ws, payload(db, ws, changes(account)), "first-entry")
        service.submit(token, ws, payload(db, ws, changes(account, "unrelated")), "unrelated-entry")
        val before = facts(db, ws); val base = sequence(db, ws)
        val firstId = before.cashTransactions.single { it.recordId == "source" }.id
        val original = existingEntries(before); val entry = original.entries.single { it.rootTransaction.reference == ExistingCashWriteReferenceDto(firstId) }
        val dependency = original.entryDependencies.single { it.reference == ExistingCashWriteReferenceDto(before.cashEntries.single { it.rootTransactionId == firstId }.id) }
        val edited = entry.copy(note = "D only", manualFields = listOf("note"), editedFields = listOf("note"),
            members = entry.members.map { it.copy(standaloneValues = JsonObject(it.standaloneValues.jsonObject + ("note" to JsonPrimitive("D only"))), standaloneManualFields = listOf("note")) })
        service.submit(token, ws, payload(db, ws, CashWriteSetDto(entries = listOf(edited), entryDependencies = listOf(dependency))), "d-only")
        val after = facts(db, ws)
        assertEquals(before.cashTransactions, after.cashTransactions); assertEquals(base + 1, sequence(db, ws))
        assertEquals("D only", after.cashEntries.single { it.rootTransactionId == firstId }.note)
        assertEquals(before.cashEntries.single { it.rootTransactionId != firstId }, after.cashEntries.single { it.rootTransactionId != firstId })
    }

    @Test fun bodyDigestAndWritesUseTheSameFrozenRootWhileWaitingForWorkspaceLock() = authenticated { db, ws, account, token, _ ->
        val original = payload(db, ws, changes(account)); val backing = original.toMutableMap(); val mutable = JsonObject(backing)
        val replacement = payload(db, ws, changes(account, "substituted"))
        val receipt = CashImportWriteSetSubmissionService(db, now = clock, beforeWorkspaceLock = { backing["changes"] = replacement.getValue("changes") })
            .submit(token, ws, mutable, "frozen-root")
        assertEquals("source", facts(db, ws).cashTransactions.single().recordId)
        val state = detailDatabaseState(db)
        assertEquals(receipt, CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, original, "frozen-root"))
        assertEquals(state, detailDatabaseState(db))
    }
    @Test fun bodySnapshotAlsoFreezesNestedArraysBeforeWorkspaceLock() = authenticated { db, ws, account, token, _ ->
        val elements = mutableListOf<JsonElement>(JsonPrimitive(1)); val original = changes(account)
        val input = original.copy(transactions = original.transactions.map { it.copy(values = it.values.copy(sourcePayload = JsonObject(mapOf("items" to JsonArray(elements))))) })
        val encoded = payload(db, ws, input)
        // Install a caller-owned nested container after DTO encoding, so it really remains mutable at submit.
        val changes = encoded.getValue("changes").jsonObject; val rows = changes.getValue("transactions").jsonArray
        val row = rows.single().jsonObject; val values = row.getValue("values").jsonObject
        val rawRow = JsonObject(row + ("values" to JsonObject(values + ("source_payload" to JsonObject(mapOf("items" to JsonArray(elements)))))))
        val raw = JsonObject(encoded + ("changes" to JsonObject(changes + ("transactions" to JsonArray(listOf(rawRow))))))
        val frozen = Json.parseToJsonElement(raw.toString()).jsonObject
        val receipt = CashImportWriteSetSubmissionService(db, now = clock, beforeWorkspaceLock = { elements[0] = JsonPrimitive(2) })
            .submit(token, ws, raw, "frozen-array")
        assertEquals(JsonArray(listOf(JsonPrimitive(1))), facts(db, ws).cashTransactions.single().sourcePayload.jsonObject["items"])
        assertEquals(receipt, CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, frozen, "frozen-array"))
    }

    @Test fun lateAuthorizationLossRollsBackNewFactsAndReceiptForRevocationLogoutAndExpiry() = authenticated { db, ws, account, token, user ->
        val input = payload(db, ws, changes(account)); val before = detailDatabaseState(db)
        for (mode in listOf("revoke", "logout", "expiry")) {
            val service = CashImportWriteSetSubmissionService(db, now = clock, beforeFinalAuthorization = { c ->
                val sql = c.jooq(db.sqlite)
                when (mode) {
                    "revoke" -> sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
                    "logout" -> sql.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(user)).execute()
                    else -> sql.update(USER_SESSIONS).set(USER_SESSIONS.EXPIRES_AT, clock().minusSeconds(1)).where(USER_SESSIONS.USER_ID.eq(user)).execute()
                }
            })
            assertFailsWith<AccessFailure> { service.submit(token, ws, input, mode) }
            assertEquals(before, detailDatabaseState(db))
        }
    }
    @Test fun draftIssuesEmptyPagesMappingsAndEntriesShareReceiptCommitAndRollback() = authenticated { db, ws, account, token, user ->
        val original = changes(account); val row = original.transactions.single().let { it.copy(values = it.values.copy(state = "draft")) }
        val issue = CashIssueWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("issue"), null), row.target,
            "import:issue", "posting_incomplete", null, JsonNull, JsonNull, empty, emptyList())
        val page = LedgerSourceEmptyPageFactDto("ccb_debit", "fixture-v1", "a".repeat(64), 1, buildJsonObject {
            put("document_key", "a".repeat(64)); put("page_total", 2); put("complete", true)
            put("page_fingerprints", buildJsonObject { put("1", "b".repeat(64)) })
        })
        val mapping = CashStatementMappingWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("mapping"), null),
            "ccb_debit", "funding_group_v1", sourceDigest("funding-group-v1", "ccb_debit", "file_account", "6222", "6222"), account.toString())
        val input = payload(db, ws, original.copy(transactions = listOf(row), issues = listOf(issue), sourceEmptyPages = listOf(page), statementAccountMappings = listOf(mapping)))
        val before = detailDatabaseState(db); val base = sequence(db, ws)
        assertEquals("not_committed", assertFailsWith<CashImportCommitFailure> {
            CashImportWriteSetSubmissionService(db, now = clock, afterStep = { if (it == "receipt") error("late") }).submit(token, ws, input, "metadata") }.commitState)
        assertEquals(before, detailDatabaseState(db))
        val receipt = CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, input, "metadata")
        val after = facts(db, ws)
        assertEquals(1, after.cashTransactionIssues.size); assertEquals(1, after.cashSourceEmptyPages.size); assertEquals(1, after.statementAccountMappings.size)
        assertEquals(1, after.cashEntries.size); assertEquals(1, after.cashSourceBindings.size)
        assertEquals(user, after.statementAccountMappings.single().confirmedBy)
        assertEquals(base + 1, sequence(db, ws)); assertEquals(JsonPrimitive((base + 1).toString()), receipt["ledger_seq"])
    }

    private fun snapshot(db: Database, ws: String) = db.read { c -> c.jooq(db.sqlite).select(LEDGER_SNAPSHOTS.PAYLOAD).from(LEDGER_SNAPSHOTS)
        .where(LEDGER_SNAPSHOTS.WORKSPACE_ID.eq(ws)).fetchOne(LEDGER_SNAPSHOTS.PAYLOAD) }
    private fun observed(source: LedgerFactsDto) = existingEntries(source).entryDependencies
    private fun editedAlias(input: CashWriteSetDto, source: LedgerFactsDto, note: String): CashWriteSetDto {
        val edit = CashEntryBeforeRelationEditDto(observed(source).single(), buildJsonObject { put("note", note) })
        return input.copy(transactions = input.transactions.map { it.copy(entryEdit = edit) }, entryDependencies = observed(source),
            entries = input.entries.map { it.copy(note = note, manualFields = listOf("note"), members = it.members.map { member -> member.copy(
                standaloneValues = JsonObject(member.standaloneValues.jsonObject + ("note" to JsonPrimitive(note))), standaloneManualFields = listOf("note")) }) })
    }
    @Test fun exactNewSourceCommitsANewReceiptWithActualIdentityAndNoDuplicateEffects() = authenticated { db, ws, account, token, user ->
        val service = CashImportWriteSetSubmissionService(db, now = clock); val original = changes(account)
        service.submit(token, ws, payload(db, ws, original), "original-source")
        val before = facts(db, ws); val base = sequence(db, ws); val balance = snapshot(db, ws)
        val input = original.copy(entryDependencies = observed(before))
        val receipt = service.submit(token, ws, payload(db, ws, input), "new-key-same-source")
        assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws)); assertEquals(balance, snapshot(db, ws))
        assertEquals(JsonPrimitive("no_changes"), receipt["status"])
        val row = db.read { ReceiptRepository(it, ws, db.sqlite).results(user, receipt.getValue("receipt_id").jsonPrimitive.content)!! }
            .getValue("items").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(before.cashTransactions.single().id), row["transaction_id"]); assertEquals(JsonPrimitive("unchanged"), row["outcome"])
        assertTrue(input.transactions.single().target.reference is NewCashWriteReferenceDto); assertNull(input.transactions.single().target.expectedRevision)
    }
    @Test fun exactSourceStillAppliesItsObservedPublicEditAndNeverChangesSourceOrBalance() = authenticated { db, ws, account, token, _ ->
        val service = CashImportWriteSetSubmissionService(db, now = clock); val original = changes(account)
        service.submit(token, ws, payload(db, ws, original), "initial-source")
        val before = facts(db, ws); val base = sequence(db, ws); val balance = snapshot(db, ws)
        service.submit(token, ws, payload(db, ws, editedAlias(original, before, "public C")), "source-with-C")
        val after = facts(db, ws)
        assertEquals(before.cashTransactions, after.cashTransactions); assertEquals(before.cashTransactionComponents, after.cashTransactionComponents)
        assertEquals(before.cashSourceBindings, after.cashSourceBindings); assertEquals(balance, snapshot(db, ws)); assertEquals(base + 1, sequence(db, ws))
        assertEquals("public C", after.cashEntries.single().note); assertEquals(JsonArray(listOf(JsonPrimitive("note"))), after.cashEntries.single().manualFields)
        assertEquals("S", after.cashTransactions.single().note)
        val unchanged = sequence(db, ws)
        val receipt = service.submit(token, ws, payload(db, ws, editedAlias(original, after, "public C")), "same-C-new-key")
        assertEquals(after, facts(db, ws)); assertEquals(unchanged, sequence(db, ws)); assertEquals(JsonPrimitive("no_changes"), receipt["status"])
    }
    @Test fun exactSourceCanSaveNewAliasAndFinalMappingInTheSameSubmission() = authenticated { db, ws, account, token, _ ->
        val service = CashImportWriteSetSubmissionService(db, now = clock); val original = changes(account)
        service.submit(token, ws, payload(db, ws, original), "initial-binding")
        val before = facts(db, ws); val base = sequence(db, ws); val balance = snapshot(db, ws)
        val alias = original.sourceBindings.single().copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("new-alias"), null), sourceScopeKey = "other")
        val mapping = CashStatementMappingWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("new-mapping"), null),
            "ccb_debit", "funding_group_v1", "funding-key", account.toString())
        val input = original.copy(sourceBindings = original.sourceBindings + alias, statementAccountMappings = listOf(mapping), entryDependencies = observed(before))
        service.submit(token, ws, payload(db, ws, input), "alias-and-mapping")
        val after = facts(db, ws)
        assertEquals(before.cashTransactions, after.cashTransactions); assertEquals(before.cashTransactionComponents, after.cashTransactionComponents)
        assertEquals(before.cashEntries, after.cashEntries); assertEquals(balance, snapshot(db, ws))
        assertEquals(2, after.cashSourceBindings.size); assertEquals(1, after.statementAccountMappings.size); assertEquals(base + 1, sequence(db, ws))
    }

    private fun sourceIssue(owner: CashWriteVersionedReferenceDto) = CashIssueWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("source-issue"), null),
        owner, "import:pending", "source_update_pending", null, JsonNull, buildJsonObject { put("note", "later source") }, empty, emptyList())
    @Test fun aliasedSourceOwnsNewExistingAndResolvedIssuesWithoutPretendingItWasObserved() = authenticated { db, ws, account, token, _ ->
        val service = CashImportWriteSetSubmissionService(db, now = clock); val original = changes(account)
        service.submit(token, ws, payload(db, ws, original), "issue-source")
        val before = facts(db, ws); val source = before.cashTransactions.single(); val balance = snapshot(db, ws)
        val owner = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(source.id), source.rowRevision)
        val issue = sourceIssue(owner)
        service.submit(token, ws, payload(db, ws, original.copy(issues = listOf(issue), entryDependencies = observed(before))), "new-issue")
        val added = facts(db, ws); val current = added.cashTransactionIssues.single()
        assertEquals(source.id, current.transactionId); assertEquals(before.cashTransactions, added.cashTransactions)
        val updated = issue.copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(current.id), current.revision),
            transaction = original.transactions.single().target, pendingFields = buildJsonObject { put("note", "updated pending") })
        service.submit(token, ws, payload(db, ws, original.copy(issues = listOf(updated), entryDependencies = observed(added))), "update-issue")
        val revised = facts(db, ws); val saved = revised.cashTransactionIssues.single()
        assertEquals(current.id, saved.id); assertEquals(current.revision.toLong() + 1, saved.revision.toLong())
        assertEquals(updated.pendingFields, saved.pendingFields)
        service.submit(token, ws, payload(db, ws, original.copy(resolvedIssues = listOf(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(saved.id), saved.revision)),
            entryDependencies = observed(revised))), "clear-issue")
        assertTrue(facts(db, ws).cashTransactionIssues.isEmpty()); assertEquals(before.cashTransactions, facts(db, ws).cashTransactions)
        assertEquals(balance, snapshot(db, ws)); assertNull(original.transactions.single().target.expectedRevision)
    }
    @Test fun aliasedSourceRejectsMissingOldEntryObservationAndActualSelfCandidate() = authenticated { db, ws, account, token, _ ->
        val service = CashImportWriteSetSubmissionService(db, now = clock); val original = changes(account)
        service.submit(token, ws, payload(db, ws, original), "guard-source")
        val before = facts(db, ws); val state = detailDatabaseState(db)
        val missing = assertFailsWith<CashWriteSetSubmissionFailure> { service.submit(token, ws, payload(db, ws, original), "missing-dependency") }
        assertEquals("invalid_reference", missing.code); assertEquals(listOf("entry_dependencies"), missing.fields)
        val stale = original.copy(entryDependencies = observed(before).map { it.copy(expectedRevision = "999") })
        assertEquals("reference_changed", assertFailsWith<CashWriteSetSubmissionFailure> { service.submit(token, ws, payload(db, ws, stale), "stale-entry") }.code)
        val parent = before.cashTransactions.single(); val owner = original.transactions.single().target
        val self = sourceIssue(owner).copy(code = "source_identity_ambiguous", pendingFields = JsonNull,
            candidates = listOf(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(parent.id), parent.rowRevision)))
        assertEquals("source_candidate_changed", assertFailsWith<CashWriteSetSubmissionFailure> { service.submit(token, ws,
            payload(db, ws, original.copy(issues = listOf(self), entryDependencies = observed(before))), "self-candidate") }.code)
        assertEquals(state, detailDatabaseState(db))
    }
    @Test fun everyLateStageRollsBackAliasedSourceMetadataMappingAndPublicEditTogether() = authenticated { db, ws, account, token, _ ->
        val original = changes(account); CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, payload(db, ws, original), "rollback-source")
        val before = facts(db, ws); val state = detailDatabaseState(db); val base = sequence(db, ws)
        val alias = original.sourceBindings.single().copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("late-alias"), null), sourceScopeKey = "other")
        val mapping = CashStatementMappingWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("late-mapping"), null), "ccb_debit", "funding_group_v1", "late-funding", account.toString())
        val changed = editedAlias(original, before, "C saved").copy(sourceBindings = original.sourceBindings + alias,
            statementAccountMappings = listOf(mapping), issues = listOf(sourceIssue(original.transactions.single().target)))
        for (step in listOf("source_bindings", "source_metadata", "account_mappings", "entry_pre_edits", "relations", "entries", "balances", "receipt")) {
            var reached = false
            val service = CashImportWriteSetSubmissionService(db, now = clock, afterStep = { if (it == step) { reached = true; error("late:$step") } })
            val failure = assertFailsWith<CashImportCommitFailure> { service.submit(token, ws, payload(db, ws, changed), "rollback:$step") }
            assertTrue(reached, step); assertEquals("not_committed", failure.commitState); assertEquals(state, detailDatabaseState(db))
        }
        CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, payload(db, ws, changed), "complete-alias-effects")
        val after = facts(db, ws)
        assertEquals(1, after.cashTransactions.size); assertEquals(2, after.cashSourceBindings.size); assertEquals(1, after.statementAccountMappings.size)
        assertEquals(1, after.cashTransactionIssues.size); assertEquals("C saved", after.cashEntries.single().note); assertEquals(base + 1, sequence(db, ws))
    }
    @Test fun matchingSourceStillParticipatesInANewRelationAndItsOriginalBChoice() = authenticated { db, ws, account, token, _ ->
        val service = CashImportWriteSetSubmissionService(db, now = clock); val expense = changes(account)
        val refund = changes(account, "refund").let { it.copy(transactions = it.transactions.map { row -> row.copy(values = row.values.copy(
            amount = "12.30", recordType = "refund"), components = row.components.map { part -> part.copy(amount = "12.30") }) }) }
        service.submit(token, ws, payload(db, ws, expense), "expense"); service.submit(token, ws, payload(db, ws, refund), "refund")
        val before = facts(db, ws); val balance = snapshot(db, ws); val base = sequence(db, ws)
        val first = before.cashTransactions.single { it.recordId == "source" }; val second = before.cashTransactions.single { it.recordId == "sourcerefund" }
        val firstPart = before.cashTransactionComponents.single { it.cashTransactionId == first.id }; val secondPart = before.cashTransactionComponents.single { it.cashTransactionId == second.id }
        val actualFirst = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(first.id), first.rowRevision)
        val actualSecond = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(second.id), second.rowRevision)
        val edgeRef = NewCashWriteReferenceDto("new-refund")
        val edge = CashRelationWriteDto(CashWriteVersionedReferenceDto(edgeRef, null), "refund_offset", expense.transactions.single().target, actualSecond,
            expense.transactions.single().components.single().reference, ExistingCashWriteReferenceDto(secondPart.id), actualFirst, ExistingCashWriteReferenceDto(firstPart.id),
            "expense", "refund", "confirmed", true, null, "rule", empty, "12.30", "rule", "user", emptyList())
        val member = refund.entries.single().members.single().copy(transaction = actualSecond)
        val entry = expense.entries.single().copy(note = "B chosen", manualFields = listOf("note"), members = expense.entries.single().members + member)
        val input = expense.copy(relations = listOf(edge), relationGroups = listOf(CashRelationWriteGroupDto(listOf(edgeRef), buildJsonObject { put("note", "B chosen") })),
            entries = listOf(entry), entryDependencies = observed(before))
        service.submit(token, ws, payload(db, ws, input), "source-plus-relation")
        val after = facts(db, ws)
        assertEquals(before.cashTransactions, after.cashTransactions); assertEquals(before.cashTransactionComponents, after.cashTransactionComponents)
        assertEquals(balance, snapshot(db, ws)); assertEquals(base + 1, sequence(db, ws)); assertEquals(1, after.transactionRelations.size)
        assertEquals(first.id, after.transactionRelations.single().anchorTransactionId); assertEquals(firstPart.id, after.transactionRelations.single().primaryComponentId)
        assertEquals("B chosen", after.cashEntries.single().note); assertEquals(2, after.cashEntryMembers.size)
        assertTrue(after.cashEntryMembers.all { it.standaloneValues.jsonObject["note"] == JsonPrimitive("S") })
    }

}
