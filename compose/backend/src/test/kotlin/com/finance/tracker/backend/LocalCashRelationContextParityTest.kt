package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.TRANSACTION_RELATIONS
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.CashRelationReadContext
import com.finance.tracker.business.domain.cashRelationReadContextToken
import com.finance.tracker.business.domain.formatPythonCashTimestamp
import com.finance.tracker.business.domain.readLocalCashRelationContext
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class LocalCashRelationContextParityTest {
    private fun database(): Database {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_PREPARATION_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun time(value: Any?): String? = when (value) {
        null -> null
        is java.time.Instant -> ParsedImportCodec.timeText(value)
        is kotlin.time.Instant -> formatPythonCashTimestamp(value)
        else -> error("unexpected time")
    }
    private fun facts(context: CashRelationReadContext<*, *>) = context.facts.map { f -> listOf(
        f.id.toString(), f.accountId, time(f.occurredAt), f.amount?.toString(), f.currency, f.counterparty, f.categoryId,
        f.note, f.sourceType, f.recordId, f.fundingRelationId, f.cashGranularity, f.state, time(f.createdAt), f.needsAttention,
        f.recordType, f.recordSubtype, f.stableSourceKey, f.recordOrigin) }

    @Test fun completeSnapshotContextsMatchTheOriginalSqlReaderWithoutWriting() {
        val db = database()
        val before = detailDatabaseState(db)
        db.read { connection ->
            val context = CashSqlContext(connection, "operation-workspace", db.sqlite)
            val snapshot = LedgerSnapshotDto("operation-workspace", "0", CASH_RULES_VERSION,
                LedgerSnapshotRepository(connection, "operation-workspace", db.sqlite).facts())
            val reader = CashRelationReadRepository(context)
            val selections = listOf(emptySet(), setOf(7501L), setOf(7502L), setOf(7505L), setOf(7506L),
                setOf(7507L), snapshot.facts.cashTransactions.map { it.id.toLong() }.toSet())
            for (ids in selections) {
                val expected = reader.readContext(ids)
                val actual = readLocalCashRelationContext(snapshot, ids)
                assertEquals<Any>(expected.copy(facts = emptyList()), actual.copy(facts = emptyList()), ids.toString())
                assertEquals(facts(expected), facts(actual), ids.toString())
                assertEquals(expected.token(), cashRelationReadContextToken(actual), ids.toString())
            }
            for ((ids, groups) in listOf(setOf(0L) to emptyList(), setOf(999999L) to emptyList(),
                setOf(7501L) to listOf("missing"), setOf(7501L) to listOf("x".repeat(129)),
                setOf(7501L) to listOf("\ud800"))) {
                val expected = assertFailsWith<CashRelationFailure> { reader.readContext(ids, groups) }.code
                assertEquals(expected, assertFailsWith<CashRelationFailure> { readLocalCashRelationContext(snapshot, ids, groups) }.code)
            }
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun explicitHistoricalGroupsMatchInsideRolledBackSyntheticState() {
        val db = database()
        val before = detailDatabaseState(db)
        db.read { connection ->
            val context = CashSqlContext(connection, "operation-workspace", db.sqlite)
            val table = TRANSACTION_RELATIONS
            val original = LedgerSnapshotRepository(connection, "operation-workspace", db.sqlite).facts()
            val ids = original.transactionRelations.map { it.id.toLong() }.sorted()
            assertTrue(ids.size >= 2)
            assertEquals(2, context.sql.update(table).set(table.DECISION_GROUP_ID, "local-context-read")
                .where(table.WORKSPACE_ID.eq(context.workspace)).and(table.ID.`in`(ids.take(2))).execute())
            assertEquals(1, context.sql.update(table).set(table.ACTIVE, false)
                .where(table.WORKSPACE_ID.eq(context.workspace)).and(table.ID.eq(ids[1])).execute())
            fun snapshot() = LedgerSnapshotDto(context.workspace, "0", CASH_RULES_VERSION,
                LedgerSnapshotRepository(connection, context.workspace, db.sqlite).facts())
            val native = CashRelationReadRepository(context)
            val expectedGroup = native.readContext(emptySet(), listOf("local-context-read"))
            val localGroup = readLocalCashRelationContext(snapshot(), emptySet(), listOf("local-context-read"))
            assertEquals(2, localGroup.jointMembers.size)
            assertTrue(localGroup.jointMembers.any { it.active == false })
            assertEquals<Any>(expectedGroup.copy(facts = emptyList()), localGroup.copy(facts = emptyList()))
            assertEquals(facts(expectedGroup), facts(localGroup))
            assertEquals(expectedGroup.token(), cashRelationReadContextToken(localGroup))


        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun storedComponentTextKeepsTheNativePlainDecimalBoundary() {
        val db = database()
        db.read { connection ->
            val base = LedgerSnapshotDto("operation-workspace", "0", CASH_RULES_VERSION,
                LedgerSnapshotRepository(connection, "operation-workspace", db.sqlite).facts())
            val part = base.facts.cashTransactionComponents.first()
            val id = part.cashTransactionId.toLong()
            for (raw in listOf("-0.000", "0E2147483648", "-0E2147483648", "1.230000", "1E-18", "1E+30", "bad")) {
                val snapshot = base.copy(facts = base.facts.copy(cashTransactionComponents =
                    base.facts.cashTransactionComponents.map { if (it.id == part.id) it.copy(amount = raw) else it }))
                val actual = readLocalCashRelationContext(snapshot, setOf(id)).componentVersions.single { it.id.toString() == part.id }.amount
                assertEquals(fixedCashDecimal(CashStoredDecimal(raw)), actual, raw)
            }
        }
    }
}
