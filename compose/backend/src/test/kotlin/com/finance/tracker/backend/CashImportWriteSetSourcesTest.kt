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
}
