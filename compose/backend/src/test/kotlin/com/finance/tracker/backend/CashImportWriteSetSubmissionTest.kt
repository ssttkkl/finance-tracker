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

    private fun finalMapping(account: Long) = CashStatementMappingWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("mapping"), null),
        "ccb_debit", "funding_group_v1", "same-funding-key", account.toString())

    @Test fun newMappingExactReimportKeepsActualIdentityConfirmationAndLedgerVersion() = authenticated { db, ws, account, token, user ->
        val original = changes(account).copy(statementAccountMappings = listOf(finalMapping(account)))
        val service = CashImportWriteSetSubmissionService(db, now = clock)
        service.submit(token, ws, payload(db, ws, original), "mapping-first")
        val before = facts(db, ws); val base = sequence(db, ws); val storedSnapshot = snapshot(db, ws)
        val input = original.copy(entryDependencies = observed(before))
        val receipt = CashImportWriteSetSubmissionService(db, now = { clock().plusSeconds(3600) })
            .submit(token, ws, payload(db, ws, input), "mapping-new-key")
        assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws)); assertEquals(storedSnapshot, snapshot(db, ws))
        assertEquals(user, before.statementAccountMappings.single().confirmedBy)
        assertEquals(JsonPrimitive("no_changes"), receipt["status"])
        assertIs<NewCashWriteReferenceDto>(input.statementAccountMappings.single().target.reference)
        assertNull(input.statementAccountMappings.single().target.expectedRevision)
        val request = payload(db, ws, input); val state = detailDatabaseState(db)
        assertEquals(receipt, service.submit(token, ws, request, "mapping-new-key"))
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun duplicateNewMappingDoesNotConsumeIndependentSourceEffectsOrTheirRollback() = authenticated { db, ws, account, token, _ ->
        val mapping = finalMapping(account)
        CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws,
            payload(db, ws, CashWriteSetDto(statementAccountMappings = listOf(mapping))), "mapping-only")
        val before = facts(db, ws); val base = sequence(db, ws); val state = detailDatabaseState(db)
        val input = changes(account).copy(statementAccountMappings = listOf(mapping))
        val request = payload(db, ws, input)
        for (step in listOf("account_mappings", "entries", "receipt")) {
            val failure = assertFailsWith<CashImportCommitFailure> {
                CashImportWriteSetSubmissionService(db, now = clock, afterStep = { if (it == step) error("late mapping replay") })
                    .submit(token, ws, request, "mapping-and-source")
            }
            assertEquals("not_committed", failure.commitState); assertEquals("late mapping replay", failure.failure.message)
            assertEquals(state, detailDatabaseState(db))
        }
        CashImportWriteSetSubmissionService(db, now = { clock().plusSeconds(3600) }).submit(token, ws, request, "mapping-and-source")
        val after = facts(db, ws)
        assertEquals(before.statementAccountMappings, after.statementAccountMappings)
        assertEquals(1, after.cashTransactions.size); assertEquals(1, after.cashEntries.size); assertEquals(base + 1, sequence(db, ws))
    }

    @Test fun newMappingCannotOverwriteAnotherAccountAndExistingStillRequiresOriginalRevision() = authenticated { db, ws, account, token, _ ->
        val other = db.write { c -> deletionInsert(c, db, "accounts", linkedMapOf(
            "workspace_id" to ws, "name" to "Other mapping account", "type" to "cash", "active" to true, "revision" to 1L,
            "currencies" to JsonArray(listOf(JsonPrimitive("CNY"))), "metadata_json" to empty, "created_at" to deletionTime, "updated_at" to deletionTime))!! }
        val mapping = finalMapping(account)
        val service = CashImportWriteSetSubmissionService(db, now = clock)
        service.submit(token, ws, payload(db, ws, CashWriteSetDto(statementAccountMappings = listOf(mapping))), "mapping-first")
        val before = facts(db, ws); val old = before.statementAccountMappings.single(); val state = detailDatabaseState(db)
        val changed = mapping.copy(accountId = other.toString())
        assertEquals("duplicate_reference", assertFailsWith<CashWriteSetSubmissionFailure> {
            service.submit(token, ws, payload(db, ws, changes(account).copy(statementAccountMappings = listOf(changed))), "mapping-conflict") }.code)
        assertEquals(state, detailDatabaseState(db))
        val observed = changed.copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(old.id), old.revision))
        val base = sequence(db, ws)
        service.submit(token, ws, payload(db, ws, CashWriteSetDto(statementAccountMappings = listOf(observed))), "mapping-update")
        val saved = facts(db, ws).statementAccountMappings.single()
        assertEquals(old.id, saved.id); assertEquals(other.toString(), saved.accountId)
        assertEquals((old.revision.toLong() + 1).toString(), saved.revision); assertEquals(base + 1, sequence(db, ws))
        val committed = detailDatabaseState(db)
        assertEquals("reference_changed", assertFailsWith<CashWriteSetSubmissionFailure> {
            service.submit(token, ws, payload(db, ws, CashWriteSetDto(statementAccountMappings = listOf(observed))), "mapping-stale") }.code)
        assertEquals("duplicate_reference", assertFailsWith<CashWriteSetSubmissionFailure> {
            service.submit(token, ws, payload(db, ws, CashWriteSetDto(statementAccountMappings = listOf(mapping))), "mapping-old-value") }.code)
        assertEquals(committed, detailDatabaseState(db))
    }

    @Test fun exactNewMappingCannotBypassDuplicateDeclarationsOrInactiveAccountGate() = authenticated { db, ws, account, token, _ ->
        val mapping = finalMapping(account); val service = CashImportWriteSetSubmissionService(db, now = clock)
        service.submit(token, ws, payload(db, ws, CashWriteSetDto(statementAccountMappings = listOf(mapping))), "mapping-first")
        val old = facts(db, ws).statementAccountMappings.single(); val state = detailDatabaseState(db)
        val existing = mapping.copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(old.id), old.revision))
        val another = mapping.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("another-mapping"), null))
        for ((index, rows) in listOf(listOf(mapping, another), listOf(mapping, existing), listOf(existing, mapping)).withIndex()) {
            assertEquals("duplicate_reference", assertFailsWith<CashWriteSetSubmissionFailure> {
                service.submit(token, ws, payload(db, ws, CashWriteSetDto(statementAccountMappings = rows)), "mapping-duplicate-$index") }.code)
            assertEquals(state, detailDatabaseState(db))
        }
        db.write { c -> c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.ACTIVE, false)
            .where(ACCOUNTS.WORKSPACE_ID.eq(ws).and(ACCOUNTS.ID.eq(account))).execute(); c.markLedgerChanged(ws) }
        val inactive = detailDatabaseState(db)
        assertEquals("funding_mapping_invalid", assertFailsWith<CashWriteSetSubmissionFailure> {
            service.submit(token, ws, payload(db, ws, CashWriteSetDto(statementAccountMappings = listOf(mapping))), "mapping-inactive") }.code)
        assertEquals(inactive, detailDatabaseState(db))
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
    private fun twoIssueSources(account: Long): CashWriteSetDto {
        val first = changes(account); val second = changes(account, "candidate")
        return first.copy(transactions = first.transactions + second.transactions, entries = first.entries + second.entries,
            sourceBindings = first.sourceBindings + second.sourceBindings)
    }
    private fun observedCandidateUpdate(input: CashWriteSetDto, before: LedgerFactsDto): CashWriteSetDto {
        val original = input.transactions.single { it.values.recordId == "sourcecandidate" }
        val old = before.cashTransactions.single { it.recordId == original.values.recordId }
        val target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(old.id), old.rowRevision)
        fun replace(value: CashWriteVersionedReferenceDto) = if (value.reference == original.target.reference) target else value
        val row = original.copy(target = target, values = original.values.copy(sourcePayload = buildJsonObject { put("changed", true) }),
            components = original.components.map { part -> part.copy(reference = ExistingCashWriteReferenceDto(
                before.cashTransactionComponents.single { it.cashTransactionId == old.id && it.ordinal == part.ordinal }.id)) })
        return input.copy(transactions = input.transactions.map { if (it.target == original.target) row else it },
            sourceBindings = input.sourceBindings.map { it.copy(transaction = if (it.transaction == original.target.reference) target.reference else it.transaction) },
            entries = input.entries.map { it.copy(rootTransaction = replace(it.rootTransaction), members = it.members.map { member -> member.copy(transaction = replace(member.transaction)) }) },
            issues = input.issues.map { it.copy(candidates = it.candidates.map(::replace)) }, entryDependencies = observed(before))
    }

    @Test fun exactNewSourceIssueReimportPreservesActualIssueAndAllAuditMetadata() = authenticated { db, ws, account, token, _ ->
        val service = CashImportWriteSetSubmissionService(db, now = clock); val original = changes(account)
        service.submit(token, ws, payload(db, ws, original), "issue-seed")
        val seeded = facts(db, ws)
        val issue = sourceIssue(original.transactions.single().target).copy(pendingSourcePayload = Json.parseToJsonElement("""{"one":1,"large":9007199254740993}"""), origin = JsonNull)
        val input = original.copy(issues = listOf(issue), entryDependencies = observed(seeded))
        service.submit(token, ws, payload(db, ws, input), "issue-first")
        val before = facts(db, ws); val base = sequence(db, ws); val storedSnapshot = snapshot(db, ws)
        val numeric = issue.copy(pendingSourcePayload = Json.parseToJsonElement("""{"large":9007199254740993,"one":true}"""))
        val request = payload(db, ws, input.copy(issues = listOf(numeric), entryDependencies = observed(before)))
        val receipt = CashImportWriteSetSubmissionService(db, now = { clock().plusSeconds(3600) }).submit(token, ws, request, "issue-repeat")
        assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws)); assertEquals(storedSnapshot, snapshot(db, ws))
        assertEquals(JsonPrimitive("no_changes"), receipt["status"]); assertNull(numeric.target.expectedRevision)
        val state = detailDatabaseState(db)
        assertEquals(receipt, service.submit(token, ws, request, "issue-repeat")); assertEquals(state, detailDatabaseState(db))
    }

    @Test fun exactNewAmbiguityKeepsCandidateRowIdentitiesAndIgnoresTheirOrdering() = authenticated { db, ws, account, token, _ ->
        val service = CashImportWriteSetSubmissionService(db, now = clock); val original = twoIssueSources(account)
        val third = changes(account, "third")
        val sources = original.copy(transactions = original.transactions + third.transactions, entries = original.entries + third.entries,
            sourceBindings = original.sourceBindings + third.sourceBindings)
        service.submit(token, ws, payload(db, ws, sources), "ambiguity-seed")
        val seeded = facts(db, ws)
        val issue = sourceIssue(sources.transactions.first().target).copy(code = "source_identity_ambiguous", pendingFields = JsonNull,
            candidates = sources.transactions.drop(1).map { it.target })
        service.submit(token, ws, payload(db, ws, sources.copy(issues = listOf(issue), entryDependencies = observed(seeded))), "ambiguity-first")
        val before = facts(db, ws); val base = sequence(db, ws)
        val replay = sources.copy(issues = listOf(issue.copy(candidates = issue.candidates.reversed())), entryDependencies = observed(before))
        val receipt = service.submit(token, ws, payload(db, ws, replay), "ambiguity-repeat")
        assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws)); assertEquals(2, before.cashIssueCandidates.size)
        assertEquals(JsonPrimitive("no_changes"), receipt["status"])
    }

    @Test fun exactNewIssueCannotRefreshCandidateProofAfterSourceUpdateButObservedExistingCan() = authenticated { db, ws, account, token, _ ->
        val service = CashImportWriteSetSubmissionService(db, now = clock); val original = twoIssueSources(account)
        service.submit(token, ws, payload(db, ws, original), "candidate-seed")
        val issue = sourceIssue(original.transactions.first().target).copy(code = "source_identity_ambiguous", pendingFields = JsonNull,
            candidates = listOf(original.transactions.last().target))
        service.submit(token, ws, payload(db, ws, original.copy(issues = listOf(issue), entryDependencies = observed(facts(db, ws)))), "candidate-first")
        val before = facts(db, ws); val state = detailDatabaseState(db); val base = sequence(db, ws)
        val changed = observedCandidateUpdate(original.copy(issues = listOf(issue)), before)
        assertEquals("source_identity_conflict", assertFailsWith<CashWriteSetSubmissionFailure> {
            service.submit(token, ws, payload(db, ws, changed), "candidate-changed") }.code)
        assertEquals(state, detailDatabaseState(db))
        val old = before.cashTransactionIssues.single()
        val observedIssue = changed.issues.single().copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(old.id), old.revision))
        service.submit(token, ws, payload(db, ws, changed.copy(issues = listOf(observedIssue))), "candidate-observed")
        val after = facts(db, ws); val candidate = after.cashTransactions.single { it.recordId == "sourcecandidate" }
        assertEquals(old.id, after.cashTransactionIssues.single().id); assertEquals("2", after.cashTransactionIssues.single().revision)
        assertEquals("2", candidate.rowRevision); assertEquals(candidate.rowRevision, after.cashIssueCandidates.single().expectedRowRevision)
        assertEquals(base + 1, sequence(db, ws))
    }

    @Test fun newEmptyAmbiguityRetainsWholeStoredIssueEvenWhenCandidateIsAlreadyStale() = authenticated { db, ws, account, token, _ ->
        val service = CashImportWriteSetSubmissionService(db, now = clock); val original = twoIssueSources(account)
        service.submit(token, ws, payload(db, ws, original), "retained-seed")
        val issue = sourceIssue(original.transactions.first().target).copy(code = "source_identity_ambiguous", pendingFields = JsonNull,
            candidates = listOf(original.transactions.last().target))
        service.submit(token, ws, payload(db, ws, original.copy(issues = listOf(issue), entryDependencies = observed(facts(db, ws)))), "retained-first")
        service.submit(token, ws, payload(db, ws, observedCandidateUpdate(original, facts(db, ws))), "make-candidate-stale")
        val before = facts(db, ws); val base = sequence(db, ws)
        assertEquals("1", before.cashIssueCandidates.single().expectedRowRevision)
        val owner = changes(account)
        val retained = issue.copy(candidates = emptyList(), pendingFields = buildJsonObject { put("note", "ignored") }, origin = buildJsonObject { put("table_key", "ignored") })
        val parentEntry = before.cashEntries.single { it.rootTransactionId == before.cashTransactions.single { it.recordId == "source" }.id }
        val input = owner.copy(issues = listOf(retained), entryDependencies = listOf(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(parentEntry.id), parentEntry.revision)))
        service.submit(token, ws, payload(db, ws, input), "retain-new-reference")
        assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws))
        val state = detailDatabaseState(db)
        val bad = retained.copy(origin = buildJsonObject { put("forbidden", "x") })
        assertFailsWith<CashWriteSetSubmissionFailure> { service.submit(token, ws, payload(db, ws, input.copy(issues = listOf(bad))), "retained-bad-input") }
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun exactNewIssueDoesNotConsumeIndependentSourceAndLateFailureRollsBackEverything() = authenticated { db, ws, account, token, _ ->
        val service = CashImportWriteSetSubmissionService(db, now = clock); val original = changes(account)
        service.submit(token, ws, payload(db, ws, original), "independent-seed")
        val issue = sourceIssue(original.transactions.single().target)
        service.submit(token, ws, payload(db, ws, original.copy(issues = listOf(issue), entryDependencies = observed(facts(db, ws)))), "independent-issue")
        val before = facts(db, ws); val state = detailDatabaseState(db); val base = sequence(db, ws); val other = changes(account, "independent")
        val input = original.copy(transactions = original.transactions + other.transactions, entries = original.entries + other.entries,
            sourceBindings = original.sourceBindings + other.sourceBindings, issues = listOf(issue), entryDependencies = observed(before))
        val request = payload(db, ws, input)
        for (step in listOf("source_metadata", "entries", "receipt")) {
            val failure = assertFailsWith<CashImportCommitFailure> { CashImportWriteSetSubmissionService(db, now = clock,
                afterStep = { if (it == step) error("late exact issue") }).submit(token, ws, request, "independent") }
            assertEquals("not_committed", failure.commitState); assertEquals("late exact issue", failure.failure.message)
            assertEquals(state, detailDatabaseState(db))
        }
        service.submit(token, ws, request, "independent")
        val after = facts(db, ws); assertEquals(before.cashTransactionIssues, after.cashTransactionIssues)
        assertEquals(2, after.cashTransactions.size); assertEquals(base + 1, sequence(db, ws))
    }

    @Test fun newIssueConflictsDuplicateClaimsAndExplicitRetirementKeepTheirOriginalBoundaries() = authenticated { db, ws, account, token, _ ->
        val service = CashImportWriteSetSubmissionService(db, now = clock); val original = changes(account)
        service.submit(token, ws, payload(db, ws, original), "issue-boundary-seed")
        val issue = sourceIssue(original.transactions.single().target)
        service.submit(token, ws, payload(db, ws, original.copy(issues = listOf(issue), entryDependencies = observed(facts(db, ws)))), "issue-boundary-first")
        val before = facts(db, ws); val old = before.cashTransactionIssues.single(); val state = detailDatabaseState(db)
        val observedIssue = issue.copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(old.id), old.revision))
        val next = issue.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("another-issue"), null))
        val baseInput = original.copy(entryDependencies = observed(before))
        val rejected = listOf(listOf(issue, next), listOf(issue, observedIssue), listOf(observedIssue, issue),
            listOf(issue.copy(pendingFields = buildJsonObject { put("note", "different") })),
            listOf(observedIssue.copy(target = observedIssue.target.copy(expectedRevision = "999"))))
        for ((index, rows) in rejected.withIndex()) {
            assertFailsWith<CashWriteSetSubmissionFailure> { service.submit(token, ws, payload(db, ws, baseInput.copy(issues = rows)), "issue-invalid-$index") }
            assertEquals(state, detailDatabaseState(db))
        }
        val base = sequence(db, ws)
        service.submit(token, ws, payload(db, ws, baseInput.copy(issues = listOf(issue), resolvedIssues = listOf(observedIssue.target))), "issue-replace")
        val saved = facts(db, ws).cashTransactionIssues.single()
        assertNotEquals(old.id, saved.id); assertEquals("1", saved.revision); assertEquals(base + 1, sequence(db, ws))
    }

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

    private fun refundRelationInput(db: Database, ws: String, account: Long, token: String, refunds: Int = 1): CashWriteSetDto {
        val service = CashImportWriteSetSubmissionService(db, now = clock); val expense = changes(account)
        service.submit(token, ws, payload(db, ws, expense), "seed-expense")
        val refundInputs = (1..refunds).map { i -> changes(account, "refund$i").let { it.copy(transactions = it.transactions.map { row ->
            row.copy(values = row.values.copy(amount = if (refunds == 1) "12.30" else "6.15", recordType = "refund"),
                components = row.components.map { part -> part.copy(amount = if (refunds == 1) "12.30" else "6.15") }) }) } }
        refundInputs.forEachIndexed { i, input -> service.submit(token, ws, payload(db, ws, input), "seed-refund:$i") }
        val before = facts(db, ws); val first = before.cashTransactions.single { it.recordId == "source" }
        fun target(id: String) = before.cashTransactions.single { it.id == id }.let { CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(id), it.rowRevision) }
        fun part(id: String) = ExistingCashWriteReferenceDto(before.cashTransactionComponents.single { it.cashTransactionId == id }.id)
        val others = (1..refunds).map { i -> before.cashTransactions.single { it.recordId == "sourcerefund$i" } }
        val edges = others.mapIndexed { i, other -> CashRelationWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("refund-edge:$i"), null),
            "refund_offset", expense.transactions.single().target, target(other.id), expense.transactions.single().components.single().reference,
            part(other.id), target(first.id), part(first.id), "expense", "refund", "confirmed", true, null, "rule", empty,
            if (refunds == 1) "12.30" else "6.15", "rule", "user", emptyList()) }
        val entry = expense.entries.single().copy(note = "B chosen", manualFields = listOf("note"),
            members = expense.entries.single().members + refundInputs.zip(others).map { (input, other) -> input.entries.single().members.single().copy(transaction = target(other.id)) })
        return expense.copy(relations = edges, relationGroups = edges.map { CashRelationWriteGroupDto(listOf(it.target.reference), buildJsonObject { put("note", "B chosen") }) },
            entries = listOf(entry), entryDependencies = observed(before))
    }

    @Test fun completeRelationReplayReturnsActualIdsWithoutRepeatingBOrAdvancingAnything() = authenticated { db, ws, account, token, user ->
        val input = refundRelationInput(db, ws, account, token, refunds = 2)
        val service = CashImportWriteSetSubmissionService(db, now = clock)
        service.submit(token, ws, payload(db, ws, input), "first-relations")
        val before = facts(db, ws); val base = sequence(db, ws); val balance = snapshot(db, ws)
        val duplicate = input.copy(entryDependencies = observed(before))
        val receipt = service.submit(token, ws, payload(db, ws, duplicate), "same-facts-new-key")
        assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws)); assertEquals(balance, snapshot(db, ws))
        assertEquals(JsonPrimitive("no_changes"), receipt["status"])
        db.read { c ->
            val items = ReceiptRepository(c, ws, db.sqlite).results(user, receipt.getValue("receipt_id").jsonPrimitive.content)!!.getValue("items").jsonArray
            val relations = items.map { it.jsonObject }.filter { it["result_kind"] == JsonPrimitive("relation") }
            assertEquals(listOf("relation:0", "relation:1"), relations.map { it.getValue("item_ref").jsonPrimitive.content })
            assertTrue(relations.all { it["outcome"] == JsonPrimitive("duplicate") })
            assertEquals(before.transactionRelations.map { it.id }.toSet(), relations.map { it.getValue("relation_id").jsonPrimitive.content }.toSet())
        }
    }

    @Test fun aDuplicateRelationConsumesOnlyItsBAndStillAllowsNewMappingAndObservedC() = authenticated { db, ws, account, token, _ ->
        val input = refundRelationInput(db, ws, account, token); val service = CashImportWriteSetSubmissionService(db, now = clock)
        service.submit(token, ws, payload(db, ws, input), "first-relation")
        val before = facts(db, ws); val balance = snapshot(db, ws); val base = sequence(db, ws)
        val edit = CashEntryBeforeRelationEditDto(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(before.cashEntries.single().id), before.cashEntries.single().revision),
            buildJsonObject { put("note", "new C") })
        val mapping = CashStatementMappingWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("new-mapping"), null), "ccb_debit", "funding_group_v1", "new-group", account.toString())
        val duplicate = input.copy(transactions = input.transactions.map { it.copy(entryEdit = edit) }, entryDependencies = observed(before),
            entries = input.entries.map { it.copy(note = "new C") }, statementAccountMappings = listOf(mapping))
        service.submit(token, ws, payload(db, ws, duplicate), "same-relation-new-effects")
        val after = facts(db, ws)
        assertEquals(before.transactionRelations, after.transactionRelations); assertEquals(before.cashTransactions, after.cashTransactions)
        assertEquals(before.cashTransactionComponents, after.cashTransactionComponents); assertEquals(before.cashEntryMembers, after.cashEntryMembers)
        assertEquals("new C", after.cashEntries.single().note); assertEquals(1, after.statementAccountMappings.size)
        assertEquals(base + 1, sequence(db, ws)); assertEquals(balance, snapshot(db, ws))
    }

    @Test fun aDraftRelationAndItsUnchangedValidCandidateAreAlsoDataIdempotent() = authenticated { db, ws, account, token, _ ->
        val input = refundRelationInput(db, ws, account, token); val before = facts(db, ws)
        val edge = input.relations.single().let { it.copy(state = "draft", appliedAmount = "0", candidates = listOf(
            CashRelationCandidateWriteDto(it.secondaryTransaction!!, it.secondaryComponent, buildJsonObject { put("why", "same") }, "valid"))) }
        val entries = existingEntries(before)
        val request = input.copy(relations = listOf(edge), relationGroups = listOf(CashRelationWriteGroupDto(listOf(edge.target.reference))),
            entries = entries.entries, entryDependencies = entries.entryDependencies)
        val service = CashImportWriteSetSubmissionService(db, now = clock)
        service.submit(token, ws, payload(db, ws, request), "draft-first")
        val stored = facts(db, ws); val base = sequence(db, ws)
        service.submit(token, ws, payload(db, ws, request.copy(entryDependencies = observed(stored))), "draft-again")
        assertEquals(stored, facts(db, ws)); assertEquals(base, sequence(db, ws)); assertEquals(1, stored.cashRelationCandidates.size)
    }

    @Test fun partialJointReplayDifferentFactsAndBadOriginalInputsAreRejectedBeforeConsumption() = authenticated { db, ws, account, token, _ ->
        val input = refundRelationInput(db, ws, account, token, refunds = 2); val service = CashImportWriteSetSubmissionService(db, now = clock)
        service.submit(token, ws, payload(db, ws, input), "first-joint")
        val before = facts(db, ws); val state = detailDatabaseState(db); val current = input.copy(entryDependencies = observed(before))
        fun denied(request: CashWriteSetDto, code: String) {
            val failure = assertFailsWith<CashWriteSetSubmissionFailure> { service.submit(token, ws, payload(db, ws, request), "bad-replay") }
            assertEquals(code, failure.code); assertEquals("not_committed", failure.commitState); assertEquals(state, detailDatabaseState(db))
        }
        denied(current.copy(relations = current.relations.take(1), relationGroups = current.relationGroups.take(1)), "relation_context_changed")
        val first = current.relations.first()
        for (changed in listOf(first.copy(appliedAmount = "1"), first.copy(state = "ignored"), first.copy(primaryRole = "refund"),
            first.copy(evidence = buildJsonObject { put("different", true) }),
            first.copy(anchorTransaction = first.secondaryTransaction!!, anchorComponent = first.secondaryComponent))) {
            val groups = if (changed.state == "confirmed") current.relationGroups else current.relationGroups.map { it.copy(commonFields = empty) }
            denied(current.copy(relations = listOf(changed) + current.relations.drop(1), relationGroups = groups), "relation_context_changed")
        }
        val duplicate = first.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("same-edge-twice"), null))
        denied(current.copy(relations = current.relations + duplicate, relationGroups = current.relationGroups + current.relationGroups.first().copy(relations = listOf(duplicate.target.reference))), "duplicate_reference")
        denied(current.copy(relationGroups = current.relationGroups.mapIndexed { i, group -> if (i == 0) group else group.copy(commonFields = buildJsonObject { put("note", "different B") }) }), "entry_result_invalid")
        denied(current.copy(relationGroups = current.relationGroups.map { it.copy(commonFields = buildJsonObject { put("note", 123) }) }), "invalid_field")
        denied(current.copy(relations = listOf(first.copy(secondaryTransaction = first.secondaryTransaction!!.copy(expectedRevision = "999"))) + current.relations.drop(1)), "reference_changed")
        denied(current.copy(entryDependencies = current.entryDependencies.map { it.copy(expectedRevision = "999") }), "reference_changed")
        denied(current.copy(relations = listOf(first.copy(evidence = buildJsonObject { put("huge", "x".repeat(70 * 1024)) })) + current.relations.drop(1)), "relation_data_invalid")
    }

    @Test fun originalTextBudgetsApplyToFirstWritesAndDuplicateRelationsEqually() = authenticated { db, ws, account, token, _ ->
        val input = refundRelationInput(db, ws, account, token); val service = CashImportWriteSetSubmissionService(db, now = clock)
        fun denied(request: CashWriteSetDto) {
            val state = detailDatabaseState(db)
            val failure = assertFailsWith<CashWriteSetSubmissionFailure> { service.submit(token, ws, payload(db, ws, request), "bad-text") }
            assertEquals("relation_data_invalid", failure.code); assertEquals("not_committed", failure.commitState); assertEquals(state, detailDatabaseState(db))
        }
        fun bad(request: CashWriteSetDto) = listOf(request.copy(relations = request.relations.map { it.copy(ruleId = "r".repeat(129)) }),
            request.copy(relations = request.relations.map { it.copy(decisionReason = "r".repeat(4097)) }))
        bad(input).forEach(::denied)
        service.submit(token, ws, payload(db, ws, input), "valid-text")
        bad(input.copy(entryDependencies = observed(facts(db, ws)))).forEach(::denied)
    }

    private fun draftInput(input: CashWriteSetDto, source: LedgerFactsDto): CashWriteSetDto {
        val edge = input.relations.single().let { it.copy(state = "draft", appliedAmount = "0", secondaryTransaction = null, secondaryComponent = null, secondaryRole = null, candidates = listOf(
            CashRelationCandidateWriteDto(it.secondaryTransaction!!, it.secondaryComponent, empty, "valid"))) }
        val entries = existingEntries(source)
        return input.copy(relations = listOf(edge), relationGroups = listOf(CashRelationWriteGroupDto(listOf(edge.target.reference))),
            entries = entries.entries, entryDependencies = entries.entryDependencies)
    }
    private fun observedSource(row: LedgerCashTransactionFactDto, source: LedgerFactsDto, note: String): CashTransactionWriteDto {
        val target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(row.id), row.rowRevision)
        val values = CashTransactionWriteValuesDto(row.accountId, row.state, row.recordOrigin, row.manualTransferId, row.cashGranularity, row.sourceType,
            row.recordId, row.sourcePayload, row.relationMetadata, row.sourceFingerprint, row.manualOverrides, row.occurredAt, row.amount, row.currency,
            row.counterparty, row.counterpartyAccount, row.counterpartyAccountAttrs, note, row.categoryId, row.recordType, row.recordSubtype)
        return CashTransactionWriteDto(target, values, source.cashTransactionComponents.filter { it.cashTransactionId == row.id }.map {
            CashComponentWriteDto(ExistingCashWriteReferenceDto(it.id), it.accountId, it.amount, it.currency, it.ordinal, it.label, it.sourceKey, it.metadataJson) })
    }

    private fun rejectChangedDraftSource(db: Database, ws: String, account: Long, token: String, anchor: Boolean) {
        val input = refundRelationInput(db, ws, account, token); val service = CashImportWriteSetSubmissionService(db, now = clock)
        val draft = draftInput(input, facts(db, ws)); service.submit(token, ws, payload(db, ws, draft), "draft-dependencies")
        val before = facts(db, ws); val state = detailDatabaseState(db)
        val first = before.cashTransactions.single { it.recordId == "source" }
        val row = before.cashTransactions.single { (it.id == first.id) == anchor }
            val edited = observedSource(row, before, "source changed")
            // The source stage and identity classifier are the production stages used by submit, before C/B/D.
            val request = CashWriteSetDto(transactions = listOf(edited), relations = draft.relations.map { edge -> edge.copy(
                primaryTransaction = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(first.id), first.rowRevision),
                primaryComponent = ExistingCashWriteReferenceDto(before.cashTransactionComponents.single { it.cashTransactionId == first.id }.id)) },
                relationGroups = draft.relationGroups, entryDependencies = observed(before))
            val failure = assertFailsWith<CashPolicyError> { db.write { c ->
                val context = CashSqlContext(c, ws, db.sqlite, { java.util.UUID.randomUUID().toString() }, clock)
                val sources = saveCashImportWriteSetSources(context, request)
                val sqlRevision = context.sql.select(CASH_TRANSACTIONS.ROW_REVISION).from(CASH_TRANSACTIONS)
                    .where(CASH_TRANSACTIONS.ID.eq(row.id.toLong())).fetchOne(CASH_TRANSACTIONS.ROW_REVISION)!!
                assertEquals(row.rowRevision.toLong() + 1, sqlRevision)
                resolveCashImportWriteSetRelationIdentities(context, sources)
                error("draft replay accepted a changed dependency")
            } }
            assertEquals("relation_context_changed", failure.code); assertEquals(state, detailDatabaseState(db))
    }
    @Test fun appliedDraftCandidatesCannotHideSameSubmissionChangesToTheirTarget() = authenticated { db, ws, account, token, _ ->
        rejectChangedDraftSource(db, ws, account, token, anchor = false)
    }
    @Test fun appliedDraftCandidatesCannotHideSameSubmissionChangesToTheirAnchor() = authenticated { db, ws, account, token, _ ->
        rejectChangedDraftSource(db, ws, account, token, anchor = true)
    }

    private fun sourceChangesInvalidateDraftCandidates(db: Database, ws: String, account: Long, token: String, anchor: Boolean) {
        val input = refundRelationInput(db, ws, account, token); val service = CashImportWriteSetSubmissionService(db, now = clock)
        val draft = draftInput(input, facts(db, ws)); service.submit(token, ws, payload(db, ws, draft), "draft-to-invalidate")
        val before = facts(db, ws); val base = sequence(db, ws); val balance = snapshot(db, ws)
        val row = before.cashTransactions.single { (it.recordId == "source") == anchor }
        db.write { c ->
            val context = CashSqlContext(c, ws, db.sqlite, { java.util.UUID.randomUUID().toString() }, clock)
            saveCashImportWriteSetSources(context, CashWriteSetDto(transactions = listOf(observedSource(row, before, "source changed"))))
            val actual = LedgerSnapshotRepository(c, ws, db.sqlite).facts()
            assertEquals(before.cashRelationCandidates.map { it.copy(validity = "stale") }, actual.cashRelationCandidates)
            assertEquals(before.transactionRelations.map { it.copy(rowRevision = (it.rowRevision.toLong() + 1).toString()) }, actual.transactionRelations)
        }
        val after = facts(db, ws)
        assertEquals("source changed", after.cashTransactions.single { it.id == row.id }.note)
        assertEquals(before.cashEntries, after.cashEntries); assertEquals(before.cashEntryMembers, after.cashEntryMembers)
        assertEquals(balance, snapshot(db, ws)); assertEquals(base + 1, sequence(db, ws))
    }
    @Test fun changingAnExistingSourceInvalidatesItsStoredDraftCandidateDependency() = authenticated { db, ws, account, token, _ ->
        sourceChangesInvalidateDraftCandidates(db, ws, account, token, anchor = false)
    }
    @Test fun changingAnExistingAnchorInvalidatesItsStoredDraftCandidates() = authenticated { db, ws, account, token, _ ->
        sourceChangesInvalidateDraftCandidates(db, ws, account, token, anchor = true)
    }

    @Test fun anIndependentFirstBatchCanCommitBesideAWholeAppliedBatch() = authenticated { db, ws, account, token, user ->
        val input = refundRelationInput(db, ws, account, token); val service = CashImportWriteSetSubmissionService(db, now = clock)
        service.submit(token, ws, payload(db, ws, input), "old-batch")
        val expense = changes(account, "other-expense")
        val refund = changes(account, "other-refund").let { it.copy(transactions = it.transactions.map { row -> row.copy(
            values = row.values.copy(amount = "12.30", recordType = "refund"), components = row.components.map { part -> part.copy(amount = "12.30") }) }) }
        service.submit(token, ws, payload(db, ws, expense), "other-expense"); service.submit(token, ws, payload(db, ws, refund), "other-refund")
        val before = facts(db, ws); val base = sequence(db, ws); val balance = snapshot(db, ws)
        fun row(record: String) = before.cashTransactions.single { it.recordId == record }
        fun target(record: String) = row(record).let { CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(it.id), it.rowRevision) }
        fun part(record: String) = ExistingCashWriteReferenceDto(before.cashTransactionComponents.single { it.cashTransactionId == row(record).id }.id)
        val primary = target("sourceother-expense"); val secondary = target("sourceother-refund")
        val fresh = input.relations.single().copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("independent-edge"), null),
            primaryTransaction = primary, secondaryTransaction = secondary, primaryComponent = part("sourceother-expense"), secondaryComponent = part("sourceother-refund"),
            anchorTransaction = primary, anchorComponent = part("sourceother-expense"))
        val entries = existingEntries(before)
        val combined = expense.entries.single().copy(rootTransaction = primary, note = "fresh B", manualFields = listOf("note"),
            members = listOf(expense.entries.single().members.single().copy(transaction = primary), refund.entries.single().members.single().copy(transaction = secondary)))
        val oldEntry = entries.entries.single { it.members.size == 2 }
        val request = input.copy(relations = listOf(fresh) + input.relations,
            relationGroups = listOf(CashRelationWriteGroupDto(listOf(fresh.target.reference), buildJsonObject { put("note", "fresh B") })) + input.relationGroups,
            entries = listOf(oldEntry, combined), entryDependencies = entries.entryDependencies)
        val receipt = service.submit(token, ws, payload(db, ws, request), "mixed-batches")
        val after = facts(db, ws)
        assertEquals(2, after.transactionRelations.size); assertEquals(base + 1, sequence(db, ws)); assertEquals(balance, snapshot(db, ws))
        val old = before.cashEntries.single { it.note == "B chosen" }
        assertEquals(old, after.cashEntries.single { it.id == old.id }); assertTrue(after.cashEntries.any { it.note == "fresh B" })
        db.read { c ->
            val result = ReceiptRepository(c, ws, db.sqlite).results(user, receipt.getValue("receipt_id").jsonPrimitive.content)!!.getValue("items").jsonArray
                .map { it.jsonObject }.filter { it["result_kind"] == JsonPrimitive("relation") }
            assertEquals(listOf("confirmed", "duplicate"), result.map { it.getValue("outcome").jsonPrimitive.content })
            assertEquals(before.transactionRelations.single().id, result.last().getValue("relation_id").jsonPrimitive.content)
        }
    }

    @Test fun appliedBatchLateFailuresRollBackNewEffectsAndLostResponseReplaysTheSameReceipt() = authenticated { db, ws, account, token, user ->
        val input = refundRelationInput(db, ws, account, token); val service = CashImportWriteSetSubmissionService(db, now = clock)
        service.submit(token, ws, payload(db, ws, input), "applied-before-failures")
        val before = facts(db, ws); val state = detailDatabaseState(db); val base = sequence(db, ws)
        val edit = CashEntryBeforeRelationEditDto(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(before.cashEntries.single().id), before.cashEntries.single().revision),
            buildJsonObject { put("note", "new C") })
        val request = input.copy(transactions = input.transactions.map { it.copy(entryEdit = edit) }, entries = input.entries.map { it.copy(note = "new C") },
            entryDependencies = observed(before), statementAccountMappings = listOf(CashStatementMappingWriteDto(
                CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("late-mapping"), null), "ccb_debit", "funding_group_v1", "late-group", account.toString())))
        val body = payload(db, ws, request)
        for (step in listOf("relation_identities", "account_mappings", "entry_pre_edits", "relations", "entries", "balances", "receipt")) {
            var reached = false
            val failure = assertFailsWith<CashImportCommitFailure> { CashImportWriteSetSubmissionService(db, now = clock,
                afterStep = { if (it == step) { reached = true; error("late:$step") } }).submit(token, ws, body, "retry-applied") }
            assertTrue(reached, step); assertEquals("not_committed", failure.commitState); assertEquals(state, detailDatabaseState(db))
        }
        val lost = assertFailsWith<CashImportCommitFailure> { CashImportWriteSetSubmissionService(db, now = clock,
            afterCommit = { error("lost") }).submit(token, ws, body, "retry-applied") }
        assertEquals("unknown", lost.commitState)
        val actual = db.read { ReceiptRepositoryView.strip(ReceiptRepository(it, ws, db.sqlite).byKey(user, "retry-applied")!!) }
        val committed = detailDatabaseState(db)
        assertEquals(actual, ReceiptReadService(db, clock).receiptByKey(token, "retry-applied"))
        assertEquals(actual, service.submit(token, ws, body, "retry-applied")); assertEquals(committed, detailDatabaseState(db))
        val after = facts(db, ws); assertEquals(before.transactionRelations, after.transactionRelations)
        assertEquals("new C", after.cashEntries.single().note); assertEquals(1, after.statementAccountMappings.size); assertEquals(base + 1, sequence(db, ws))
    }

    @Test fun anInactiveMemberOfAnOldJointGroupPreventsConsumingItsActiveSubset() = authenticated { db, ws, account, token, _ ->
        val input = refundRelationInput(db, ws, account, token, refunds = 2); val service = CashImportWriteSetSubmissionService(db, now = clock)
        service.submit(token, ws, payload(db, ws, input), "joint-before-disable")
        val old = facts(db, ws).transactionRelations.last()
        db.write { c ->
            c.jooq(db.sqlite).update(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.ACTIVE, false)
                .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(ws)).and(TRANSACTION_RELATIONS.ID.eq(old.id.toLong())).execute()
            c.markLedgerChanged(ws)
        }
        val before = facts(db, ws); val state = detailDatabaseState(db)
        val remaining = input.relations.filter { row -> row.secondaryTransaction!!.reference != ExistingCashWriteReferenceDto(old.secondaryTransactionId!!) }
        val request = input.copy(relations = remaining, relationGroups = input.relationGroups.filter { it.relations.single() == remaining.single().target.reference }, entryDependencies = observed(before))
        val failure = assertFailsWith<CashWriteSetSubmissionFailure> { service.submit(token, ws, payload(db, ws, request), "active-subset") }
        assertEquals("relation_context_changed", failure.code); assertEquals("not_committed", failure.commitState); assertEquals(state, detailDatabaseState(db))
    }

    @Test fun oneAppliedEdgeCannotBeMixedWithANewEdgeInTheSameJointBatch() = authenticated { db, ws, account, token, _ ->
        val input = refundRelationInput(db, ws, account, token); val service = CashImportWriteSetSubmissionService(db, now = clock)
        service.submit(token, ws, payload(db, ws, input), "applied-edge")
        val extra = changes(account, "extra-refund").let { it.copy(transactions = it.transactions.map { row -> row.copy(
            values = row.values.copy(amount = "12.30", recordType = "refund"), components = row.components.map { part -> part.copy(amount = "12.30") }) }) }
        service.submit(token, ws, payload(db, ws, extra), "extra-source")
        val before = facts(db, ws); val state = detailDatabaseState(db); val parent = before.cashTransactions.single { it.recordId == "sourceextra-refund" }
        val part = before.cashTransactionComponents.single { it.cashTransactionId == parent.id }
        val edge = input.relations.single().copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("new-edge"), null),
            secondaryTransaction = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(parent.id), parent.rowRevision), secondaryComponent = ExistingCashWriteReferenceDto(part.id))
        val entries = existingEntries(before)
        val request = input.copy(relations = input.relations + edge, relationGroups = input.relationGroups + input.relationGroups.single().copy(relations = listOf(edge.target.reference)),
            entries = entries.entries, entryDependencies = entries.entryDependencies)
        val failure = assertFailsWith<CashWriteSetSubmissionFailure> { service.submit(token, ws, payload(db, ws, request), "mixed-same-batch") }
        assertEquals("relation_context_changed", failure.code); assertEquals(state, detailDatabaseState(db))
    }

    @Test fun multipleCandidateSourceChangesPreserveTheOriginalPerRowRevisionAdvances() = authenticated { db, ws, account, token, _ ->
        val input = refundRelationInput(db, ws, account, token, refunds = 2); val beforeDraft = facts(db, ws)
        val edge = input.relations.first().copy(secondaryTransaction = null, secondaryComponent = null, secondaryRole = null, state = "draft", appliedAmount = "0",
            candidates = input.relations.map { CashRelationCandidateWriteDto(it.secondaryTransaction!!, it.secondaryComponent, empty, "valid") })
        val entries = existingEntries(beforeDraft)
        val draft = input.copy(relations = listOf(edge), relationGroups = listOf(CashRelationWriteGroupDto(listOf(edge.target.reference))),
            entries = entries.entries, entryDependencies = entries.entryDependencies)
        CashImportWriteSetSubmissionService(db, now = clock).submit(token, ws, payload(db, ws, draft), "two-candidates")
        val before = facts(db, ws)
        val updates = before.cashTransactions.filter { it.recordType == "refund" }.map { observedSource(it, before, "updated ${it.id}") }
        db.write { c -> saveCashImportWriteSetSources(CashSqlContext(c, ws, db.sqlite, { java.util.UUID.randomUUID().toString() }, clock), CashWriteSetDto(transactions = updates)) }
        val after = facts(db, ws)
        assertEquals(before.transactionRelations.single().rowRevision.toLong() + 2, after.transactionRelations.single().rowRevision.toLong())
        assertEquals(before.cashRelationCandidates.map { it.copy(validity = "stale") }, after.cashRelationCandidates)
    }

}
