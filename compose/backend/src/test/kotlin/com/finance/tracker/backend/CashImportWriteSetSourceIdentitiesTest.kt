package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.validateCashWriteSetConfirmedGraph
import com.finance.tracker.business.domain.validateCashWriteSetReferences
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CashImportWriteSetSourceIdentitiesTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private fun workspace(action: (Database, String, Long) -> Unit) = withCashWriteSetTestWorkspace(backend, action)
    private fun input(account: Long, name: String = "row", count: Int = 1): CashWriteSetDto {
        val target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto(name), null)
        val row = CashTransactionWriteDto(target, CashTransactionWriteValuesDto(if (count == 1) account.toString() else null,
            "posted", "import", null, if (count == 1) "atomic" else "aggregate", "ccb_debit", name, empty, empty, null, empty,
            "2026-11-14T00:00:00Z", "-12.30", "CNY", "Party", "", array, "S", null, "consumption", "not_applicable"),
            (0 until count).map { CashComponentWriteDto(NewCashWriteReferenceDto("$name:part:$it"), account.toString(),
                if (count == 1) "-12.30" else if (it == 0) "-5.00" else "-7.30", "CNY", it.toString(), "", "", empty) })
        val binding = CashSourceBindingWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("$name:binding"), null), target.reference,
            "ccb_debit", "file_local", "scope", "file_occurrence", name, "file_local", null, "fixture-v1", empty)
        return CashWriteSetDto(transactions = listOf(row), sourceBindings = listOf(binding))
    }
    private fun store(db: Database, ws: String, changes: CashWriteSetDto) = db.write { c ->
        check(c.lockWorkspace(db.sqlite, ws)); val context = CashSqlContext(c, ws, db.sqlite) { deletionTime }
        saveCashImportWriteSetBindings(context, saveCashImportWriteSetSources(context, changes))
    }
    private fun resolve(db: Database, ws: String, changes: CashWriteSetDto) = db.write { c ->
        check(c.lockWorkspace(db.sqlite, ws)); resolveCashImportWriteSetSourceIdentities(CashSqlContext(c, ws, db.sqlite), changes)
    }
    private fun facts(db: Database, ws: String) = db.read { LedgerSnapshotRepository(it, ws, db.sqlite).facts() }
    private fun policy(db: Database, ws: String, input: CashWriteSetDto, code: String = "source_identity_conflict") {
        val state = detailDatabaseState(db)
        assertEquals(code, assertFailsWith<CashPolicyError> { resolve(db, ws, input) }.code)
        assertEquals(state, detailDatabaseState(db))
    }
    @Test fun exactNewSourceReturnsRealParentPartAndBindingWithoutAnyWriteOrDtoRewrite() = workspace { db, ws, account ->
        val request = input(account); store(db, ws, request); val before = detailDatabaseState(db); val stored = facts(db, ws)
        val result = resolve(db, ws, request)
        assertEquals(stored.cashTransactions.single().id.toLong(), result.transactions[request.transactions.single().target.reference])
        assertEquals(stored.cashTransactionComponents.single().id.toLong(), result.components[request.transactions.single().components.single().reference])
        assertEquals(stored.cashSourceBindings.single().id.toLong(), result.bindings[request.sourceBindings.single().target.reference])
        assertEquals(stored, result.before); assertTrue(result.deletedBindings.isEmpty())
        assertIs<NewCashWriteReferenceDto>(request.transactions.single().target.reference)
        assertNull(request.transactions.single().target.expectedRevision); assertEquals(before, detailDatabaseState(db))
    }
    @Test fun noKeyMatchDoesNotInferAnIdentityFromFinancialValuesRecordIdOrTime() = workspace { db, ws, account ->
        val first = input(account); store(db, ws, first)
        val fresh = first.copy(sourceBindings = first.sourceBindings.map { it.copy(identityKey = "other-key") })
        val before = detailDatabaseState(db); val result = resolve(db, ws, fresh)
        assertTrue(result.transactions.isEmpty()); assertTrue(result.components.isEmpty()); assertTrue(result.bindings.isEmpty())
        assertEquals(before, detailDatabaseState(db))
    }
    @Test fun exactAmountsPartOrderAndJsonValuesUseOriginalStorageEquivalence() = workspace { db, ws, account ->
        val initial = input(account, count = 2); val row = initial.transactions.single()
        val first = initial.copy(transactions = listOf(row.copy(values = row.values.copy(sourcePayload = Json.parseToJsonElement("""{"n":9007199254740993,"v":1.2300}""")))))
        store(db, ws, first)
        val equivalent = first.copy(transactions = first.transactions.map { it.copy(values = it.values.copy(amount = "-12.300", occurredAt = "2026-11-14T08:00:00+08:00",
            sourcePayload = Json.parseToJsonElement("""{"v":1.23,"n":9007199254740993}""")), components = it.components.reversed().map { part -> part.copy(amount = part.amount + "0") }) })
        val before = detailDatabaseState(db); val result = resolve(db, ws, equivalent)
        val stored = facts(db, ws)
        assertEquals(1, result.transactions.size); assertEquals(2, result.components.size)
        for (part in equivalent.transactions.single().components) assertEquals(stored.cashTransactionComponents.single { it.ordinal == part.ordinal }.id.toLong(), result.components[part.reference])
        assertEquals(before, detailDatabaseState(db))
        policy(db, ws, equivalent.copy(transactions = equivalent.transactions.map { it.copy(values = it.values.copy(sourcePayload = Json.parseToJsonElement("""{"n":9007199254740992,"v":1.23}"""))) }))
    }
    @Test fun sameKeyDifferentSourceOrPartsCannotBecomeAnExistingUpdate() = workspace { db, ws, account ->
        val request = input(account); store(db, ws, request); val row = request.transactions.single()
        for (changed in listOf(row.copy(values = row.values.copy(note = "other")), row.copy(values = row.values.copy(counterparty = "other")),
            row.copy(values = row.values.copy(recordId = "different")), row.copy(values = row.values.copy(state = "draft")),
            row.copy(values = row.values.copy(amount = "-13.30"), components = row.components.map { it.copy(amount = "-13.30") }),
            row.copy(components = row.components.map { it.copy(ordinal = "1") }))) policy(db, ws, request.copy(transactions = listOf(changed)))
    }
    @Test fun frozenPostedAccountMayHaveBecomeInactiveWithoutInvalidatingExactIdentity() = workspace { db, ws, account ->
        val request = input(account); store(db, ws, request)
        db.write { c -> c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.ACTIVE, false).where(ACCOUNTS.WORKSPACE_ID.eq(ws).and(ACCOUNTS.ID.eq(account))).execute(); c.markLedgerChanged(ws) }
        val before = detailDatabaseState(db)
        assertEquals(1, resolve(db, ws, request).transactions.size); assertEquals(before, detailDatabaseState(db))
    }
    @Test fun naturalKeysAndStableEvidenceMustAgreeOnOneLiveOwner() = workspace { db, ws, account ->
        val a = input(account, "a"); val b = input(account, "b"); store(db, ws, a); store(db, ws, b)
        val request = a.copy(sourceBindings = a.sourceBindings + b.sourceBindings.map { it.copy(transaction = a.transactions.single().target.reference) })
        policy(db, ws, request)
        val stored = facts(db, ws); val secondBinding = stored.cashSourceBindings.single { it.identityKey == "b" }
        db.write { c -> c.jooq(db.sqlite).update(CASH_SOURCE_BINDINGS).set(CASH_SOURCE_BINDINGS.STABLE_EVIDENCE_KEY, "evidence").set(CASH_SOURCE_BINDINGS.SOURCE_SCOPE_KIND, "account").set(CASH_SOURCE_BINDINGS.SOURCE_SCOPE_KEY, "owner").set(CASH_SOURCE_BINDINGS.IDENTITY_STRENGTH, "validated")
            .where(CASH_SOURCE_BINDINGS.WORKSPACE_ID.eq(ws).and(CASH_SOURCE_BINDINGS.ID.eq(secondBinding.id.toLong()))).execute() }
        val byEvidence = a.sourceBindings.single().copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("evidence-binding"), null),
            sourceScopeKind = "account", sourceScopeKey = "owner", identityStrength = "validated", stableEvidenceKey = "evidence")
        policy(db, ws, a.copy(sourceBindings = a.sourceBindings + byEvidence))
    }
    @Test fun nonUniqueStableEvidenceCannotChooseTheFirstOwner() = workspace { db, ws, account ->
        val a = input(account, "a"); val b = input(account, "b"); store(db, ws, a); store(db, ws, b)
        db.write { c -> c.jooq(db.sqlite).update(CASH_SOURCE_BINDINGS).set(CASH_SOURCE_BINDINGS.STABLE_EVIDENCE_KEY, "same").set(CASH_SOURCE_BINDINGS.SOURCE_SCOPE_KIND, "account").set(CASH_SOURCE_BINDINGS.SOURCE_SCOPE_KEY, "owner").set(CASH_SOURCE_BINDINGS.IDENTITY_STRENGTH, "validated")
            .where(CASH_SOURCE_BINDINGS.WORKSPACE_ID.eq(ws)).execute() }
        val unknown = input(account, "unknown").let { it.copy(sourceBindings = it.sourceBindings.map { row -> row.copy(sourceScopeKind = "account",
            sourceScopeKey = "owner", identityStrength = "validated", stableEvidenceKey = "same") }) }
        policy(db, ws, unknown)
    }
    @Test fun deletedOwnerBindingCanBeReusedButItsParentIsNeverResurrected() = workspace { db, ws, account ->
        val request = input(account); store(db, ws, request); val stored = facts(db, ws)
        db.write { c -> c.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.DELETED_AT, deletionTime)
            .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(ws)).execute(); c.markLedgerChanged(ws) }
        val changed = request.copy(transactions = request.transactions.map { it.copy(values = it.values.copy(note = "new source")) })
        val before = detailDatabaseState(db); val result = resolve(db, ws, changed)
        assertTrue(result.transactions.isEmpty()); assertTrue(result.components.isEmpty()); assertTrue(result.bindings.isEmpty())
        assertEquals(stored.cashSourceBindings.single().id.toLong(), result.deletedBindings[request.sourceBindings.single().target.reference])
        assertEquals(before, detailDatabaseState(db))
    }
    @Test fun aliasesRemainIncompleteWhenNewBindingOrCDIntentHasNotBeenApplied() = workspace { db, ws, account ->
        val request = input(account); store(db, ws, request)
        val row = request.transactions.single(); val alias = request.sourceBindings.single().copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("extra"), null), identityKey = "extra")
        val changed = request.copy(transactions = listOf(row.copy(entryEdit = CashEntryBeforeRelationEditDto(null, buildJsonObject { put("note", "unapplied C") }))),
            sourceBindings = request.sourceBindings + alias)
        val before = detailDatabaseState(db); val result = resolve(db, ws, changed)
        assertEquals(1, result.transactions.size); assertEquals(1, result.bindings.size)
        assertFalse(alias.target.reference in result.bindings); assertEquals(before, detailDatabaseState(db))
        assertEquals("unapplied C", changed.transactions.single().entryEdit!!.patch.getValue("note").jsonPrimitive.content)
    }
    @Test fun duplicateWriteAliasesAndNaturalClaimsAreRejected() = workspace { db, ws, account ->
        val request = input(account); store(db, ws, request); val parent = facts(db, ws).cashTransactions.single(); val row = request.transactions.single()
        val duplicate = row.copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(parent.id), parent.rowRevision), components = emptyList())
        policy(db, ws, request.copy(transactions = listOf(row, duplicate)), "duplicate_reference")
        policy(db, ws, request.copy(sourceBindings = request.sourceBindings + request.sourceBindings.map { it.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("same-key"), null)) }), "duplicate_reference")
    }
    @Test fun identityResultCannotCrossTransactionsWorkspacesOrSavepointRollback() = workspace { db, ws, account ->
        val request = input(account); store(db, ws, request); val expired = resolve(db, ws, request)
        db.write { c ->
            check(c.lockWorkspace(db.sqlite, ws)); val context = CashSqlContext(c, ws, db.sqlite)
            assertFailsWith<CashProjectionFailure> { expired.requireOwner(context) }
            assertFailsWith<CashProjectionFailure> { expired.aliases(context) }
            val point = c.setSavepoint(); val current = resolveCashImportWriteSetSourceIdentities(context, request)
            current.requireOwner(context)
            assertFailsWith<CashProjectionFailure> { current.requireOwner(CashSqlContext(c, "other", db.sqlite)) }
            c.rollback(point)
            assertFailsWith<CashProjectionFailure> { current.requireOwner(context) }
            assertFailsWith<CashProjectionFailure> { current.aliases(context) }
        }
    }
    @Test fun legacyOriginAndServerHiddenFieldsArePreservedButCannotBeForgedByNewRequest() = workspace { db, ws, account ->
        val request = input(account); store(db, ws, request)
        val flags = buildJsonObject { put("note", true) }; val metadata = buildJsonObject { put("retained", "legacy") }
        db.write { c ->
            c.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.RECORD_ORIGIN, "legacy").set(CASH_TRANSACTIONS.SOURCE_FINGERPRINT, "old-fingerprint")
                .set(CASH_TRANSACTIONS.MANUAL_OVERRIDES, flags).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(ws)).execute()
            c.jooq(db.sqlite).update(CASH_TRANSACTION_COMPONENTS).set(CASH_TRANSACTION_COMPONENTS.LABEL, "retained label")
                .set(CASH_TRANSACTION_COMPONENTS.SOURCE_KEY, "retained key").set(CASH_TRANSACTION_COMPONENTS.METADATA_JSON, metadata)
                .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(ws)).execute(); c.markLedgerChanged(ws)
        }
        val state = detailDatabaseState(db); val result = resolve(db, ws, request)
        assertEquals(1, result.transactions.size); assertEquals(1, result.components.size); assertEquals(state, detailDatabaseState(db))
        val row = request.transactions.single()
        for (forged in listOf(row.copy(values = row.values.copy(sourceFingerprint = "old-fingerprint")), row.copy(values = row.values.copy(manualOverrides = flags)),
            row.copy(components = row.components.map { it.copy(label = "retained label") }), row.copy(components = row.components.map { it.copy(metadataJson = metadata) })))
            policy(db, ws, request.copy(transactions = listOf(forged)), "field_not_editable")
    }
    @Test fun matchingParentDoesNotPermitDifferentBindingValuesOrScopeClaims() = workspace { db, ws, account ->
        val request = input(account); store(db, ws, request)
        policy(db, ws, request.copy(sourceBindings = request.sourceBindings.map { it.copy(strategyVersion = "different") }))
        policy(db, ws, request.copy(sourceBindings = request.sourceBindings.map { it.copy(coverage = buildJsonObject { put("complete", true) }) }))
        val old = facts(db, ws).cashSourceBindings.single()
        val stale = request.sourceBindings.single().copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(old.id), "9"))
        policy(db, ws, request.copy(sourceBindings = listOf(stale)), "reference_changed")
    }
    @Test fun invalidOrDeepSourceJsonAndMalformedCreationReferencesStopWithoutWrites() = workspace { db, ws, account ->
        val request = input(account); store(db, ws, request); val row = request.transactions.single()
        var nested: JsonElement = empty; repeat(65) { nested = JsonArray(listOf(nested)) }
        policy(db, ws, request.copy(transactions = listOf(row.copy(values = row.values.copy(sourcePayload = JsonObject(mapOf("deep" to nested)))))), "invalid_field")
        policy(db, ws, request.copy(transactions = listOf(row.copy(target = row.target.copy(expectedRevision = "1")))), "invalid_reference")
        policy(db, ws, request.copy(sourceBindings = request.sourceBindings.map { it.copy(identityStrength = "provider") }), "invalid_source_binding")
    }

    @Test fun stableEvidenceAloneMayResolveParentButDoesNotInventANewAliasId() = workspace { db, ws, account ->
        val initial = input(account).let { it.copy(sourceBindings = it.sourceBindings.map { row -> row.copy(sourceScopeKind = "account", sourceScopeKey = "owner",
            identityStrength = "validated", stableEvidenceKey = "verified") }) }
        store(db, ws, initial)
        val request = initial.copy(sourceBindings = initial.sourceBindings.map { it.copy(identityKey = "new-alias", target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("new-alias"), null)) })
        val state = detailDatabaseState(db); val result = resolve(db, ws, request)
        assertEquals(1, result.transactions.size); assertEquals(1, result.components.size); assertTrue(result.bindings.isEmpty())
        assertEquals(state, detailDatabaseState(db))
    }
    @Test fun deletedBindingClassificationMatchesOriginalOccurrenceWriterReparenting() = workspace { db, ws, account ->
        val occurrence = SourceOccurrence("ccb_debit", null, "a".repeat(64), "fixture-v1", "csv", 1, emptyMap())
        val key = occurrence.bindingSpecs.single(); val initial = input(account)
        val request = initial.copy(sourceBindings = initial.sourceBindings.map { it.copy(sourceScopeKind = key.scopeKind, sourceScopeKey = key.scopeKey,
            identityKind = key.identityKind, identityKey = key.identityKey, identityStrength = key.identityStrength) })
        val parent = db.write { c ->
            check(c.lockWorkspace(db.sqlite, ws)); val context = CashSqlContext(c, ws, db.sqlite) { deletionTime }
            val sources = saveCashImportWriteSetSources(context, initial.copy(sourceBindings = emptyList()))
            val id = sources.transactions.values.single()
            assertTrue(CashSourceWriteRepository(context).saveOccurrence(id, occurrence)); id
        }
        val oldBinding = facts(db, ws).cashSourceBindings.single()
        assertEquals(parent, resolve(db, ws, request).transactions.values.single())
        db.write { c -> c.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.DELETED_AT, deletionTime)
            .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(ws).and(CASH_TRANSACTIONS.ID.eq(parent))).execute(); c.markLedgerChanged(ws) }
        val classified = resolve(db, ws, request)
        assertEquals(oldBinding.id.toLong(), classified.deletedBindings.values.single())
        val newParent = db.write { c ->
            check(c.lockWorkspace(db.sqlite, ws)); val context = CashSqlContext(c, ws, db.sqlite) { deletionTime }
            val sources = saveCashImportWriteSetSources(context, initial.copy(sourceBindings = emptyList()))
            val id = sources.transactions.values.single(); assertTrue(CashSourceWriteRepository(context).saveOccurrence(id, occurrence)); id
        }
        val after = facts(db, ws)
        assertNotEquals(parent, newParent); assertEquals(oldBinding.id, after.cashSourceBindings.single().id)
        assertEquals(newParent.toString(), after.cashSourceBindings.single().transactionId)
        assertNotNull(after.cashTransactions.single { it.id == parent.toString() }.deletedAt)
    }
    @Test fun anotherWorkspaceWithTheSameNaturalIdentityCannotSupplyAnAlias() = workspace { db, ws, account ->
        val other = java.util.UUID.randomUUID().toString()
        db.write { c -> deletionInsert(c, db, "workspaces", linkedMapOf("id" to other, "name" to "Other identity workspace", "created_at" to deletionTime)) }
        try {
            store(db, other, input(deletionAccount(db, other)))
            val state = detailDatabaseState(db); val result = resolve(db, ws, input(account))
            assertTrue(result.transactions.isEmpty()); assertTrue(result.bindings.isEmpty()); assertTrue(result.before.cashTransactions.isEmpty())
            assertEquals(state, detailDatabaseState(db))
        } finally { db.write { c -> c.jooq(db.sqlite).deleteFrom(WORKSPACES).where(WORKSPACES.ID.eq(other)).execute() } }
    }

    @Test fun explanationNullDirectionMatchesTheActualSourceWriterInsteadOfSymmetricEmptyDefaults() = workspace { db, ws, account ->
        val request = input(account); store(db, ws, request); val initial = facts(db, ws)
        assertEquals(empty, initial.cashTransactions.single().relationMetadata)
        val clearing = request.copy(transactions = request.transactions.map { it.copy(values = it.values.copy(relationMetadata = JsonNull)) })
        policy(db, ws, clearing)
        val parent = initial.cashTransactions.single()
        val existing = clearing.transactions.single().copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(parent.id), parent.rowRevision),
            components = clearing.transactions.single().components.map { part -> part.copy(reference = ExistingCashWriteReferenceDto(initial.cashTransactionComponents.single().id)) })
        db.write { c ->
            check(c.lockWorkspace(db.sqlite, ws)); val context = CashSqlContext(c, ws, db.sqlite)
            val changed = saveCashImportWriteSetSources(context, CashWriteSetDto(transactions = listOf(existing)))
            assertEquals(setOf(parent.id.toLong()), changed.affected)
        }
        assertEquals(JsonNull, facts(db, ws).cashTransactions.single().relationMetadata)
        val state = detailDatabaseState(db)
        assertEquals(1, resolve(db, ws, request).transactions.size)
        assertEquals(state, detailDatabaseState(db))
        val after = facts(db, ws)
        db.write { c ->
            check(c.lockWorkspace(db.sqlite, ws)); val context = CashSqlContext(c, ws, db.sqlite)
            val unchanged = saveCashImportWriteSetSources(context, CashWriteSetDto(transactions = listOf(existing.copy(
                target = existing.target.copy(expectedRevision = after.cashTransactions.single().rowRevision), values = existing.values.copy(relationMetadata = empty)))))
            assertTrue(unchanged.affected.isEmpty())
        }
        assertEquals(state, detailDatabaseState(db))
    }
    @Test fun errorsOnNewRowsKeepTheirOriginalIndexInMixedDeclarations() = workspace { db, ws, account ->
        val a = input(account, "a"); val b = input(account, "b"); store(db, ws, a); store(db, ws, b)
        val old = facts(db, ws).cashTransactions.single { it.recordId == "b" }
        val existing = b.transactions.single().copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(old.id), old.rowRevision), components = emptyList())
        val row = a.transactions.single()
        for (changed in listOf(row.copy(values = row.values.copy(sourceFingerprint = "forged")), row.copy(values = row.values.copy(amount = "invalid")))) {
            val failure = assertFailsWith<CashPolicyError> { resolve(db, ws, a.copy(transactions = listOf(existing, changed))) }
            assertTrue(failure.fields.first().startsWith("transactions[1]"), failure.fields.toString())
        }
    }

    @Test fun invalidIncomingJsonShapesArePolicyErrorsButStoredDamageKeepsItsOriginalFailure() = workspace { db, ws, account ->
        val request = input(account); store(db, ws, request); val row = request.transactions.single()
        for (value in listOf(row.values.copy(sourcePayload = JsonPrimitive("invalid")), row.values.copy(sourcePayload = array),
            row.values.copy(counterpartyAccountAttrs = empty))) policy(db, ws, request.copy(transactions = listOf(row.copy(values = value))), "invalid_field")
        db.write { c -> c.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.SOURCE_PAYLOAD, JsonPrimitive("damaged stored value"))
            .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(ws)).execute() }
        val state = detailDatabaseState(db)
        assertFailsWith<InvalidCashValue> { resolve(db, ws, request) }; assertEquals(state, detailDatabaseState(db))
    }
    @Test fun sourceJsonAtTheExistingDepthBoundaryResolvesAndOneMoreLevelIsRejected() = workspace { db, ws, account ->
        var nested: JsonElement = empty; repeat(63) { nested = JsonArray(listOf(nested)) }
        val initial = input(account); val request = initial.copy(transactions = initial.transactions.map {
            it.copy(values = it.values.copy(sourcePayload = JsonObject(mapOf("data" to nested)))) })
        store(db, ws, request); val state = detailDatabaseState(db)
        assertEquals(1, resolve(db, ws, request).transactions.size); assertEquals(state, detailDatabaseState(db))
        val tooDeep = request.copy(transactions = request.transactions.map { it.copy(values = it.values.copy(sourcePayload = JsonObject(mapOf("data" to JsonArray(listOf(nested)))))) })
        policy(db, ws, tooDeep, "invalid_field")
    }
    @Test fun largeAliasGroupPreservesMatchedIdentitiesWithoutWritingUnmatchedAliases() = workspace { db, ws, account ->
        val initial = input(account); store(db, ws, initial)
        val request = initial.copy(sourceBindings = initial.sourceBindings + (1..1000).map { index ->
            initial.sourceBindings.single().copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("alias:$index"), null), identityKey = "alias:$index") })
        val state = detailDatabaseState(db); val start = System.nanoTime()
        val result = resolve(db, ws, request)
        println("source_identity_aliases=1001 backend=$backend elapsed_ms=${(System.nanoTime() - start) / 1_000_000}")
        assertEquals(1, result.transactions.size); assertEquals(1, result.components.size); assertEquals(1, result.bindings.size)
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun incompleteDraftsResolveWithNoPartsOrOnlyUnknownFinancialValues() {
        for (count in listOf(0, 1)) workspace { db, ws, account ->
            val initial = input(account); val original = initial.transactions.single()
            val row = original.copy(values = original.values.copy(state = "draft", accountId = null, amount = null, currency = null, occurredAt = null,
                recordType = null, recordSubtype = null), components = if (count == 0) emptyList() else original.components.map { it.copy(accountId = null, amount = null, currency = null) })
            val request = initial.copy(transactions = listOf(row)); store(db, ws, request); val state = detailDatabaseState(db)
            val result = resolve(db, ws, request)
            assertEquals(1, result.transactions.size); assertEquals(count, result.components.size); assertEquals(1, result.bindings.size)
            assertEquals(state, detailDatabaseState(db))
        }
    }

    @Test fun trustedIdentitiesFeedSharedReferenceAndGraphGuardsWithoutChangingTheRequest() = workspace { db, ws, account ->
        val request = input(account, count = 2); store(db, ws, request); val before = detailDatabaseState(db)
        db.write { c ->
            check(c.lockWorkspace(db.sqlite, ws)); val context = CashSqlContext(c, ws, db.sqlite)
            val identities = resolveCashImportWriteSetSourceIdentities(context, request)
            val aliases = identities.aliases(context)
            validateCashWriteSetReferences(request, identities.before, aliases)
            validateCashWriteSetConfirmedGraph(request, identities.before, aliases)
            assertNull(request.transactions.single().target.expectedRevision)
            assertTrue(request.transactions.single().target.reference is NewCashWriteReferenceDto)
            assertFailsWith<CashProjectionFailure> { identities.aliases(CashSqlContext(c, "other", db.sqlite)) }
        }
        assertEquals(before, detailDatabaseState(db))
    }

    private fun persist(db: Database, ws: String, changes: CashWriteSetDto, lateFailure: Boolean = false) = db.write { c ->
        check(c.lockWorkspace(db.sqlite, ws)); val context = CashSqlContext(c, ws, db.sqlite) { deletionTime }
        val sources = saveCashImportWriteSetSources(context, changes)
        val bindings = saveCashImportWriteSetBindings(context, sources)
        CashPlanFinalizer(context).balances(sources.balanceChanges)
        if (lateFailure) error("after-source-bindings")
        sources to bindings
    }
    @Test fun actualSourceAndBindingSaveReusesTheExactNewRequestWithoutAnySecondWrite() = workspace { db, ws, account ->
        val request = input(account, count = 2); val first = persist(db, ws, request); val before = detailDatabaseState(db)
        val equivalent = request.copy(transactions = request.transactions.map { row -> row.copy(values = row.values.copy(amount = "-12.300"),
            components = row.components.reversed().map { it.copy(amount = it.amount + "0") }) })
        val second = persist(db, ws, equivalent)
        assertEquals(first.first.transactions, second.first.transactions); assertEquals(first.first.components, second.first.components)
        assertEquals(first.second, second.second); assertEquals(before, detailDatabaseState(db))
        assertTrue(second.first.affected.isEmpty()); assertTrue(second.first.balanceChanges.isEmpty())
        assertTrue(second.first.changes.transactions.single().target.reference is NewCashWriteReferenceDto)
        assertNull(second.first.changes.transactions.single().target.expectedRevision)
    }
    @Test fun exactSourceSaveRetainsLegacyHiddenFieldsAndFrozenAccountAvailability() = workspace { db, ws, account ->
        val request = input(account); val first = persist(db, ws, request)
        db.write { c ->
            c.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.RECORD_ORIGIN, "legacy").set(CASH_TRANSACTIONS.SOURCE_FINGERPRINT, "retained")
                .set(CASH_TRANSACTIONS.MANUAL_OVERRIDES, buildJsonObject { put("note", true) }).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(ws)).execute()
            c.jooq(db.sqlite).update(CASH_TRANSACTION_COMPONENTS).set(CASH_TRANSACTION_COMPONENTS.LABEL, "retained")
                .set(CASH_TRANSACTION_COMPONENTS.SOURCE_KEY, "key").set(CASH_TRANSACTION_COMPONENTS.METADATA_JSON, buildJsonObject { put("retained", true) })
                .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(ws)).execute()
            c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.ACTIVE, false).where(ACCOUNTS.WORKSPACE_ID.eq(ws).and(ACCOUNTS.ID.eq(account))).execute()
            c.markLedgerChanged(ws)
        }
        val before = detailDatabaseState(db); val next = persist(db, ws, request)
        assertEquals(first.first.transactions, next.first.transactions); assertEquals(first.second, next.second)
        assertEquals(before, detailDatabaseState(db))
    }
    @Test fun matchingSourceStillPersistsANewAliasAndNeverConsumesItsEntryEdit() = workspace { db, ws, account ->
        val request = input(account); val first = persist(db, ws, request)
        val prior = facts(db, ws); val binding = request.sourceBindings.single()
        val alias = binding.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("new-alias"), null), sourceScopeKey = "other")
        val edit = CashEntryBeforeRelationEditDto(null, buildJsonObject { put("note", "pending C") })
        val changed = request.copy(sourceBindings = request.sourceBindings + alias, transactions = request.transactions.map { it.copy(entryEdit = edit) })
        val next = persist(db, ws, changed); val after = facts(db, ws)
        assertEquals(first.first.transactions, next.first.transactions); assertEquals(first.second.getValue(binding.target.reference), next.second.getValue(binding.target.reference))
        assertNotEquals(next.second.getValue(binding.target.reference), next.second.getValue(alias.target.reference))
        assertEquals(prior.cashTransactions, after.cashTransactions); assertEquals(prior.cashTransactionComponents, after.cashTransactionComponents)
        assertEquals(prior.cashEntries, after.cashEntries); assertEquals(prior.cashEntryMembers, after.cashEntryMembers)
        assertEquals(2, after.cashSourceBindings.size); assertEquals(edit, next.first.changes.transactions.single().entryEdit)
        assertTrue(next.first.balanceChanges.isEmpty()); assertTrue(next.first.affected.isEmpty())
    }

    @Test fun deletedBindingSaveReusesItsIdAndCreatesANewParentWithOneBalanceEffect() = workspace { db, ws, account ->
        val request = input(account); val first = persist(db, ws, request); val original = facts(db, ws)
        db.write { c ->
            check(c.lockWorkspace(db.sqlite, ws)); val context = CashSqlContext(c, ws, db.sqlite) { deletionTime }
            val value = cashImportWriteSetStoredSourceFields(original.cashTransactions.single(), original.cashTransactionComponents)
            c.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.DELETED_AT, deletionTime)
                .set(CASH_TRANSACTIONS.ROW_REVISION, 2L).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(ws)).execute()
            c.markLedgerChanged(ws); CashPlanFinalizer(context).balances(listOf(value to null))
        }
        val second = persist(db, ws, request); val after = facts(db, ws)
        val oldParent = first.first.transactions.values.single(); val newParent = second.first.transactions.values.single()
        assertNotEquals(oldParent, newParent); assertNotEquals(first.first.components, second.first.components)
        assertEquals(first.second, second.second); assertEquals(2, after.cashTransactions.size)
        assertNotNull(after.cashTransactions.single { it.id == oldParent.toString() }.deletedAt)
        assertEquals(newParent.toString(), after.cashSourceBindings.single().transactionId)
        assertEquals("2", after.cashSourceBindings.single().revision)
        assertEquals(setOf(newParent), second.first.affected); assertEquals(1, second.first.balanceChanges.size)
        val beforeReplay = detailDatabaseState(db); persist(db, ws, request)
        assertEquals(beforeReplay, detailDatabaseState(db))
    }
    @Test fun lateBindingFailureRollsBackNewAliasAndPreservesTheReusedSource() = workspace { db, ws, account ->
        val request = input(account); persist(db, ws, request)
        val alias = request.sourceBindings.single().copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("new-alias"), null), sourceScopeKey = "new-scope")
        val changed = request.copy(sourceBindings = request.sourceBindings + alias)
        val before = detailDatabaseState(db)
        assertEquals("after-source-bindings", assertFailsWith<IllegalStateException> { persist(db, ws, changed, lateFailure = true) }.message)
        assertEquals(before, detailDatabaseState(db))
        persist(db, ws, changed)
        assertEquals(2, facts(db, ws).cashSourceBindings.size)
    }
    @Test fun differentSourceUnderTheSameIdentityIsRejectedBeforeAnyPersistence() = workspace { db, ws, account ->
        val request = input(account); persist(db, ws, request); val before = detailDatabaseState(db)
        val changed = request.copy(transactions = request.transactions.map { it.copy(values = it.values.copy(note = "different source")) })
        assertEquals("source_identity_conflict", assertFailsWith<CashPolicyError> { persist(db, ws, changed) }.code)
        assertEquals(before, detailDatabaseState(db))
    }

}
