package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.normalizeCashImportSourceCoverage
import com.finance.tracker.business.domain.normalizeCashWriteSetFinancialValues
import com.finance.tracker.business.domain.validateCashImportWriteSetBindingFields
import com.finance.tracker.business.domain.validateCashImportWriteSetSourceAliases
import kotlinx.serialization.json.*

/** Source-value identities only. This does not consume edits, relation batches or complete command effects. */
internal class CashImportWriteSetSourceIdentities(private val owner: CashSqlContext, val before: LedgerFactsDto,
    val transactions: Map<CashWriteReferenceDto, Long>, val components: Map<CashWriteReferenceDto, Long>,
    val bindings: Map<CashWriteReferenceDto, Long>, val deletedBindings: Map<CashWriteReferenceDto, Long>) {
    private var rolledBack = false
    init { (owner.connection as CashSqlTransactionBoundary).cashOnSavepointRollback { rolledBack = true } }
    fun requireOwner(context: CashSqlContext) {
        owner.requireTransactionStamp(); context.requireTransactionStamp()
        if (rolledBack || owner.connection !== context.connection || owner.workspace != context.workspace) throw CashProjectionFailure("projection.incomplete")
    }
}

/** Call under the original workspace/base lock. No heuristic recall, DTO rewriting, revision synthesis or DML. */
internal fun resolveCashImportWriteSetSourceIdentities(context: CashSqlContext, changes: CashWriteSetDto): CashImportWriteSetSourceIdentities {
    context.requireTransactionStamp()
    val before = LedgerSnapshotRepository(context.connection, context.workspace, context.sqlite).facts()
    fun fail(code: String = "source_identity_conflict", path: String = "source_bindings"): Nothing = throw CashPolicyError(code, listOf(path))
    fun depth(root: JsonElement) {
        val pending = ArrayDeque<Pair<JsonElement, Int>>(); pending.add(root to 0)
        while (pending.isNotEmpty()) {
            val (value, level) = pending.removeLast()
            if (level > 64) fail("invalid_field", "source_json")
            when (value) {
                is JsonObject -> value.values.forEach { pending.add(it to level + 1) }
                is JsonArray -> value.forEach { pending.add(it to level + 1) }
                is JsonPrimitive -> Unit
            }
        }
    }
    fun key(row: CashSourceBindingWriteDto) = SourceBindingKey(row.sourceType, row.sourceScopeKind, row.sourceScopeKey, row.identityKind, row.identityKey)
    fun oldRow(row: LedgerSourceBindingFactDto) = CashSourceBindingWriteDto(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(row.id), row.revision),
        ExistingCashWriteReferenceDto(row.transactionId), row.sourceType, row.sourceScopeKind, row.sourceScopeKey, row.identityKind, row.identityKey,
        row.identityStrength, row.stableEvidenceKey, row.strategyVersion, row.coverage)
    val oldBindings = before.cashSourceBindings.map(::oldRow)
    val storedKeys = oldBindings.associateBy(::key)
    check(storedKeys.size == oldBindings.size) { "write_set.duplicate_persisted_key" }
    val byId = before.cashSourceBindings.associateBy { it.id }
    val parents = before.cashTransactions.associateBy { it.id }
    val parts = before.cashTransactionComponents.groupBy { it.cashTransactionId }
    fun live(id: String) = parents[id]?.deletedAt == null && id in parents
    val evidence = oldBindings.filter { !it.stableEvidenceKey.isNullOrEmpty() }.groupBy { it.stableEvidenceKey }
        .mapValues { (_, aliases) -> aliases.map { (it.transaction as ExistingCashWriteReferenceDto).id }.filter(::live).toSet() }
    val oldByParent = oldBindings.groupBy { (it.transaction as ExistingCashWriteReferenceDto).id }
    val declared = mutableSetOf<CashWriteReferenceDto>()
    val incomingKeys = mutableSetOf<SourceBindingKey>()
    val incomingBindings = changes.sourceBindings.mapIndexed { i, row ->
        val path = "source_bindings[$i]"
        validateCashImportWriteSetBindingFields(row, path); depth(row.coverage)
        if (!declared.add(row.target.reference) || !incomingKeys.add(key(row))) fail("duplicate_reference", path)
        when (val ref = row.target.reference) {
            is NewCashWriteReferenceDto -> if (ref.ref.isEmpty() || row.target.expectedRevision != null) fail("invalid_reference", "$path.target")
            is ExistingCashWriteReferenceDto -> {
                val stored = byId[ref.id] ?: fail("reference_changed", "$path.target")
                if (stored.revision != row.target.expectedRevision) fail("reference_changed", "$path.target")
                if (key(oldRow(stored)) != key(row)) fail(path = path)
            }
        }
        row.copy(coverage = normalizeCashImportSourceCoverage(row.coverage) { fail("invalid_source_coverage", "$path.coverage") })
    }
    val bindingsByParent = incomingBindings.groupBy { it.transaction }
    val transactions = linkedMapOf<CashWriteReferenceDto, Long>(); val components = linkedMapOf<CashWriteReferenceDto, Long>()
    val bindings = linkedMapOf<CashWriteReferenceDto, Long>(); val deleted = linkedMapOf<CashWriteReferenceDto, Long>()
    val targets = mutableSetOf<CashWriteReferenceDto>(); val componentRefs = mutableSetOf<CashWriteReferenceDto>()
    val claimed = changes.transactions.mapNotNull { (it.target.reference as? ExistingCashWriteReferenceDto)?.id }.toMutableSet()
    val rows = changes.transactions.withIndex().filter { it.value.target.reference is NewCashWriteReferenceDto }
    // Structural declarations precede normalization so duplicate target claims cannot be hidden by an invalid second value.
    for (row in changes.transactions) {
        if (!targets.add(row.target.reference)) fail("duplicate_reference", "transactions")
        for (part in row.components) if (!componentRefs.add(part.reference)) fail("duplicate_reference", "components")
    }
    fun matches(row: CashSourceBindingWriteDto): Set<String> = buildSet {
        storedKeys[key(row)]?.let { add((it.transaction as ExistingCashWriteReferenceDto).id) }
        row.stableEvidenceKey?.let { marker -> addAll(evidence[marker].orEmpty()) }
    }.filterTo(linkedSetOf(), ::live)
    for ((index, raw) in rows) {
        val ref = raw.target.reference as NewCashWriteReferenceDto
        if (ref.ref.isEmpty() || raw.target.expectedRevision != null) fail("invalid_reference", "transactions[$index].target")
        val value = raw.values
        if (value.recordOrigin != "import" || value.manualTransferId != null) fail("fixed_field_conflict", "transactions[$index].values")
        if (value.sourceFingerprint != null || value.manualOverrides != JsonObject(emptyMap())) fail("field_not_editable", "transactions[$index].values")
        for (part in raw.components) {
            val reference = part.reference as? NewCashWriteReferenceDto ?: fail("invalid_reference", "components")
            if (reference.ref.isEmpty()) fail("invalid_reference", "components")
            if (part.label.isNotEmpty() || part.sourceKey.isNotEmpty() || part.metadataJson != JsonObject(emptyMap())) fail("field_not_editable", "components")
        }
        listOf(value.sourcePayload, value.relationMetadata, value.manualOverrides, value.counterpartyAccountAttrs).forEach(::depth)
        val owned = bindingsByParent[ref].orEmpty()
        val owners = linkedSetOf<String>()
        for (binding in owned) for (candidate in matches(binding)) {
            owners += candidate
            if (owners.size > 1) fail()
        }
        if (owners.isEmpty()) continue
        val owner = owners.single(); val old = parents.getValue(owner)
        if (old.recordOrigin !in setOf("import", "legacy") || old.sourceType != value.sourceType || old.manualTransferId != null) fail()
        if (!claimed.add(owner)) fail("duplicate_reference", "transactions")
        val row = try { normalizeCashWriteSetFinancialValues(CashWriteSetDto(transactions = listOf(raw))).transactions.single() }
        catch (failure: CashPolicyError) { throw CashPolicyError(failure.code, failure.fields.map { it.replace("transactions[0]", "transactions[$index]") }) }
        val storedParts = parts[owner].orEmpty()
        val ordinal = storedParts.associateBy { it.ordinal }
        check(ordinal.size == storedParts.size) { "write_set.duplicate_persisted_component" }
        if (row.components.map { it.ordinal }.toSet().size != row.components.size || row.components.size != ordinal.size || row.components.any { it.ordinal !in ordinal }) fail()
        fun withoutComponentIds(fields: Map<String, Any?>): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST") val children = fields.getValue("components") as List<Map<String, Any?>>
            return fields + ("components" to children.map { it - "component_id" })
        }
        val current = withoutComponentIds(cashImportWriteSetStoredSourceFields(old, storedParts))
        val proposed = try { withoutComponentIds(cashImportWriteSetSourceFields(row.values, row.components)) }
        catch (_: InvalidCashValue) { fail("invalid_field", "transactions[$index].values") }
        if (!CashRecordPolicy.valuesEqual(current, proposed) || cashImportWriteSetSourceExplanationChanged(old.relationMetadata, row.values.relationMetadata)) fail()
        transactions[ref] = owner.toLong()
        for (part in row.components) components[part.reference] = ordinal.getValue(part.ordinal).id.toLong()
    }
    fun actualParent(reference: CashWriteReferenceDto): String? = transactions[reference]?.toString() ?: (reference as? ExistingCashWriteReferenceDto)?.id
    for ((parent, aliases) in incomingBindings.groupBy { actualParent(it.transaction) }) {
        if (parent == null) continue
        val old = parents[parent] ?: fail("reference_changed")
        if (!live(parent) || old.recordOrigin !in setOf("import", "legacy") || aliases.any { old.sourceType != it.sourceType }) fail("source_target_incompatible")
        validateCashImportWriteSetSourceAliases(oldByParent[parent].orEmpty() + aliases)
    }
    for (row in incomingBindings) {
        val parent = actualParent(row.transaction)
        val liveOwners = matches(row)
        if (parent != null && liveOwners.any { it != parent }) fail("active_source_binding")
        val stored = storedKeys[key(row)] ?: continue
        val oldId = (stored.target.reference as ExistingCashWriteReferenceDto).id.toLong()
        val oldParent = (stored.transaction as ExistingCashWriteReferenceDto).id
        if (row.target.reference !is NewCashWriteReferenceDto) continue
        if (!live(oldParent)) { deleted[row.target.reference] = oldId; continue }
        if (parent != oldParent || stored.identityStrength != row.identityStrength || stored.strategyVersion != row.strategyVersion ||
            stored.stableEvidenceKey != row.stableEvidenceKey || !CashRecordPolicy.valuesEqual(cashSourceValue(stored.coverage), cashSourceValue(row.coverage))) fail()
        bindings[row.target.reference] = oldId
    }
    return CashImportWriteSetSourceIdentities(context, before, transactions, components, bindings, deleted)
}
