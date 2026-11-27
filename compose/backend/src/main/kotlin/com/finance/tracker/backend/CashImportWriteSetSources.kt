package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.CashWriteSetSourceAliases
import com.finance.tracker.business.domain.prepareCashImportWriteSetSourceValues
import kotlinx.serialization.json.*

internal class CashImportWriteSetSourceResult(
    private val owner: CashSqlContext,
    val changes: CashWriteSetDto,
    val before: LedgerFactsDto,
    val transactions: Map<CashWriteReferenceDto, Long>,
    val components: Map<CashWriteReferenceDto, Long>,
    val balanceChanges: List<Pair<Map<String, Any?>?, Map<String, Any?>>>,
    val affected: Set<Long>,
    val identities: CashImportWriteSetSourceIdentities,
    val aliases: CashWriteSetSourceAliases,
) {
    private var rolledBack = false
    init { (owner.connection as CashSqlTransactionBoundary).cashOnSavepointRollback { rolledBack = true } }
    fun requireOwner(context: CashSqlContext) {
        identities.requireOwner(context)
        owner.requireTransactionStamp()
        context.requireTransactionStamp()
        if (rolledBack || owner.connection !== context.connection || owner.workspace != context.workspace) {
            throw CashProjectionFailure("projection.incomplete")
        }
    }
    /** Execution view only: the command has proved these complete relation groups already applied. */
    fun withoutAppliedRelations(context: CashSqlContext, applied: Set<CashWriteReferenceDto>): CashImportWriteSetSourceResult {
        requireOwner(context)
        if (applied.isEmpty()) return this
        check(changes.relations.map { it.target.reference }.containsAll(applied)) { "write_set.invalid_relation_replay" }
        val groups = changes.relationGroups.filter { group ->
            val count = group.relations.count { it in applied }
            check(count == 0 || count == group.relations.size) { "write_set.incomplete_relation_replay" }
            count == 0
        }
        return CashImportWriteSetSourceResult(owner, changes.copy(relations = changes.relations.filter { it.target.reference !in applied }, relationGroups = groups),
            before, transactions, components, balanceChanges, affected, identities, aliases)
    }
}

/** Source values and source identity only: caller owns authorization, baseline and complete effects/receipt. */
internal fun saveCashImportWriteSetSources(context: CashSqlContext, changes: CashWriteSetDto,
    verifyRunning: (() -> Unit)? = null): CashImportWriteSetSourceResult {
    context.requireTransactionStamp()
    val identities = resolveCashImportWriteSetSourceIdentities(context, changes)
    verifyRunning?.invoke()
    val before = identities.before
    val aliases = identities.aliases(context)
    val prepared = prepareCashImportWriteSetSourceValues(changes, before, aliases)
    val oldParents = before.cashTransactions.associateBy { it.id }
    val oldParts = before.cashTransactionComponents.groupBy { it.cashTransactionId }
    val transactions = linkedMapOf<CashWriteReferenceDto, Long>()
    val components = linkedMapOf<CashWriteReferenceDto, Long>()
    val balances = mutableListOf<Pair<Map<String, Any?>?, Map<String, Any?>>>()
    val affected = linkedSetOf<Long>()
    val writer = CashRecordValueWriter(context)
    for ((position, row) in prepared.transactions.withIndex()) {
        if (position % 256 == 0) verifyRunning?.invoke()
        val reference = row.target.reference
        identities.transactions[reference]?.let { id ->
            transactions[reference] = id
            for (part in row.components) components[part.reference] = identities.components.getValue(part.reference)
        }
        if (reference in transactions) continue
        val old = (reference as? ExistingCashWriteReferenceDto)?.let { oldParents.getValue(it.id) }
        val oldId = old?.id?.toLong()
        val stored = oldId?.let { id -> context.sql.selectFrom(CASH_TRANSACTIONS)
            .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(context.workspace).and(CASH_TRANSACTIONS.ID.eq(id)))
            .fetchOne() ?: throw CashPolicyError("record_changed", emptyList()) }
        if (stored != null && stored[CASH_TRANSACTIONS.ROW_REVISION]?.toString() != row.target.expectedRevision) {
            throw CashPolicyError("record_changed", emptyList())
        }
        val previous = old?.let { cashImportWriteSetStoredSourceFields(it, oldParts[it.id].orEmpty()) }
        val values = cashImportWriteSetSourceFields(row.values, row.components)
        val explanationChanged = old != null && cashImportWriteSetSourceExplanationChanged(old.relationMetadata, row.values.relationMetadata)
        val changed = old == null || !CashRecordPolicy.valuesEqual(previous, values) || explanationChanged
        val operation = mapOf(
            "values" to values, "transaction_id" to old?.id, "manual_fields" to emptyList<String>(),
            "pending_fields" to emptyMap<String, Any?>(), "source_explanation" to cashSourceValue(row.values.relationMetadata),
            "preview_components" to row.components.map { part -> mapOf("ordinal" to part.ordinal.toInt(), "component_id" to part.ordinal) },
        )
        val id = writer.saveParent(operation, values, oldId, stored, changed)
        val mapping = writer.saveComponents(id, operation, values, previous, old != null, old?.state == "posted") { removed ->
            detachCashDraftComponentReferences(context, removed)
        }
        transactions[reference] = id
        for (part in row.components) components[part.reference] = mapping.getValue(part.ordinal).toLong()
        if (changed) {
            affected += id
            balances += previous to values
            if (oldId != null) context.markCashRelationCandidatesStale(setOf(id))
        }
    }
    return CashImportWriteSetSourceResult(context, prepared, before, transactions, components, balances, affected, identities, aliases)
}

internal fun cashImportWriteSetSourceFields(value: CashTransactionWriteValuesDto, children: List<CashComponentWriteDto>): Map<String, Any?> =
    linkedMapOf<String, Any?>(
        "state" to value.state, "source_type" to value.sourceType, "record_id" to value.recordId,
        "amount" to value.amount, "currency" to value.currency, "occurred_at" to value.occurredAt,
        "account_id" to value.accountId, "cash_granularity" to value.cashGranularity,
        "counterparty" to value.counterparty, "counterparty_account" to value.counterpartyAccount,
        "counterparty_account_attrs" to cashSourceValue(value.counterpartyAccountAttrs), "note" to value.note,
        "category_id" to value.categoryId, "record_type" to value.recordType, "record_subtype" to value.recordSubtype,
        "source_payload" to cashSourceValue(value.sourcePayload),
        "components" to children.sortedBy { it.ordinal.toInt() }.map { part -> linkedMapOf<String, Any?>(
            "component_id" to (part.reference as? ExistingCashWriteReferenceDto)?.id,
            "account_id" to part.accountId, "amount" to part.amount, "currency" to part.currency, "ordinal" to part.ordinal.toInt()) },
    ).mapValues { (key, value) -> CashRecordPolicy.normalize(key, value) }

internal fun cashImportWriteSetStoredSourceFields(value: LedgerCashTransactionFactDto, parts: List<LedgerCashComponentFactDto>): Map<String, Any?> = cashImportWriteSetSourceFields(
    CashTransactionWriteValuesDto(value.accountId, value.state, value.recordOrigin, value.manualTransferId,
        value.cashGranularity, value.sourceType, value.recordId, value.sourcePayload, value.relationMetadata,
        value.sourceFingerprint, value.manualOverrides, value.occurredAt, value.amount, value.currency, value.counterparty,
        value.counterpartyAccount, value.counterpartyAccountAttrs, value.note, value.categoryId, value.recordType, value.recordSubtype),
    parts.sortedBy { it.ordinal.toInt() }.map { part -> CashComponentWriteDto(ExistingCashWriteReferenceDto(part.id),
        part.accountId, part.amount, part.currency, part.ordinal, part.label, part.sourceKey, part.metadataJson) })

/** Preserve the original directional null/empty comparison used by sourceChanged. */
internal fun cashImportWriteSetSourceExplanationChanged(before: JsonElement, after: JsonElement): Boolean =
    before != after && !CashRecordPolicy.valuesEqual(cashSourceValue(after), cashSourceValue(before) ?: emptyMap<String, Any?>())
