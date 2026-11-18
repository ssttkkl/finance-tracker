package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.domain.CashImportOperationRead
import com.finance.tracker.business.domain.CashImportOperationRules
import com.finance.tracker.business.domain.CashImportEntryVersion
import com.finance.tracker.business.domain.CashImportOperationTarget
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import kotlinx.serialization.json.*

internal typealias PreparedCashImportOperations = com.finance.tracker.business.domain.PreparedCashImportOperations

/** Native repository and scalar ports; operation decisions are shared. */
internal class CashImportOperationPlanner(private val context: CashSqlContext,
    private val validatePosting: (Map<*, *>) -> Unit = CashRecordPolicy::validateForPosting) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace
    private fun batches(values: Collection<Any?>): List<List<Long>> {
        val identifiers = values.mapNotNull { integral(it)?.takeIf { it.signum() > 0 && it < BigInteger.TWO.pow(63) }?.toLong() }
            .distinct().sorted()
        return identifiers.chunked(400)
    }
    private fun integral(value: Any?): BigInteger? = when (value) {
        is Byte, is Short, is Int, is Long, is BigInteger -> BigInteger(value.toString())
        is String -> value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toBigIntegerOrNull()
        else -> null
    }
    private fun text(value: Any?): String? = value as? String

    private fun accounts(selections: Map<String, Map<String, Any?>>, records: List<SourceRecord>,
        choices: Map<String, Map<String, Any?>>): Map<String, Map<String, Any?>> {
        val references = mutableListOf<Any?>()
        selections.values.forEach { references += it["account_id"] }
        for (record in records) references += record.fields["account_id"]
        for (choice in choices.values) {
            val patch = choice["patch"] as? Map<*, *> ?: continue
            references += patch["account_id"]
            val components = patch["components"]
            if (components is List<*>) references.addAll(components.filterIsInstance<Map<*, *>>().map { it["account_id"] })
        }
        val result = linkedMapOf<String, Map<String, Any?>>()
        for (batch in batches(references)) {
            val table = ACCOUNTS
            sql.select(table.ID, table.REVISION, table.ACTIVE, table.TYPE, table.CURRENCIES).from(table)
                .where(table.WORKSPACE_ID.eq(workspace).and(table.ID.`in`(batch))).fetch().forEach { row ->
                    result[row[table.ID]!!.toString()] = linkedMapOf(
                        "id" to row[table.ID]!!.toString(), "revision" to row[table.REVISION],
                        "active" to row[table.ACTIVE], "type" to row[table.TYPE],
                        "currencies" to cashSourceValue(row[table.CURRENCIES]))
                }
        }
        return result
    }

    private fun currentTargets(records: Map<String, SourceRecord>): Pair<Map<String, CashImportOperationTarget>, Map<String, List<Map<String, Any?>>>> {
        val targets = linkedMapOf<String, CashImportOperationTarget>()
        val issues = linkedMapOf<String, MutableList<Map<String, Any?>>>()
        for (batch in batches(records.keys)) {
            val tx = CASH_TRANSACTIONS
            sql.select(tx.ID, tx.RECORD_ID, tx.SOURCE_TYPE, tx.RELATION_METADATA).from(tx)
                .where(tx.WORKSPACE_ID.eq(workspace).and(tx.ID.`in`(batch)).and(tx.DELETED_AT.isNull)).fetch()
                .forEach { row ->
                    targets[row[tx.ID]!!.toString()] = CashImportOperationTarget(row[tx.RECORD_ID]!!, row[tx.SOURCE_TYPE]!!,
                        cashSourceValue(row[tx.RELATION_METADATA]) as? Map<String, Any?>)
                }
            val table = CASH_TRANSACTION_ISSUES
            sql.select(table.ID, table.TRANSACTION_ID, table.REVISION, table.CODE, table.FIELD_PATH, table.ISSUE_KEY)
                .from(table).where(table.WORKSPACE_ID.eq(workspace).and(table.TRANSACTION_ID.`in`(batch))).fetch()
                .forEach { row ->
                    issues.getOrPut(row[table.TRANSACTION_ID]!!.toString()) { mutableListOf() } += linkedMapOf(
                        "issue_id" to row[table.ID]!!.toString(), "revision" to row[table.REVISION],
                        "code" to row[table.CODE], "field_path" to row[table.FIELD_PATH], "issue_key" to row[table.ISSUE_KEY])
                }
        }
        return targets to issues
    }

    private fun categories(choices: Map<String, Map<String, Any?>>): Set<String> {
        val identifiers = choices.values.mapNotNull { (it["patch"] as? Map<*, *>)?.get("category_id") as? String }
        val result = mutableSetOf<String>()
        for (batch in identifiers.sorted().chunked(400)) {
            val table = CASH_CATEGORIES
            result += sql.select(table.ID).from(table).where(table.WORKSPACE_ID.eq(workspace)
                .and(table.ID.`in`(batch))).fetch(table.ID).filterNotNull()
        }
        return result
    }

    private val rules by lazy {
        CashImportOperationRules(
            reads = object : CashImportOperationRead {
                override fun source(files: List<ParsedImportFile>) = CashImportSourcePlanner(context).prepare(files)
                override fun accounts(selections: Map<String, Map<String, Any?>>, records: List<SourceRecord>,
                    choices: Map<String, Map<String, Any?>>) = this@CashImportOperationPlanner.accounts(selections, records, choices)
                override fun entries(transactionIds: Collection<String>): Pair<Map<String, CashImportEntryVersion>, Map<String, List<Any?>>> {
                    val repository = CashEntryRepository(context.connection, workspace, context.sqlite)
                    val (members, rows) = repository.forTransactions(transactionIds.mapNotNull { it.toLongOrNull() })
                    val versions = members.mapKeys { it.key.toString() }.mapValues { (_, member) ->
                        rows.getValue(member.entryId).let { CashImportEntryVersion(it.id, it.revision) }
                    }
                    val snapshots = repository.snapshot(members.keys).associate { snapshot ->
                        snapshot.transactionId.toString() to listOf<Any?>(listOf(snapshot.transactionId, snapshot.entryId, snapshot.revision, snapshot.digest))
                    }
                    return versions to snapshots
                }
                override fun targets(records: Map<String, SourceRecord>) = this@CashImportOperationPlanner.currentTargets(records)
                override fun categories(choices: Map<String, Map<String, Any?>>) = this@CashImportOperationPlanner.categories(choices)
            },
            recordValues = CashRecordPolicy,
            previewInput = { accounts, rows -> CashImportPlanViews.previewInput(accounts, rows, emptyList<Any?>(), null) },
            scanFunding = CashImportFunding::scan, resolveFunding = CashImportFunding::resolve,
            postingValidator = { PostingValidationCache(validatePosting)::invoke }, wireValue = ::wire,
            digestValues = { values -> sourceDigest(*values.toTypedArray()) },
            allocateRecordId = CashSourceResolver::allocateSourceRecordId,
            issueValue = { code, field, after -> cashImportIssue(code, fieldPath = field, afterImportAction = after) },
        )
    }
    fun prepare(files: List<ParsedImportFile>, accountSelections: List<Any?> = emptyList(),
        rowChoices: List<Any?> = emptyList(), recordIds: Map<String, String> = emptyMap(),
        sourcePlan: PreparedCashSourcePlan? = null, wireValues: Boolean = true): PreparedCashImportOperations =
        rules.prepare(files, accountSelections, rowChoices, recordIds, sourcePlan, wireValues)

    private fun wire(value: Any?): Any? = when (value) {
        null -> null
        is CashAmount -> BigDecimal(value.toString()).toPlainString()
        is Instant -> ParsedImportCodec.timeText(value)
        is Map<*, *> -> value.entries.associate { it.key.toString() to wire(it.value) }
        is List<*> -> value.map(::wire)
        else -> value
    }
}
