package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.*
import java.net.URI
import java.nio.file.Files
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
    private fun inWorkspace(action: (Database, String, Long) -> Unit) {
        val directory = if (backend == "sqlite") Files.createTempDirectory("ft-write-set-sources-") else null
        val db = if (directory != null) Database("jdbc:sqlite:${directory.resolve("source_test.db")}").also { it.bootstrapSchema() }
        else {
            val url = System.getenv("FT_TEST_POSTGRES_URL")
            org.junit.Assume.assumeTrue("Explicit PostgreSQL test database required", url != null)
            require(URI(url!!.replaceFirst("postgresql+psycopg://", "postgresql://")).path.endsWith("_test"))
            databaseConnectionConfig(mapOf("FT_DATABASE_URL" to url)).let { Database(it.jdbcUrl, it.user, it.password) }
        }
        val workspace = UUID.randomUUID().toString()
        try {
            db.validateSchema()
            db.write { c -> deletionInsert(c, db, "workspaces", linkedMapOf("id" to workspace, "name" to "Write set sources", "created_at" to deletionTime)) }
            action(db, workspace, deletionAccount(db, workspace))
        } finally {
            db.write { c -> c.prepareStatement("DELETE FROM workspaces WHERE id=?").use { it.setString(1, workspace); it.executeUpdate() } }
            directory?.let { Files.walk(it).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
    }
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

    @Test fun stableIdentityCollisionAfterRealSourceInsertRollsBackTheWholeCommand() = inWorkspace { db, workspace, account ->
        val first = boundRow(account)
        val firstBinding = binding(first).copy(sourceScopeKind = "account", sourceScopeKey = "owner", identityStrength = "validated", stableEvidenceKey = "verified")
        saveBindings(db, workspace, CashWriteSetDto(transactions = listOf(first), sourceBindings = listOf(firstBinding)))
        val second = first.copy(values = first.values.copy(recordId = "second"))
        val changes = CashWriteSetDto(transactions = listOf(second), sourceBindings = listOf(firstBinding.copy(identityKey = "another")))
        val state = detailDatabaseState(db)
        val failure = assertFailsWith<com.finance.tracker.business.domain.CashPolicyError> { db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace)); val context = CashSqlContext(c, workspace, db.sqlite)
            val sources = saveCashImportWriteSetSources(context, changes)
            assertEquals(2, context.sql.selectFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).fetch().size)
            saveCashImportWriteSetBindings(context, sources)
        } }
        assertEquals("active_source_binding", failure.code)
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

}
