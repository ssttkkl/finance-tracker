package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.*
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CashImportWriteSetSourcesTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private fun inWorkspace(action: (Database, String, Long) -> Unit) = withCashWriteSetTestWorkspace(backend, action)
    private fun fresh(account: Long, state: String = "draft", count: Int = 1) = CashTransactionWriteDto(
        CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("row"), null),
        CashTransactionWriteValuesDto(if (count == 1) account.toString() else null, state, "import", null, if (count == 1) "atomic" else "aggregate", "wechat", "source-row", empty, empty, null, empty,
            "2026-11-14T00:00:00Z", "-12.30", "CNY", "Source party", "", array, "Source S", null, "consumption", "not_applicable"),
        (0 until count).map { ordinal -> CashComponentWriteDto(NewCashWriteReferenceDto("part:$ordinal"), account.toString(),
            if (count == 1) "-12.30" else if (ordinal == 0) "-5.00" else "-7.30", "CNY", ordinal.toString(), "", "", empty) },
        entryEdit = CashEntryBeforeRelationEditDto(null, buildJsonObject { put("note", "C must remain unapplied") }),
    )
    private fun existing(facts: LedgerFactsDto): CashTransactionWriteDto {
        val r = facts.cashTransactions.single()
        return CashTransactionWriteDto(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(r.id), r.rowRevision),
            CashTransactionWriteValuesDto(r.accountId, r.state, r.recordOrigin, r.manualTransferId, r.cashGranularity, r.sourceType, r.recordId,
                r.sourcePayload, r.relationMetadata, r.sourceFingerprint, r.manualOverrides, r.occurredAt, r.amount, r.currency,
                r.counterparty, r.counterpartyAccount, r.counterpartyAccountAttrs, r.note, r.categoryId, r.recordType, r.recordSubtype),
            facts.cashTransactionComponents.sortedBy { it.ordinal.toInt() }.map { p -> CashComponentWriteDto(ExistingCashWriteReferenceDto(p.id),
                p.accountId, p.amount, p.currency, p.ordinal, p.label, p.sourceKey, p.metadataJson) })
    }
    private fun facts(db: Database, workspace: String) = db.read { LedgerSnapshotRepository(it, workspace, db.sqlite).facts() }
    private fun save(db: Database, workspace: String, row: CashTransactionWriteDto): CashImportWriteSetSourceResult = db.write { c ->
        check(c.lockWorkspace(db.sqlite, workspace))
        saveCashImportWriteSetSources(CashSqlContext(c, workspace, db.sqlite) { deletionTime }, CashWriteSetDto(transactions = listOf(row)))
    }

    @Test fun actualParentAndPartsAreSavedWithServerIdsButWithoutCEntriesOrIssues() = inWorkspace { db, workspace, account ->
        val before = facts(db, workspace)
        val result = save(db, workspace, fresh(account))
        val after = facts(db, workspace); val parent = after.cashTransactions.single(); val part = after.cashTransactionComponents.single()
        assertEquals(parent.id.toLong(), result.transactions[NewCashWriteReferenceDto("row")])
        assertEquals(part.id.toLong(), result.components[NewCashWriteReferenceDto("part:0")])
        assertEquals(parent.id, part.cashTransactionId)
        assertEquals("Source S", parent.note); assertEquals(0, "-12.30".toBigDecimal().compareTo(parent.amount!!.toBigDecimal())); assertEquals("1", parent.rowRevision)
        assertNull(parent.sourceFingerprint); assertEquals(empty, parent.manualOverrides)
        assertEquals("", part.label); assertEquals("", part.sourceKey); assertEquals(empty, part.metadataJson)
        assertEquals(before.copy(cashTransactions = after.cashTransactions, cashTransactionComponents = after.cashTransactionComponents), after)
        assertEquals(setOf(parent.id.toLong()), result.affected)
        assertNull(result.balanceChanges.single().first)
    }

    @Test fun unchangedPostedSourcesDoNotAdvanceVersionOrRewriteProtectedMetadata() = inWorkspace { db, workspace, account ->
        save(db, workspace, fresh(account, "posted"))
        val source = facts(db, workspace); val before = detailDatabaseState(db)
        val result = save(db, workspace, existing(source))
        assertEquals(before, detailDatabaseState(db))
        assertTrue(result.affected.isEmpty()); assertTrue(result.balanceChanges.isEmpty())
        assertEquals(source.cashTransactions.single().id.toLong(), result.transactions.values.single())
    }

    @Test fun sourceUpdatesPreserveFlagsAndStandaloneEntryFieldsUntilExplicitC() = inWorkspace { db, workspace, account ->
        val result = save(db, workspace, fresh(account, "posted"))
        val id = result.transactions.values.single()
        db.write { c ->
            val context = CashSqlContext(c, workspace, db.sqlite)
            context.entries.applyEdit(id, mapOf("note" to "Existing entry"), null); context.entries.flush()
            context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.SOURCE_FINGERPRINT, "existing fingerprint")
                .set(CASH_TRANSACTIONS.MANUAL_OVERRIDES, buildJsonObject { put("note", true) }).where(CASH_TRANSACTIONS.ID.eq(id)).execute()
        }
        val source = facts(db, workspace)
        val input = existing(source).let { it.copy(values = it.values.copy(note = "New source S")) }
        save(db, workspace, input)
        val after = facts(db, workspace)
        assertEquals("New source S", after.cashTransactions.single().note)
        assertEquals("2", after.cashTransactions.single().rowRevision)
        assertEquals(source.cashTransactions.single().manualOverrides, after.cashTransactions.single().manualOverrides)
        assertEquals("existing fingerprint", after.cashTransactions.single().sourceFingerprint)
        assertEquals(source.cashEntries, after.cashEntries); assertEquals(source.cashEntryMembers, after.cashEntryMembers)
    }

    @Test fun emptyExplanationKeepsExistingNullAndDoesNotAdvanceRevisionOrLedger() = inWorkspace { db, workspace, account ->
        val id = save(db, workspace, fresh(account, "posted")).transactions.values.single()
        db.write { c ->
            CashSqlContext(c, workspace, db.sqlite).sql.update(CASH_TRANSACTIONS)
                .set(CASH_TRANSACTIONS.RELATION_METADATA, JsonNull).where(CASH_TRANSACTIONS.ID.eq(id)).execute()
        }
        val source = facts(db, workspace); val before = detailDatabaseState(db)
        val input = existing(source).let { it.copy(values = it.values.copy(relationMetadata = empty)) }
        val result = save(db, workspace, input)
        assertEquals(before, detailDatabaseState(db))
        assertEquals(JsonNull, facts(db, workspace).cashTransactions.single().relationMetadata)
        assertTrue(result.affected.isEmpty()); assertTrue(result.balanceChanges.isEmpty())
    }

    @Test fun postedComponentTransportOrderDoesNotChangeFactsOrBalanceEffects() = inWorkspace { db, workspace, account ->
        save(db, workspace, fresh(account, "posted", count = 2))
        val input = existing(facts(db, workspace)); val before = detailDatabaseState(db)
        val result = save(db, workspace, input.copy(components = input.components.reversed()))
        assertEquals(before, detailDatabaseState(db))
        assertTrue(result.affected.isEmpty()); assertTrue(result.balanceChanges.isEmpty())
    }

    @Test fun equivalentDraftAmountsKeepStoredRepresentationAndDoNotCreateWrites() = inWorkspace { db, workspace, account ->
        save(db, workspace, fresh(account))
        val source = facts(db, workspace); val input = existing(source); val before = detailDatabaseState(db)
        val result = save(db, workspace, input.copy(values = input.values.copy(amount = "-12.300"),
            components = input.components.map { it.copy(amount = "-12.300") }))
        assertEquals(before, detailDatabaseState(db))
        assertEquals(source.cashTransactions.single().amount, result.changes.transactions.single().values.amount)
        assertEquals(source.cashTransactionComponents.single().amount, result.changes.transactions.single().components.single().amount)
        assertTrue(result.affected.isEmpty()); assertTrue(result.balanceChanges.isEmpty())
    }

    @Test fun equivalentDraftSignedZeroKeepsStoredRepresentationAndDoesNotCreateWrites() = inWorkspace { db, workspace, account ->
        val zero = fresh(account).let { it.copy(values = it.values.copy(amount = "-0.00"), components = it.components.map { p -> p.copy(amount = "-0.00") }) }
        save(db, workspace, zero)
        val source = facts(db, workspace); val input = existing(source); val before = detailDatabaseState(db)
        val result = save(db, workspace, input.copy(values = input.values.copy(amount = "0.000"), components = input.components.map { it.copy(amount = "0.000") }))
        assertEquals(before, detailDatabaseState(db))
        assertEquals(source.cashTransactions.single().amount, result.changes.transactions.single().values.amount)
        assertEquals(source.cashTransactionComponents.single().amount, result.changes.transactions.single().components.single().amount)
        assertTrue(result.affected.isEmpty()); assertTrue(result.balanceChanges.isEmpty())
    }

    @Test fun draftReorderKeepsIdsAndEachComponentsNonfinancialValues() = inWorkspace { db, workspace, account ->
        save(db, workspace, fresh(account, count = 2))
        val originalParts = facts(db, workspace).cashTransactionComponents
        db.write { c ->
            val context = CashSqlContext(c, workspace, db.sqlite); val table = CASH_TRANSACTION_COMPONENTS
            for (part in originalParts) context.sql.update(table).set(table.LABEL, "label:${part.id}")
                .set(table.SOURCE_KEY, "key:${part.id}").set(table.METADATA_JSON, buildJsonObject { put("original", part.id) }).where(table.ID.eq(part.id.toLong())).execute()
        }
        val source = facts(db, workspace); val input = existing(source)
        val reordered = input.copy(components = input.components.reversed().mapIndexed { index, p -> p.copy(ordinal = index.toString()) })
        save(db, workspace, reordered)
        val after = facts(db, workspace).cashTransactionComponents
        assertEquals(input.components.map { (it.reference as ExistingCashWriteReferenceDto).id }.reversed(), after.sortedBy { it.ordinal.toInt() }.map { it.id })
        for (p in after) {
            assertEquals("label:${p.id}", p.label); assertEquals("key:${p.id}", p.sourceKey)
            assertEquals(buildJsonObject { put("original", p.id) }, p.metadataJson)
        }
    }

    @Test fun invalidFinalFlagsAndStaleRevisionsRejectBeforeWritingAnyBusinessFacts() = inWorkspace { db, workspace, account ->
        save(db, workspace, fresh(account))
        val source = facts(db, workspace); val input = existing(source); val before = detailDatabaseState(db)
        assertFailsWith<CashPolicyError> { save(db, workspace, input.copy(values = input.values.copy(manualOverrides = buildJsonObject { put("amount", true) }))) }
        assertEquals(before, detailDatabaseState(db))
        assertFailsWith<com.finance.tracker.business.domain.CashWriteSetReferenceFailure> { save(db, workspace,
            input.copy(target = input.target.copy(expectedRevision = "2"))) }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun newPostedAndExplicitDraftAccountsUseActualTransactionalEligibility() = inWorkspace { db, workspace, account ->
        save(db, workspace, fresh(account))
        db.write { c -> c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.ACTIVE, false).where(ACCOUNTS.ID.eq(account)).execute() }
        val input = existing(facts(db, workspace)); val before = detailDatabaseState(db)
        for (candidate in listOf(fresh(account, "posted"), input.copy(values = input.values.copy(state = "posted")), input.copy(editedFields = listOf("components")))) {
            assertEquals("account_unavailable", assertFailsWith<CashPolicyError> { save(db, workspace, candidate) }.code)
            assertEquals(before, detailDatabaseState(db))
        }
        assertTrue(save(db, workspace, input).affected.isEmpty())
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun frozenPostedAccountChangesDoNotRejectMutableSourceUpdates() = inWorkspace { db, workspace, account ->
        save(db, workspace, fresh(account, "posted"))
        db.write { c -> c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.ACTIVE, false).set(ACCOUNTS.TYPE, "security")
            .set(ACCOUNTS.CURRENCIES, array).where(ACCOUNTS.ID.eq(account)).execute() }
        val source = facts(db, workspace); val input = existing(source)
        save(db, workspace, input.copy(values = input.values.copy(note = "Updated source S")))
        val after = facts(db, workspace)
        assertEquals("Updated source S", after.cashTransactions.single().note)
        assertEquals("2", after.cashTransactions.single().rowRevision)
        assertEquals(source.accounts, after.accounts); assertEquals(source.cashTransactionComponents, after.cashTransactionComponents)
    }

    @Test fun failureAfterRealSourceWritesRollsBackFactsPartsAndLedgerVersion() = inWorkspace { db, workspace, account ->
        val before = detailDatabaseState(db)
        class Rejected : RuntimeException("after source writes")
        val failure = assertFailsWith<Rejected> { db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace))
            val context = CashSqlContext(c, workspace, db.sqlite)
            val result = saveCashImportWriteSetSources(context, CashWriteSetDto(transactions = listOf(fresh(account))))
            assertEquals(1, result.transactions.size); assertEquals(1, result.components.size)
            assertEquals("Source S", context.sql.selectFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).fetchSingle()[CASH_TRANSACTIONS.NOTE])
            assertEquals(1, context.sql.selectFrom(CASH_TRANSACTION_COMPONENTS).where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(workspace)).fetch().size)
            throw Rejected()
        } }
        assertEquals("after source writes", failure.message)
        assertEquals(before, detailDatabaseState(db))
    }

    private fun mappingInput(db: Database, workspace: String, account: Long, state: String = "posted"): CashWriteSetDto {
        val input = fresh(account, state).let { it.copy(values = it.values.copy(sourceType = "ccb_debit"), entryEdit = null) }
        save(db, workspace, input)
        val row = existing(facts(db, workspace))
        val ref = sourceDigest("funding-group-v1", "ccb_debit", "file_account", "6222", "6222")
        return CashWriteSetDto(transactions = listOf(row),
            statementAccountMappings = listOf(CashStatementMappingWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("mapping"), null),
                "ccb_debit", "funding_group_v1", ref, account.toString())))
    }
    private fun saveMappings(db: Database, workspace: String, changes: CashWriteSetDto, actor: String = "mapping actor"): Map<CashWriteReferenceDto, Long> = db.write { c ->
        check(c.lockWorkspace(db.sqlite, workspace))
        val context = CashSqlContext(c, workspace, db.sqlite) { deletionTime }
        saveCashImportWriteSetMappings(context, saveCashImportWriteSetSources(context, changes), actor)
    }
    private fun sequence(db: Database, workspace: String) = db.read { c ->
        c.prepareStatement("SELECT ledger_seq FROM workspaces WHERE id=?").use { statement ->
            statement.setString(1, workspace); statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
        }
    }
    private fun anotherAccount(db: Database, workspace: String) = db.write { c -> deletionInsert(c, db, "accounts", linkedMapOf(
        "workspace_id" to workspace, "name" to "Other mapping account", "type" to "cash", "active" to true, "revision" to 1L,
        "currencies" to JsonArray(listOf(JsonPrimitive("CNY"))), "metadata_json" to empty, "created_at" to deletionTime, "updated_at" to deletionTime))!! }

    @Test fun validatedMappingsUseActualIdsAndSameValuePreservesAllConfirmationMetadata() = inWorkspace { db, workspace, account ->
        val input = mappingInput(db, workspace, account); val before = facts(db, workspace); val version = sequence(db, workspace)
        val result = saveMappings(db, workspace, input); val after = facts(db, workspace)
        val saved = after.statementAccountMappings.single()
        assertEquals(saved.id.toLong(), result.getValue(NewCashWriteReferenceDto("mapping")))
        assertEquals(account.toString(), saved.accountId); assertEquals("1", saved.revision); assertEquals("mapping actor", saved.confirmedBy)
        assertEquals(before.cashTransactions, after.cashTransactions); assertEquals(before.cashTransactionComponents, after.cashTransactionComponents)
        assertEquals(version + 1, sequence(db, workspace))
        val same = input.copy(statementAccountMappings = input.statementAccountMappings.map {
            it.copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(saved.id), saved.revision)) })
        val state = detailDatabaseState(db)
        assertEquals(saved.id.toLong(), saveMappings(db, workspace, same, "later actor").values.single())
        assertEquals(state, detailDatabaseState(db))
        assertEquals(saved.id.toLong(), saveMappings(db, workspace, input, "new reference actor").getValue(NewCashWriteReferenceDto("mapping")))
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun noMappingDeltaDoesNotPublishADefaultOrAdvanceLedger() = inWorkspace { db, workspace, account ->
        val input = mappingInput(db, workspace, account).copy(statementAccountMappings = emptyList())
        val state = detailDatabaseState(db)
        assertTrue(saveMappings(db, workspace, input).isEmpty())
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun mappingRejectionAfterSourceUpdateRollsBackTheEntireTransaction() = inWorkspace { db, workspace, account ->
        val input = mappingInput(db, workspace, account)
        val changed = input.copy(transactions = input.transactions.map { it.copy(values = it.values.copy(note = "changed before rejected mapping")) },
            statementAccountMappings = input.statementAccountMappings.map { it.copy(sourceType = "wechat") })
        val state = detailDatabaseState(db)
        assertFailsWith<com.finance.tracker.business.domain.CashPolicyError> { saveMappings(db, workspace, changed) }
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun failureAfterActualMappingInsertRollsBackSourceMappingAndLedgerTogether() = inWorkspace { db, workspace, account ->
        val input = mappingInput(db, workspace, account)
        val changed = input.copy(transactions = input.transactions.map { it.copy(values = it.values.copy(note = "changed before late failure")) })
        val state = detailDatabaseState(db)
        assertFailsWith<IllegalStateException> { db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace))
            val context = CashSqlContext(c, workspace, db.sqlite) { deletionTime }
            assertEquals(1, saveCashImportWriteSetMappings(context, saveCashImportWriteSetSources(context, changed), "actor").size)
            assertEquals(1, context.sql.selectFrom(STATEMENT_ACCOUNT_MAPPINGS).where(STATEMENT_ACCOUNT_MAPPINGS.WORKSPACE_ID.eq(workspace)).fetch().size)
            error("after actual mapping insert")
        } }
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun mappingRevisionOverflowRollsBackEarlierRealSourceWrites() = inWorkspace { db, workspace, account ->
        val input = mappingInput(db, workspace, account)
        saveMappings(db, workspace, input)
        val other = anotherAccount(db, workspace)
        db.write { c ->
            val context = CashSqlContext(c, workspace, db.sqlite)
            context.sql.update(STATEMENT_ACCOUNT_MAPPINGS).set(STATEMENT_ACCOUNT_MAPPINGS.ACCOUNT_ID, other)
                .set(STATEMENT_ACCOUNT_MAPPINGS.REVISION, Long.MAX_VALUE).where(STATEMENT_ACCOUNT_MAPPINGS.WORKSPACE_ID.eq(workspace)).execute()
        }
        val saved = facts(db, workspace).statementAccountMappings.single()
        val changed = input.copy(transactions = input.transactions.map { it.copy(values = it.values.copy(note = "changed before overflow")) },
            statementAccountMappings = input.statementAccountMappings.map { it.copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(saved.id), saved.revision)) })
        val state = detailDatabaseState(db)
        assertFailsWith<ArithmeticException> { saveMappings(db, workspace, changed) }
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun sourceResultsCannotAuthorizeMappingWritesInLaterTransactions() = inWorkspace { db, workspace, account ->
        val input = mappingInput(db, workspace, account)
        val expired = db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace))
            saveCashImportWriteSetSources(CashSqlContext(c, workspace, db.sqlite), input)
        }
        val state = detailDatabaseState(db)
        assertFailsWith<CashProjectionFailure> { db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace))
            saveCashImportWriteSetMappings(CashSqlContext(c, workspace, db.sqlite), expired, "later actor")
        } }
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun sourceResultsFromRolledBackSavepointsCannotPublishMappings() = inWorkspace { db, workspace, account ->
        val input = mappingInput(db, workspace, account); val state = detailDatabaseState(db)
        db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace))
            val context = CashSqlContext(c, workspace, db.sqlite)
            val savepoint = c.setSavepoint()
            val changed = input.copy(transactions = input.transactions.map { it.copy(values = it.values.copy(note = "rolled back source")) })
            val result = saveCashImportWriteSetSources(context, changed)
            c.rollback(savepoint)
            assertFailsWith<CashProjectionFailure> { saveCashImportWriteSetMappings(context, result, "actor") }
        }
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun finalizedDraftAndMappingAdvanceOnlyOneLedgerVersion() = inWorkspace { db, workspace, account ->
        val input = mappingInput(db, workspace, account, "draft")
        val finalized = input.copy(transactions = input.transactions.map { it.copy(values = it.values.copy(state = "posted")) })
        val version = sequence(db, workspace)
        assertEquals(1, saveMappings(db, workspace, finalized).size)
        assertEquals("posted", facts(db, workspace).cashTransactions.single().state)
        assertEquals("2", facts(db, workspace).cashTransactions.single().rowRevision)
        assertEquals(version + 1, sequence(db, workspace))
    }

    @Test fun changedMappingAdvancesRevisionAndPreservesItsCreationIdentity() = inWorkspace { db, workspace, account ->
        val input = mappingInput(db, workspace, account); saveMappings(db, workspace, input)
        val other = anotherAccount(db, workspace)
        db.write { c -> CashSqlContext(c, workspace, db.sqlite).sql.update(STATEMENT_ACCOUNT_MAPPINGS)
            .set(STATEMENT_ACCOUNT_MAPPINGS.ACCOUNT_ID, other).where(STATEMENT_ACCOUNT_MAPPINGS.WORKSPACE_ID.eq(workspace)).execute() }
        val old = facts(db, workspace).statementAccountMappings.single(); val version = sequence(db, workspace)
        val changed = input.copy(statementAccountMappings = input.statementAccountMappings.map {
            it.copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(old.id), old.revision)) })
        assertEquals(old.id.toLong(), saveMappings(db, workspace, changed, "new actor").values.single())
        val saved = facts(db, workspace).statementAccountMappings.single()
        assertEquals(old.id, saved.id); assertEquals(old.createdAt, saved.createdAt); assertEquals("new actor", saved.confirmedBy)
        assertEquals("2", saved.revision); assertEquals(account.toString(), saved.accountId)
        assertEquals(version + 1, sequence(db, workspace))
    }

    @Test fun mappingStageCannotReuseSourceResultsForAnotherWorkspace() = inWorkspace { db, workspace, account ->
        val input = mappingInput(db, workspace, account); val state = detailDatabaseState(db)
        db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace))
            val context = CashSqlContext(c, workspace, db.sqlite)
            val result = saveCashImportWriteSetSources(context, input)
            assertFailsWith<CashProjectionFailure> {
                saveCashImportWriteSetMappings(CashSqlContext(c, "other-workspace", db.sqlite), result, "actor")
            }
        }
        assertEquals(state, detailDatabaseState(db))
    }
    private fun boundRow(account: Long) = fresh(account).let { it.copy(values = it.values.copy(sourceType = "ccb_debit"), entryEdit = null) }
    private fun binding(row: CashTransactionWriteDto, name: String = "binding") = CashSourceBindingWriteDto(
        CashWriteVersionedReferenceDto(NewCashWriteReferenceDto(name), null), row.target.reference, "ccb_debit", "file_local", "file-scope",
        "file_occurrence", "occurrence", "file_local", null, "fixture-v1", empty)
    private fun binding(row: LedgerSourceBindingFactDto) = CashSourceBindingWriteDto(
        CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(row.id), row.revision), ExistingCashWriteReferenceDto(row.transactionId),
        row.sourceType, row.sourceScopeKind, row.sourceScopeKey, row.identityKind, row.identityKey, row.identityStrength,
        row.stableEvidenceKey, row.strategyVersion, row.coverage)
    private fun saveBindings(db: Database, workspace: String, changes: CashWriteSetDto): Map<CashWriteReferenceDto, Long> = db.write { c ->
        check(c.lockWorkspace(db.sqlite, workspace))
        val context = CashSqlContext(c, workspace, db.sqlite) { deletionTime }
        saveCashImportWriteSetBindings(context, saveCashImportWriteSetSources(context, changes))
    }

    @Test fun sourceAndBindingUseServerIdsInOneLedgerVersionWithoutReplayingDiscovery() = inWorkspace { db, workspace, account ->
        val row = boundRow(account); val version = sequence(db, workspace)
        val result = saveBindings(db, workspace, CashWriteSetDto(transactions = listOf(row), sourceBindings = listOf(binding(row))))
        val after = facts(db, workspace); val saved = after.cashSourceBindings.single()
        assertEquals(after.cashTransactions.single().id, saved.transactionId)
        assertEquals(saved.id.toLong(), result.getValue(NewCashWriteReferenceDto("binding")))
        assertEquals("1", saved.revision); assertEquals("file_local", saved.identityStrength)
        assertEquals(version + 1, sequence(db, workspace))
        val state = detailDatabaseState(db)
        val replay = CashWriteSetDto(transactions = listOf(existing(after)), sourceBindings = listOf(binding(saved)))
        assertEquals(saved.id.toLong(), saveBindings(db, workspace, replay).values.single())
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun existingNativeOccurrenceWritesAreExactNoopsThroughTheFinalBindingStage() = inWorkspace { db, workspace, account ->
        val id = save(db, workspace, boundRow(account)).transactions.values.single()
        db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace))
            CashSourceWriteRepository(CashSqlContext(c, workspace, db.sqlite) { deletionTime }).saveOccurrence(id,
                SourceOccurrence("ccb_debit", "owner", "a".repeat(64), "fixture-v1", "table", 1, emptyMap(), providerId = "order", stableEvidence = "verified"))
        }
        val source = facts(db, workspace); val state = detailDatabaseState(db)
        val result = saveBindings(db, workspace, CashWriteSetDto(transactions = listOf(existing(source)), sourceBindings = source.cashSourceBindings.map(::binding)))
        assertEquals(3, result.size)
        assertEquals(source.cashSourceBindings.map { it.id.toLong() }.toSet(), result.values.toSet())
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun stableIdentityCollisionIsRejectedBeforeSourceInsertion() = inWorkspace { db, workspace, account ->
        val first = boundRow(account)
        val firstBinding = binding(first).copy(sourceScopeKind = "account", sourceScopeKey = "owner", identityStrength = "validated", stableEvidenceKey = "verified")
        saveBindings(db, workspace, CashWriteSetDto(transactions = listOf(first), sourceBindings = listOf(firstBinding)))
        val second = first.copy(values = first.values.copy(recordId = "second"))
        val changes = CashWriteSetDto(transactions = listOf(second), sourceBindings = listOf(firstBinding.copy(identityKey = "another")))
        val state = detailDatabaseState(db)
        val failure = assertFailsWith<com.finance.tracker.business.domain.CashPolicyError> { db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace)); val context = CashSqlContext(c, workspace, db.sqlite)
            val sources = saveCashImportWriteSetSources(context, changes)
            saveCashImportWriteSetBindings(context, sources)
        } }
        assertEquals("source_identity_conflict", failure.code)
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun softDeletedOwnerReusePreservesTheBindingIdentityAndCreationTime() = inWorkspace { db, workspace, account ->
        val row = boundRow(account)
        saveBindings(db, workspace, CashWriteSetDto(transactions = listOf(row), sourceBindings = listOf(binding(row))))
        val old = facts(db, workspace).cashSourceBindings.single()
        db.write { c -> CashSqlContext(c, workspace, db.sqlite).sql.update(CASH_TRANSACTIONS)
            .set(CASH_TRANSACTIONS.DELETED_AT, deletionTime).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).execute() }
        val version = sequence(db, workspace)
        val changes = CashWriteSetDto(transactions = listOf(row), sourceBindings = listOf(binding(old).copy(transaction = row.target.reference)))
        assertEquals(old.id.toLong(), saveBindings(db, workspace, changes).values.single())
        val after = facts(db, workspace); val saved = after.cashSourceBindings.single()
        assertNotEquals(old.transactionId, saved.transactionId)
        assertEquals(old.createdAt, saved.createdAt); assertEquals("2", saved.revision)
        assertEquals(after.cashTransactions.single { it.deletedAt == null }.id, saved.transactionId)
        assertEquals(version + 1, sequence(db, workspace))
    }

    @Test fun failureAfterBindingInsertRollsBackParentsBindingsAndLedger() = inWorkspace { db, workspace, account ->
        val row = boundRow(account); val state = detailDatabaseState(db)
        assertFailsWith<IllegalStateException> { db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace)); val context = CashSqlContext(c, workspace, db.sqlite)
            assertEquals(1, saveCashImportWriteSetBindings(context,
                saveCashImportWriteSetSources(context, CashWriteSetDto(transactions = listOf(row), sourceBindings = listOf(binding(row))))).size)
            assertEquals(1, context.sql.selectFrom(CASH_SOURCE_BINDINGS).where(CASH_SOURCE_BINDINGS.WORKSPACE_ID.eq(workspace)).fetch().size)
            error("late binding failure")
        } }
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun bindingMetadataRevisionAndOverflowUseTheRealStoredRevision() = inWorkspace { db, workspace, account ->
        val row = boundRow(account)
        saveBindings(db, workspace, CashWriteSetDto(transactions = listOf(row), sourceBindings = listOf(binding(row))))
        val before = facts(db, workspace); val old = before.cashSourceBindings.single()
        val enriched = binding(old).copy(coverage = JsonObject(old.coverage.jsonObject + ("evidence" to JsonPrimitive("enriched"))))
        saveBindings(db, workspace, CashWriteSetDto(transactions = listOf(existing(before)), sourceBindings = listOf(enriched)))
        val after = facts(db, workspace); val updated = after.cashSourceBindings.single()
        assertEquals(old.id, updated.id); assertEquals(old.createdAt, updated.createdAt); assertEquals("2", updated.revision)
        db.write { c -> CashSqlContext(c, workspace, db.sqlite).sql.update(CASH_SOURCE_BINDINGS).set(CASH_SOURCE_BINDINGS.REVISION, Long.MAX_VALUE)
            .where(CASH_SOURCE_BINDINGS.WORKSPACE_ID.eq(workspace)).execute() }
        val original = facts(db, workspace); val finalRow = existing(original).let { it.copy(values = it.values.copy(note = "must roll back")) }
        val overflow = binding(original.cashSourceBindings.single()).copy(coverage = empty)
        val state = detailDatabaseState(db)
        assertFailsWith<ArithmeticException> { saveBindings(db, workspace, CashWriteSetDto(transactions = listOf(finalRow), sourceBindings = listOf(overflow))) }
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun bindingStageRejectsExpiredWrongWorkspaceAndRolledBackSourceResults() = inWorkspace { db, workspace, account ->
        val row = boundRow(account); val changes = CashWriteSetDto(transactions = listOf(row), sourceBindings = listOf(binding(row)))
        val expired = db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace))
            saveCashImportWriteSetSources(CashSqlContext(c, workspace, db.sqlite), changes)
        }
        val state = detailDatabaseState(db)
        assertFailsWith<CashProjectionFailure> { db.write { c -> saveCashImportWriteSetBindings(CashSqlContext(c, workspace, db.sqlite), expired) } }
        db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace)); val context = CashSqlContext(c, workspace, db.sqlite)
            val observed = LedgerSnapshotRepository(c, workspace, db.sqlite).facts()
            val input = CashWriteSetDto(transactions = listOf(existing(observed)), sourceBindings = listOf(binding(row).copy(transaction = ExistingCashWriteReferenceDto(observed.cashTransactions.single().id))))
            val savepoint = c.setSavepoint(); val sources = saveCashImportWriteSetSources(context, input)
            assertFailsWith<CashProjectionFailure> { saveCashImportWriteSetBindings(CashSqlContext(c, "another-workspace", db.sqlite), sources) }
            c.rollback(savepoint)
            assertFailsWith<CashProjectionFailure> { saveCashImportWriteSetBindings(context, sources) }
        }
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun replacingTheOnlyDocumentPageOrDeclarationRollsBackTheSourceEdit() = inWorkspace { db, workspace, account ->
        val row = boundRow(account)
        val coverage = buildJsonObject {
            put("document_key", "a".repeat(64)); put("page_total", 2)
            put("page_fingerprints", buildJsonObject { put("1", "b".repeat(64)) })
        }
        saveBindings(db, workspace, CashWriteSetDto(transactions = listOf(row), sourceBindings = listOf(binding(row).copy(coverage = coverage))))
        val source = facts(db, workspace); val old = source.cashSourceBindings.single()
        val edit = existing(source).let { it.copy(values = it.values.copy(note = "must roll back")) }
        for (replacement in listOf(JsonObject(coverage + ("page_fingerprints" to buildJsonObject { put("1", "c".repeat(64)) })),
            JsonObject(coverage + ("start_inclusive" to JsonPrimitive(false))))) {
            val state = detailDatabaseState(db)
            val failure = assertFailsWith<com.finance.tracker.business.domain.CashPolicyError> {
                saveBindings(db, workspace, CashWriteSetDto(transactions = listOf(edit), sourceBindings = listOf(binding(old).copy(coverage = replacement))))
            }
            assertEquals("source_document_conflict", failure.code); assertEquals(state, detailDatabaseState(db))
        }
    }

    @Test fun invalidPlatformAccountScopeRollsBackTheRealSourceInsert() = inWorkspace { db, workspace, account ->
        for (channel in listOf("alipay", "wechat")) {
            val row = boundRow(account).let { it.copy(values = it.values.copy(sourceType = channel)) }
            val state = detailDatabaseState(db)
            val invalid = binding(row).copy(sourceType = channel, sourceScopeKind = "account", sourceScopeKey = "other-account",
                identityKind = "provider_id", identityStrength = "provider")
            val failure = assertFailsWith<com.finance.tracker.business.domain.CashPolicyError> {
                saveBindings(db, workspace, CashWriteSetDto(transactions = listOf(row), sourceBindings = listOf(invalid)))
            }
            assertEquals("invalid_source_binding", failure.code); assertEquals(state, detailDatabaseState(db))
        }
    }

    @Test fun clearingCanonicalDocumentAliasesCannotEraseStoredEvidence() = inWorkspace { db, workspace, account ->
        val row = boundRow(account)
        val document = sourceDigest("source-document-v1", "ccb_debit", "fixture-v1", "a".repeat(64))
        val page = sourceDigest("source-document-page-v1", document, "1")
        val coverage = buildJsonObject {
            put("document_key", "a".repeat(64)); put("page_total", 2)
            put("page_fingerprints", buildJsonObject { put("1", "b".repeat(64)) })
        }
        val bindings = listOf(document, page).mapIndexed { index, scope -> binding(row, "doc:$index").copy(sourceScopeKey = scope, coverage = coverage) }
        saveBindings(db, workspace, CashWriteSetDto(transactions = listOf(row), sourceBindings = bindings))
        val source = facts(db, workspace)
        val edit = existing(source).let { it.copy(values = it.values.copy(note = "must roll back")) }
        for (old in source.cashSourceBindings) for (replacement in listOf(binding(old).copy(coverage = empty),
            binding(old).copy(strategyVersion = "other-parser"),
            binding(old).copy(coverage = JsonObject(coverage + ("document_key" to JsonPrimitive("d".repeat(64))))))) {
            val state = detailDatabaseState(db)
            val failure = assertFailsWith<com.finance.tracker.business.domain.CashPolicyError> {
                saveBindings(db, workspace, CashWriteSetDto(transactions = listOf(edit), sourceBindings = listOf(replacement)))
            }
            assertEquals("source_document_conflict", failure.code); assertEquals(state, detailDatabaseState(db))
        }
    }

    private fun sourceIssue(row: CashTransactionWriteDto, code: String = "posting_incomplete", name: String = "issue") = CashIssueWriteDto(
        CashWriteVersionedReferenceDto(NewCashWriteReferenceDto(name), null), row.target, "import:$name", code, null, JsonNull, JsonNull, empty, emptyList())
    private fun sourceIssue(old: LedgerCashIssueFactDto, row: CashTransactionWriteDto) = CashIssueWriteDto(
        CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(old.id), old.revision), row.target,
        old.issueKey, old.code, old.fieldPath, old.pendingSourcePayload, old.pendingFields, old.origin, emptyList())
    private fun emptyPage(fingerprint: Char = 'b') = LedgerSourceEmptyPageFactDto("ccb_debit", "fixture-v1", "a".repeat(64), 1, buildJsonObject {
        put("document_key", "a".repeat(64)); put("page_total", 2); put("complete", true); put("evidence", "original")
        put("page_fingerprints", buildJsonObject { put("1", fingerprint.toString().repeat(64)) })
    })
    private fun saveMetadata(db: Database, workspace: String, changes: CashWriteSetDto): Map<CashWriteReferenceDto, Long> = db.write { c ->
        check(c.lockWorkspace(db.sqlite, workspace)); val context = CashSqlContext(c, workspace, db.sqlite) { deletionTime }
        val sources = saveCashImportWriteSetSources(context, changes)
        saveCashImportWriteSetBindings(context, sources)
        saveCashImportWriteSetSourceMetadata(context, sources)
    }
    private fun existingOnly(source: LedgerFactsDto, id: String) = existing(source.copy(
        cashTransactions = source.cashTransactions.filter { it.id == id }, cashTransactionComponents = source.cashTransactionComponents.filter { it.cashTransactionId == id }))
    private fun anotherRow(row: CashTransactionWriteDto, name: String) = row.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto(name), null),
        values = row.values.copy(recordId = name), components = row.components.mapIndexed { index, part -> part.copy(reference = NewCashWriteReferenceDto("$name:$index")) })

    @Test fun emptyPageOnlySubmissionIsCanonicalIdempotentAndCreatesNoPlaceholderSource() = inWorkspace { db, workspace, _ ->
        val version = sequence(db, workspace); val changes = CashWriteSetDto(sourceEmptyPages = listOf(emptyPage()))
        assertTrue(saveMetadata(db, workspace, changes).isEmpty())
        val after = facts(db, workspace); val proof = after.cashSourceEmptyPages.single()
        assertTrue(after.cashTransactions.isEmpty()); assertTrue(after.cashTransactionIssues.isEmpty())
        assertEquals(JsonPrimitive(2), proof.coverage.jsonObject["page_total"])
        assertEquals(JsonPrimitive(false), proof.coverage.jsonObject["complete"]); assertEquals(JsonPrimitive(""), proof.coverage.jsonObject["evidence"])
        assertEquals(version + 1, sequence(db, workspace))
        val state = detailDatabaseState(db); saveMetadata(db, workspace, changes); assertEquals(state, detailDatabaseState(db))
    }

    @Test fun emptyPageConflictsSeeBindingsWrittenEarlierInTheSameTransaction() = inWorkspace { db, workspace, account ->
        val row = boundRow(account); val proof = emptyPage()
        val bound = binding(row).copy(sourceScopeKey = sourceDigest("source-document-v1", proof.sourceType, proof.parserVersion, proof.documentKey), coverage = proof.coverage)
        val state = detailDatabaseState(db)
        val failure = assertFailsWith<com.finance.tracker.business.domain.CashPolicyError> {
            saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(row),
                sourceBindings = listOf(bound), sourceEmptyPages = listOf(emptyPage('c')))) }
        assertEquals("source_document_conflict", failure.code); assertEquals(state, detailDatabaseState(db))
        saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(row), sourceBindings = listOf(bound), sourceEmptyPages = listOf(proof)))
        val after = facts(db, workspace)
        assertEquals(1, after.cashSourceBindings.size); assertTrue(after.cashSourceEmptyPages.isEmpty())
    }

    @Test fun sourceIssuesUseActualIdsSameValueNoopsAndRealRevisionChanges() = inWorkspace { db, workspace, account ->
        val row = boundRow(account); val version = sequence(db, workspace)
        val raw = buildJsonObject { put("", ""); put("large", JsonPrimitive(9007199254740993L)) }
        val result = saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(row), issues = listOf(sourceIssue(row).copy(pendingSourcePayload = raw))))
        val after = facts(db, workspace); val issue = after.cashTransactionIssues.single()
        assertEquals(raw, issue.pendingSourcePayload)
        assertEquals(after.cashTransactions.single().id, issue.transactionId); assertEquals(issue.id.toLong(), result.values.single())
        assertEquals(version + 1, sequence(db, workspace)); assertEquals("1", issue.revision)
        val current = existing(after); val state = detailDatabaseState(db)
        val same = CashWriteSetDto(transactions = listOf(current), issues = listOf(sourceIssue(issue, current)))
        assertEquals(issue.id.toLong(), saveMetadata(db, workspace, same).values.single()); assertEquals(state, detailDatabaseState(db))
        val sameNew = same.copy(issues = same.issues.map { it.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("same-issue"), null)) })
        assertEquals(issue.id.toLong(), saveMetadata(db, workspace, sameNew).getValue(NewCashWriteReferenceDto("same-issue")))
        assertEquals(state, detailDatabaseState(db))
        saveMetadata(db, workspace, same.copy(issues = listOf(same.issues.single().copy(fieldPath = "note"))))
        val updated = facts(db, workspace).cashTransactionIssues.single()
        assertEquals(issue.id, updated.id); assertEquals(issue.createdAt, updated.createdAt); assertEquals("2", updated.revision)
        val replacement = sourceIssue(current, name = "replacement").copy(issueKey = updated.issueKey)
        val newId = saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(current), issues = listOf(replacement),
            resolvedIssues = listOf(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(updated.id), updated.revision)))).values.single()
        assertNotEquals(updated.id.toLong(), newId); assertEquals("1", facts(db, workspace).cashTransactionIssues.single().revision)
    }

    @Test fun candidatesValidateOldDependenciesThenStoreActualSameBatchRevisionsAndIds() = inWorkspace { db, workspace, account ->
        val first = boundRow(account); val second = anotherRow(first, "second")
        saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(first, second)))
        val before = facts(db, workspace); val owner = existingOnly(before, before.cashTransactions.first { it.recordId == first.values.recordId }.id)
        val candidate = existingOnly(before, before.cashTransactions.first { it.recordId == "second" }.id)
        val changed = candidate.copy(values = candidate.values.copy(note = "same-batch source update"))
        val third = anotherRow(first, "third")
        val issue = sourceIssue(owner, "source_identity_ambiguous").copy(candidates = listOf(candidate.target, third.target))
        saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(owner, changed, third), issues = listOf(issue)))
        val after = facts(db, workspace); val parents = after.cashTransactions.associateBy { it.id }
        assertEquals(2, after.cashIssueCandidates.size)
        for (saved in after.cashIssueCandidates) assertEquals(parents.getValue(saved.candidateTransactionId).rowRevision, saved.expectedRowRevision)
        assertEquals(setOf("1", "2"), after.cashIssueCandidates.map { it.expectedRowRevision }.toSet())
        val state = detailDatabaseState(db)
        val invalid = issue.copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(after.cashTransactionIssues.single().id), "1"))
        assertFailsWith<com.finance.tracker.business.domain.CashWriteSetReferenceFailure> {
            saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(owner), issues = listOf(invalid.copy(candidates = listOf(candidate.target)))))
        }
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun emptyAmbiguityCandidatesRetainEvenAnAlreadyStaleStoredCandidate() = inWorkspace { db, workspace, account ->
        val first = boundRow(account); val second = anotherRow(first, "second")
        val issue = sourceIssue(first, "source_identity_ambiguous").copy(candidates = listOf(second.target))
        saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(first, second), issues = listOf(issue)))
        val before = facts(db, workspace); val candidate = existingOnly(before, before.cashTransactions.first { it.recordId == "second" }.id)
        saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(candidate.copy(values = candidate.values.copy(note = "now stale")))))
        val source = facts(db, workspace); val old = source.cashTransactionIssues.single(); val owner = existingOnly(source, old.transactionId)
        val state = detailDatabaseState(db)
        saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(owner), issues = listOf(sourceIssue(old, owner).copy(pendingFields = buildJsonObject { put("note", "ignore for retained ambiguity") }))))
        assertEquals(state, detailDatabaseState(db))
        assertEquals("1", facts(db, workspace).cashIssueCandidates.single().expectedRowRevision)
    }

    @Test fun observedRelationIssuesReturnTheirOriginalIdentityWithoutSourceWritesOrMutation() = inWorkspace { db, workspace, account ->
        val row = boundRow(account); saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(row)))
        val parent = facts(db, workspace).cashTransactions.single().id.toLong()
        db.write { c -> CashSourceWriteRepository(CashSqlContext(c, workspace, db.sqlite) { deletionTime }).saveIssue(parent, "relation:old", "relation_save_failed") }
        db.write { c -> c.jooq(db.sqlite).update(CASH_TRANSACTION_ISSUES).set(CASH_TRANSACTION_ISSUES.REVISION, Long.MAX_VALUE)
            .where(CASH_TRANSACTION_ISSUES.WORKSPACE_ID.eq(workspace)).execute() }
        val before = facts(db, workspace); val old = before.cashTransactionIssues.single()
        val retained = sourceIssue(old, existingOnly(before, old.transactionId))
        val state = detailDatabaseState(db)
        assertEquals(mapOf(retained.target.reference to old.id.toLong()), saveMetadata(db, workspace, CashWriteSetDto(issues = listOf(retained))))
        assertEquals(state, detailDatabaseState(db))
        assertTrue(saveMetadata(db, workspace, CashWriteSetDto()).isEmpty()); assertEquals(state, detailDatabaseState(db))
    }

    @Test fun fileImportCannotCreateOrModifyRelationProblemsAndAllSourceEffectsRollBack() = inWorkspace { db, workspace, account ->
        val row = boundRow(account); saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(row)))
        val parent = facts(db, workspace).cashTransactions.single().id.toLong()
        db.write { c -> CashSourceWriteRepository(CashSqlContext(c, workspace, db.sqlite) { deletionTime }).saveIssue(parent, "relation:old", "relation_save_failed") }
        val before = facts(db, workspace); val old = before.cashTransactionIssues.single(); val current = existingOnly(before, old.transactionId)
        val retained = sourceIssue(old, current)
        val edit = current.copy(values = current.values.copy(note = "must roll back"))
        for (bad in listOf(retained.copy(code = "other_failure"), retained.copy(pendingFields = empty),
            retained.copy(origin = buildJsonObject { put("table_key", "changed") }),
            sourceIssue(current, name = "new-relation").copy(issueKey = "relation:new"))) {
            val state = detailDatabaseState(db)
            val failure = assertFailsWith<com.finance.tracker.business.domain.CashPolicyError> {
                saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(edit), issues = listOf(bad)))
            }
            assertEquals("field_not_editable", failure.code); assertEquals(state, detailDatabaseState(db))
        }
    }

    @Test fun ordinarySourceIssuesRetireButRetainedKindsCannotBeDeleted() = inWorkspace { db, workspace, account ->
        val row = boundRow(account); saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(row), issues = listOf(sourceIssue(row))))
        saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(anotherRow(row, "legacy-candidate"))))
        val initial = facts(db, workspace); val issue = initial.cashTransactionIssues.single()
        val candidate = initial.cashTransactions.first { it.id != issue.transactionId }
        db.write { c -> CashSourceWriteRepository(CashSqlContext(c, workspace, db.sqlite)).saveIssue(issue.transactionId.toLong(), issue.issueKey, issue.code,
            candidates = mapOf(candidate.id.toLong() to candidate.rowRevision.toLong())) }
        val before = facts(db, workspace); val old = before.cashTransactionIssues.single(); val current = existingOnly(before, old.transactionId)
        assertEquals(1, before.cashIssueCandidates.size)
        saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(current), resolvedIssues = listOf(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(old.id), old.revision))))
        assertTrue(facts(db, workspace).cashTransactionIssues.isEmpty()); assertTrue(facts(db, workspace).cashIssueCandidates.isEmpty())
        for (key in listOf("import:ambiguous", "relation:old")) {
            db.write { c -> CashSourceWriteRepository(CashSqlContext(c, workspace, db.sqlite) { deletionTime }).saveIssue(old.transactionId.toLong(), key,
                if (key.startsWith("relation:")) "posting_incomplete" else "source_identity_ambiguous") }
        }
        for (issue in facts(db, workspace).cashTransactionIssues) {
            val state = detailDatabaseState(db)
            val edit = current.copy(values = current.values.copy(note = "must roll back"))
            assertFailsWith<com.finance.tracker.business.domain.CashPolicyError> { saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(edit),
                resolvedIssues = listOf(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(issue.id), issue.revision)))) }
            assertEquals(state, detailDatabaseState(db))
        }
    }

    @Test fun issueRevisionOverflowAndLateMetadataFailureRollBackAllFactsAndTheLedger() = inWorkspace { db, workspace, account ->
        val row = boundRow(account); saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(row), issues = listOf(sourceIssue(row))))
        db.write { c -> CashSqlContext(c, workspace, db.sqlite).sql.update(CASH_TRANSACTION_ISSUES).set(CASH_TRANSACTION_ISSUES.REVISION, Long.MAX_VALUE)
            .where(CASH_TRANSACTION_ISSUES.WORKSPACE_ID.eq(workspace)).execute() }
        val source = facts(db, workspace); val current = existing(source); val state = detailDatabaseState(db)
        val changed = current.copy(values = current.values.copy(note = "must roll back"))
        assertFailsWith<ArithmeticException> { saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(changed),
            issues = listOf(sourceIssue(source.cashTransactionIssues.single(), changed).copy(fieldPath = "note")))) }
        assertEquals(state, detailDatabaseState(db))
        val second = anotherRow(row, "late")
        assertFailsWith<IllegalStateException> { db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace)); val context = CashSqlContext(c, workspace, db.sqlite)
            val saved = saveCashImportWriteSetSources(context, CashWriteSetDto(transactions = listOf(second), issues = listOf(sourceIssue(second, name = "late")), sourceEmptyPages = listOf(emptyPage())))
            assertEquals(1, saveCashImportWriteSetSourceMetadata(context, saved).size)
            assertEquals(1, context.sql.selectFrom(CASH_SOURCE_EMPTY_PAGES).where(CASH_SOURCE_EMPTY_PAGES.WORKSPACE_ID.eq(workspace)).fetch().size)
            error("late metadata failure")
        } }
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun metadataCannotFinalizeWithIssuesOrConsumeAnExpiredSourceResult() = inWorkspace { db, workspace, account ->
        val row = boundRow(account).let { it.copy(values = it.values.copy(state = "posted")) }; val state = detailDatabaseState(db)
        assertFailsWith<com.finance.tracker.business.domain.CashPolicyError> { saveMetadata(db, workspace, CashWriteSetDto(transactions = listOf(row), issues = listOf(sourceIssue(row)))) }
        assertEquals(state, detailDatabaseState(db))
        val expired = db.write { c -> check(c.lockWorkspace(db.sqlite, workspace)); saveCashImportWriteSetSources(CashSqlContext(c, workspace, db.sqlite), CashWriteSetDto(transactions = listOf(row))) }
        val after = detailDatabaseState(db)
        assertFailsWith<CashProjectionFailure> { db.write { c -> saveCashImportWriteSetSourceMetadata(CashSqlContext(c, workspace, db.sqlite), expired) } }
        assertEquals(after, detailDatabaseState(db))
    }

    @Test fun emptyPageConflictsIncludeOrdinaryFileAndExistingProviderAliases() = inWorkspace { db, workspace, account ->
        val row = boundRow(account); val state = detailDatabaseState(db)
        val file = binding(row).copy(coverage = emptyPage().coverage)
        val failure = assertFailsWith<com.finance.tracker.business.domain.CashPolicyError> { saveMetadata(db, workspace,
            CashWriteSetDto(transactions = listOf(row), sourceBindings = listOf(file), sourceEmptyPages = listOf(emptyPage('c')))) }
        assertEquals("source_document_conflict", failure.code); assertEquals(state, detailDatabaseState(db))
        val provider = file.copy(sourceScopeKind = "account", sourceScopeKey = "owner", identityKind = "provider_id", identityStrength = "provider")
        saveBindings(db, workspace, CashWriteSetDto(transactions = listOf(row), sourceBindings = listOf(provider)))
        val stored = detailDatabaseState(db)
        val conflict = assertFailsWith<com.finance.tracker.business.domain.CashPolicyError> {
            saveMetadata(db, workspace, CashWriteSetDto(sourceEmptyPages = listOf(emptyPage('c')))) }
        assertEquals("source_document_conflict", conflict.code); assertEquals(stored, detailDatabaseState(db))
        saveMetadata(db, workspace, CashWriteSetDto(sourceEmptyPages = listOf(emptyPage())))
        assertEquals(1, facts(db, workspace).cashSourceEmptyPages.size)
        val saved = detailDatabaseState(db)
        saveMetadata(db, workspace, CashWriteSetDto(sourceEmptyPages = listOf(emptyPage())))
        assertEquals(saved, detailDatabaseState(db))
    }

    private fun mirrorInput(account: Long, state: String = "confirmed"): CashWriteSetDto {
        val first = boundRow(account).let { it.copy(values = it.values.copy(state = "posted")) }
        val second = anotherRow(first, "mirror").let { it.copy(values = it.values.copy(sourceType = "wechat")) }
        val edge = CashRelationWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("relation"), null), "payment_mirror",
            first.target, second.target, first.components.single().reference, second.components.single().reference,
            first.target, first.components.single().reference, "expense", "expense", state, true, "untrusted-client-group", "manual",
            empty, "12.30", "fixture-v1", "choice", emptyList())
        val aliases = listOf(binding(first), binding(second, "mirror-binding").copy(sourceType = "wechat", sourceScopeKind = "account",
            sourceScopeKey = requireNotNull(com.finance.tracker.business.domain.CashSourceScope.sourceTransactionScopeKey("wechat")),
            identityKind = "provider_id", identityStrength = "provider", identityKey = "mirror-provider"))
        return CashWriteSetDto(transactions = listOf(first, second), sourceBindings = aliases, relations = listOf(edge),
            relationGroups = listOf(CashRelationWriteGroupDto(listOf(edge.target.reference),
                if (state == "confirmed") buildJsonObject { put("note", "B") } else empty)))
    }
    private fun saveFirstRelations(db: Database, workspace: String, changes: CashWriteSetDto): Map<CashWriteReferenceDto, Long> = db.write { c ->
        check(c.lockWorkspace(db.sqlite, workspace)); val context = CashSqlContext(c, workspace, db.sqlite) { deletionTime }
        val sources = saveCashImportWriteSetSources(context, changes)
        saveCashImportWriteSetBindings(context, sources)
        applyCashImportWriteSetEntryEdits(context, sources.changes, sources.before) { ref -> sources.transactions[ref] ?: (ref as ExistingCashWriteReferenceDto).id.toLong() }
        saveCashImportWriteSetFirstRelations(context, sources, "trusted-actor")
    }

    @Test fun firstRelationStatesUseActualIdentitiesAndTrustedAuditMetadata() {
        for (state in listOf("confirmed", "draft", "ignored")) inWorkspace { db, workspace, account ->
            val input = mirrorInput(account, state); val version = sequence(db, workspace)
            val identities = saveFirstRelations(db, workspace, input)
            val after = facts(db, workspace); val relation = after.transactionRelations.single()
            val rows = after.cashTransactions.associateBy { it.recordId }
            assertEquals(relation.id.toLong(), identities.getValue(input.relations.single().target.reference))
            assertEquals(rows.getValue("source-row").id, relation.primaryTransactionId)
            assertEquals(rows.getValue("mirror").id, relation.secondaryTransactionId)
            assertEquals(relation.primaryTransactionId, relation.anchorTransactionId)
            assertEquals(state, relation.state); assertEquals("trusted-actor", relation.createdBy)
            assertEquals(if (state == "draft") "" else "trusted-actor", relation.decidedBy)
            assertEquals(if (state == "draft") null else "2026-08-12T00:00:00+00:00", relation.decidedAt)
            assertNull(relation.decisionGroupId); assertEquals("manual", relation.decisionSource)
            assertEquals(if (state == "draft") "2" else "1", relation.rowRevision)
            assertEquals(version + 1, sequence(db, workspace))
            if (state == "confirmed") assertEquals(setOf("B"), after.cashEntries.map { it.note }.toSet())
            else assertTrue(after.cashEntries.isEmpty())
        }
    }

    @Test fun draftCandidatesUseActualSameBatchRevisionsAndRejectDeclaredValidityChanges() = inWorkspace { db, workspace, account ->
        val initial = mirrorInput(account, "draft")
        saveMetadata(db, workspace, initial.copy(relations = emptyList(), relationGroups = emptyList()))
        val before = facts(db, workspace)
        val first = existingOnly(before, before.cashTransactions.first { it.recordId == "source-row" }.id)
        val second = existingOnly(before, before.cashTransactions.first { it.recordId == "mirror" }.id)
        val candidate = CashRelationCandidateWriteDto(second.target, second.components.single().reference, empty, "valid")
        val edge = initial.relations.single().copy(primaryTransaction = first.target, secondaryTransaction = null,
            primaryComponent = first.components.single().reference, secondaryComponent = null, secondaryRole = null,
            anchorTransaction = first.target, anchorComponent = first.components.single().reference, candidates = listOf(candidate))
        val changes = initial.copy(transactions = listOf(first, second.copy(values = second.values.copy(note = "changed source"))), sourceBindings = emptyList(), relations = listOf(edge))
        saveFirstRelations(db, workspace, changes)
        val after = facts(db, workspace); val actual = after.cashRelationCandidates.single()
        assertEquals("2", actual.expectedRowRevision); assertEquals("valid", actual.validity)
        val state = detailDatabaseState(db)
        val other = anotherRow(initial.transactions.first(), "other")
        val bad = edge.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("other-relation"), null),
            primaryTransaction = other.target, primaryComponent = other.components.single().reference,
            anchorTransaction = other.target, anchorComponent = other.components.single().reference,
            candidates = listOf(candidate.copy(transaction = second.target.copy(expectedRevision = "2"), component = null)))
        assertEquals("relation_candidate_changed", assertFailsWith<CashPolicyError> { saveFirstRelations(db, workspace,
            CashWriteSetDto(transactions = listOf(other), relations = listOf(bad), relationGroups = listOf(CashRelationWriteGroupDto(listOf(bad.target.reference))))) }.code)
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun invalidRelationAmountsRolesAndNonDraftCandidatesRollBackSourceAndPublicEdits() = inWorkspace { db, workspace, account ->
        val input = mirrorInput(account); val edge = input.relations.single(); val state = detailDatabaseState(db)
        for ((index, bad) in listOf(edge.copy(appliedAmount = "1.00"), edge.copy(primaryRole = "refund"),
            edge.copy(anchorComponent = edge.secondaryComponent),
            edge.copy(candidates = listOf(CashRelationCandidateWriteDto(edge.secondaryTransaction!!, edge.secondaryComponent, empty, "valid"))),
            edge.copy(decisionSource = "unknown")).withIndex()) {
            when (index) {
                0, 1 -> assertEquals(if (index == 0) "relation_amount_invalid" else "relation_role_incompatible",
                    assertFailsWith<CashRelationFailure> { saveFirstRelations(db, workspace, input.copy(relations = listOf(bad))) }.code)
                2 -> assertEquals("invalid_reference", assertFailsWith<com.finance.tracker.business.domain.CashWriteSetReferenceFailure> {
                    saveFirstRelations(db, workspace, input.copy(relations = listOf(bad))) }.code)
                else -> assertEquals("invalid_field", assertFailsWith<CashPolicyError> {
                    saveFirstRelations(db, workspace, input.copy(relations = listOf(bad))) }.code)
            }
            assertEquals(state, detailDatabaseState(db))
        }
    }

    @Test fun lateFirstRelationFailureRollsBackSourceRelationsCandidatesCommonFieldsAndVersion() = inWorkspace { db, workspace, account ->
        val input = mirrorInput(account); val state = detailDatabaseState(db)
        assertFailsWith<IllegalStateException> { db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace)); val context = CashSqlContext(c, workspace, db.sqlite) { deletionTime }
            val sources = saveCashImportWriteSetSources(context, input)
            saveCashImportWriteSetBindings(context, sources)
            assertEquals(1, saveCashImportWriteSetFirstRelations(context, sources, "trusted-actor").size)
            assertEquals(1, context.sql.selectFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace)).fetch().size)
            assertEquals(setOf("B"), sources.transactions.values.map { context.entries.current(it)!!.values["note"]!!.jsonPrimitive.content }.toSet())
            error("late relation failure")
        } }
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun firstRelationStageRejectsExpiredSourceResults() = inWorkspace { db, workspace, account ->
        val input = mirrorInput(account)
        val expired = db.write { c -> check(c.lockWorkspace(db.sqlite, workspace)); saveCashImportWriteSetSources(CashSqlContext(c, workspace, db.sqlite), input) }
        val state = detailDatabaseState(db)
        assertFailsWith<CashProjectionFailure> { db.write { c -> saveCashImportWriteSetFirstRelations(CashSqlContext(c, workspace, db.sqlite), expired, "trusted-actor") } }
        assertEquals(state, detailDatabaseState(db))
    }

    @Test fun aJointBatchUsesOneServerGroupAndAppliesEachAcceptedChoiceOnlyOnce() = inWorkspace { db, workspace, account ->
        val input = mirrorInput(account); val first = input.transactions[0]; val second = input.transactions[1]
        val third = anotherRow(first, "third"); val fourth = anotherRow(second, "fourth")
        val a = input.relations.single()
        val b = a.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("relation-b"), null),
            primaryTransaction = third.target, secondaryTransaction = fourth.target, primaryComponent = third.components.single().reference,
            secondaryComponent = fourth.components.single().reference, anchorTransaction = third.target, anchorComponent = third.components.single().reference)
        val bridge = a.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("bridge"), null), state = "draft",
            primaryTransaction = second.target, secondaryTransaction = third.target, primaryComponent = second.components.single().reference,
            secondaryComponent = third.components.single().reference, anchorTransaction = second.target, anchorComponent = second.components.single().reference)
        val bindings = input.sourceBindings + listOf(input.sourceBindings[0].copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("third-binding"), null),
            transaction = third.target.reference, identityKey = "third-occurrence"), input.sourceBindings[1].copy(
            target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("fourth-binding"), null), transaction = fourth.target.reference, identityKey = "fourth-provider"))
        val changes = input.copy(transactions = input.transactions + listOf(third, fourth), sourceBindings = bindings,
            relations = listOf(a, bridge, b), relationGroups = listOf(input.relationGroups.single(), CashRelationWriteGroupDto(listOf(bridge.target.reference)),
                CashRelationWriteGroupDto(listOf(b.target.reference), input.relationGroups.single().commonFields)))
        saveFirstRelations(db, workspace, changes)
        val after = facts(db, workspace)
        val group = after.transactionRelations.map { it.decisionGroupId }.toSet().single()
        assertNotNull(group); assertNotEquals("untrusted-client-group", group); assertEquals(UUID.fromString(group).toString(), group)
        assertEquals(4, after.cashEntries.size); assertEquals(setOf("B"), after.cashEntries.map { it.note }.toSet())
        assertEquals(setOf("2"), after.cashEntries.map { it.revision }.toSet())
    }

    @Test fun realSourcePreEditRelationAndFinalEntryStagesKeepSeparateSCAndDValues() = inWorkspace { db, workspace, account ->
        val original = mirrorInput(account)
        val source = original.transactions[0].copy(entryEdit = CashEntryBeforeRelationEditDto(null, buildJsonObject { put("note", "C") }))
        val root = original.transactions[1]
        val entry = CashEntryWriteDto(root.target, null, "D", "Source party", "", array, listOf("note"),
            listOf(source, root).map { row -> CashEntryMemberWriteDto(row.target, buildJsonObject {
                put("category_id", JsonNull); put("note", if (row === source) "C" else "Source S"); put("counterparty", "Source party")
                put("counterparty_account", ""); put("counterparty_account_attrs", array)
            }, if (row === source) listOf("note") else emptyList()) }, listOf("note"))
        val changes = original.copy(transactions = listOf(source, root), entries = listOf(entry))
        val before = detailDatabaseState(db); val version = sequence(db, workspace)
        fun execute(forged: Boolean) = db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace)); val context = CashSqlContext(c, workspace, db.sqlite) { deletionTime }
            val input = if (!forged) changes else changes.copy(entries = listOf(entry.copy(members = entry.members.map {
                it.copy(standaloneValues = JsonObject(it.standaloneValues.jsonObject + ("note" to JsonPrimitive("forged")))) })))
            val saved = saveCashImportWriteSetSources(context, input)
            com.finance.tracker.business.domain.validateCashImportWriteSetEntryTopology(saved.changes, saved.before)
            saveCashImportWriteSetBindings(context, saved); saveCashImportWriteSetSourceMetadata(context, saved)
            fun resolve(ref: CashWriteReferenceDto) = saved.transactions[ref] ?: (ref as ExistingCashWriteReferenceDto).id.toLong()
            applyCashImportWriteSetEntryEdits(context, saved.changes, saved.before, resolve = ::resolve)
            assertEquals(1, saveCashImportWriteSetFirstRelations(context, saved, "trusted-actor").size)
            val graph = CashRelationContextService(context)
            val groups = graph.validate(graph.load(saved.transactions.values.toSet())).build.projections
            finishCashImportWriteSetEntries(context, saved.changes, groups, resolve = ::resolve)
        }
        assertEquals("entry_result_invalid", assertFailsWith<CashPolicyError> { execute(true) }.code)
        assertEquals(before, detailDatabaseState(db))
        execute(false)
        val after = facts(db, workspace)
        assertEquals(setOf("Source S"), after.cashTransactions.map { it.note }.toSet())
        assertEquals("D", after.cashEntries.single().note)
        assertEquals(setOf("C", "Source S"), after.cashEntryMembers.map { it.standaloneValues.jsonObject.getValue("note").jsonPrimitive.content }.toSet())
        assertEquals(1, after.transactionRelations.size); assertEquals(version + 1, sequence(db, workspace))
    }

    @Test fun refundsIncludeTheOldConfirmedClosureAndRejectCombinedOverconsumption() = inWorkspace { db, workspace, account ->
        val initial = mirrorInput(account)
        fun refund(row: CashTransactionWriteDto, amount: String) = row.copy(values = row.values.copy(amount = amount, recordType = "refund"),
            components = row.components.map { it.copy(amount = amount) })
        val firstRefund = refund(initial.transactions[1], "8.00")
        val edge = initial.relations.single().copy(kind = "refund_offset", primaryRole = "expense", secondaryRole = "refund", appliedAmount = "8.00")
        saveFirstRelations(db, workspace, initial.copy(transactions = listOf(initial.transactions[0], firstRefund), relations = listOf(edge)))
        val source = facts(db, workspace)
        val expense = source.cashTransactions.first { it.recordId == "source-row" }
        val component = source.cashTransactionComponents.single { it.cashTransactionId == expense.id }
        val oldTarget = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(expense.id), expense.rowRevision)
        val second = refund(anotherRow(initial.transactions[1], "second-refund"), "8.00")
        val added = edge.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("second-refund-edge"), null),
            primaryTransaction = oldTarget, primaryComponent = ExistingCashWriteReferenceDto(component.id),
            secondaryTransaction = second.target, secondaryComponent = second.components.single().reference,
            anchorTransaction = second.target, anchorComponent = second.components.single().reference)
        val alias = initial.sourceBindings[1].copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("second-refund-binding"), null),
            transaction = second.target.reference, identityKey = "second-refund-provider")
        val input = CashWriteSetDto(transactions = listOf(second), relations = listOf(added), sourceBindings = listOf(alias),
            relationGroups = listOf(CashRelationWriteGroupDto(listOf(added.target.reference), initial.relationGroups.single().commonFields)))
        val state = detailDatabaseState(db)
        assertEquals("relation_refund_exceeds_remaining", assertFailsWith<CashRelationFailure> { saveFirstRelations(db, workspace, input) }.code)
        assertEquals(state, detailDatabaseState(db))
        saveFirstRelations(db, workspace, input.copy(transactions = listOf(refund(second, "4.30")), relations = listOf(added.copy(appliedAmount = "4.30"))))
        assertEquals(setOf("8", "4.3"), facts(db, workspace).transactionRelations.map { it.appliedAmount.toBigDecimal().stripTrailingZeros().toPlainString() }.toSet())
    }

    @Test fun relationAndCandidateEvidenceRejectExcessiveNestingBeforeRecursiveDecoding() = inWorkspace { db, workspace, account ->
        fun nested(count: Int): JsonObject {
            var value: JsonElement = JsonPrimitive("leaf")
            repeat(count) { value = JsonArray(listOf(value)) }
            return JsonObject(mapOf("deep" to value))
        }
        val input = mirrorInput(account); val relation = input.relations.single()
        val candidate = CashRelationCandidateWriteDto(relation.secondaryTransaction!!, relation.secondaryComponent, empty, "valid")
        val draft = relation.copy(state = "draft", secondaryTransaction = null, secondaryComponent = null, secondaryRole = null, candidates = listOf(candidate))
        val state = detailDatabaseState(db)
        for (evidence in listOf(nested(10000), JsonArray(emptyList()))) {
            val rows = listOf(relation.copy(evidence = evidence), draft.copy(candidates = listOf(candidate.copy(evidence = evidence))))
            for (row in rows) {
                val changes = input.copy(relations = listOf(row), relationGroups = listOf(CashRelationWriteGroupDto(listOf(row.target.reference),
                    if (row.state == "confirmed") input.relationGroups.single().commonFields else empty)))
                assertEquals("relation_data_invalid", assertFailsWith<CashRelationFailure> { saveFirstRelations(db, workspace, changes) }.code)
                assertEquals(state, detailDatabaseState(db))
            }
        }
        val valid = draft.copy(evidence = nested(63), candidates = listOf(candidate.copy(evidence = nested(63))))
        saveFirstRelations(db, workspace, input.copy(relations = listOf(valid), relationGroups = listOf(CashRelationWriteGroupDto(listOf(valid.target.reference)))))
        val after = facts(db, workspace)
        assertEquals(valid.evidence, after.transactionRelations.single().evidence)
        assertEquals(valid.candidates.single().evidence, after.cashRelationCandidates.single().evidence)
    }

    @Test fun completeUsableCandidatesMustSatisfyTheAnchorHardConstraintsButDraftAnchorsRemainLegal() = inWorkspace { db, workspace, account ->
        val input = mirrorInput(account, "draft"); val original = input.relations.single()
        val candidate = CashRelationCandidateWriteDto(original.secondaryTransaction!!, original.secondaryComponent, empty, "valid")
        val edge = original.copy(secondaryTransaction = null, secondaryComponent = null, secondaryRole = null, candidates = listOf(candidate))
        val state = detailDatabaseState(db)
        for ((amount, type) in listOf("-7.00" to "consumption", "12.30" to "refund")) {
            val other = input.transactions[1].let { it.copy(values = it.values.copy(amount = amount, recordType = type),
                components = it.components.map { part -> part.copy(amount = amount) }) }
            val changes = input.copy(transactions = listOf(input.transactions[0], other), relations = listOf(edge))
            assertEquals(if (type == "consumption") "relation_amount_invalid" else "relation_role_incompatible",
                assertFailsWith<CashRelationFailure> { saveFirstRelations(db, workspace, changes) }.code)
            assertEquals(state, detailDatabaseState(db))
        }
        val anchor = input.transactions[0].let { it.copy(values = it.values.copy(state = "draft")) }
        val other = input.transactions[1].let { it.copy(values = it.values.copy(amount = "-7.00"), components = it.components.map { part -> part.copy(amount = "-7.00") }) }
        saveFirstRelations(db, workspace, input.copy(transactions = listOf(anchor, other), relations = listOf(edge)))
        assertEquals("draft", facts(db, workspace).transactionRelations.single().state)
        assertEquals("valid", facts(db, workspace).cashRelationCandidates.single().validity)
    }

}
