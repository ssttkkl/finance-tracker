package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** Assemble a completed local file preview into the existing command DTO, without IO or discovery. */
fun buildLocalCashImportWriteSet(source: LedgerSnapshotDto, files: List<ParsedImportFile>, prepared: PreparedCashSourcePlan,
    plan: JsonObject, rowChoices: JsonArray = JsonArray(emptyList()), relationChoices: JsonArray = JsonArray(emptyList())): CashWriteSetDto =
    buildLocalCashImportEntryWrites(source, plan, buildLocalCashImportRelationWrites(source, plan,
        buildLocalCashImportSourceWrites(source, files, prepared, plan, rowChoices), relationChoices))

/** Complete the same write set with affected entry results, using private copies of the original ledger state. */
fun buildLocalCashImportEntryWrites(source: LedgerSnapshotDto, plan: JsonObject, changes: CashWriteSetDto): CashWriteSetDto =
    LocalEntryWrites(source.facts, plan, changes).build()

private class LocalEntryWrites(private val facts: LedgerFactsDto, private val plan: JsonObject, changes: CashWriteSetDto) {
    private fun version(id: String, revision: String) = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(id), revision)
    private val prepared = prepareCashImportWriteSetEntryEdits(changes.copy(entryDependencies = facts.cashEntries.map { version(it.id, it.revision) }), facts)
    private val ids = WriteGraphIds(prepared.transactions.map { it.target.reference })
    private fun id(ref: CashWriteReferenceDto) = CashImportPreviewInteger.parse(ids.text(ref))
    private fun stored(id: String) = CashImportPreviewInteger.parse(id)
    private fun flags(value: JsonElement) = value.jsonArray.map { check(it is JsonPrimitive && it.isString); it.content }
    private val rows = facts.cashTransactions.associateBy { it.id }
    private val written = prepared.transactions.associateBy { it.target.reference }
    private val references = written.keys.associateBy { when (it) { is ExistingCashWriteReferenceDto -> it.id; is NewCashWriteReferenceDto -> it.ref } }
    private fun reference(text: String): CashWriteReferenceDto = references[text] ?: ExistingCashWriteReferenceDto(rows.getValue(text).id)
    private val finalEdits = plan.getValue("entry_edits").jsonArray.map { it.jsonObject }
    private val topology = prepareCashWriteSetEntryTopology(prepared, facts, additionalParents = finalEdits.flatMap {
        it.getValue("target").jsonObject.getValue("parent_ids").jsonArray.map { parent -> reference(parent.jsonPrimitive.content) }
    }.toSet())
    private val members = facts.cashEntryMembers.associateTo(linkedMapOf()) {
        val key = stored(it.transactionId)
        key to CashEntryMember(key, it.entryId, it.standaloneValues.jsonObject, flags(it.standaloneManualFields))
    }
    private val membersByEntry = members.values.groupBy { it.entryId }.mapValuesTo(linkedMapOf()) { (_, group) ->
        group.mapTo(linkedSetOf()) { it.transactionId }
    }
    private val entries = facts.cashEntries.associateTo(linkedMapOf()) { entry ->
        entry.id to CashEntry(entry.id, stored(entry.rootTransactionId), stored(entry.identityTransactionId), entry.revision.toLong(),
            publicValues(entry.categoryId, entry.note, entry.counterparty, entry.counterpartyAccount, entry.counterpartyAccountAttrs),
            flags(entry.manualFields), entry.splitInherit)
    }
    private val sources = linkedMapOf<CashImportPreviewInteger, CashEntrySource>()
    private val edits = CashEntryEditRules(LocalCashRecordValues)
    private var nextEntry = 0
    private val coordinator = CashEntryReconciliationRules<CashImportPreviewInteger, CashImportPreviewInteger>(
        { it }, { it }, { var key: String; do { key = "local-entry:${nextEntry++}" } while (key in entries); key },
        { members[it.transactionId] = it })

    fun build(): CashWriteSetDto {
        check(plan["can_commit"] == JsonPrimitive(true)) { "local_import_plan_not_committable" }
        check(prepared.entries.isEmpty()) { "local_import_entries_already_composed" }
        for (group in topology.groups) for (ref in group.members) sources.getOrPut(id(ref)) {
            val value = written[ref]?.values
            val old = (ref as? ExistingCashWriteReferenceDto)?.id?.let(rows::getValue)
            val public = if (value != null) publicValues(value.categoryId, value.note, value.counterparty,
                value.counterpartyAccount, value.counterpartyAccountAttrs) else checkNotNull(old).let {
                publicValues(it.categoryId, it.note, it.counterparty, it.counterpartyAccount, it.counterpartyAccountAttrs)
            }
            val overrides = value?.manualOverrides ?: old!!.manualOverrides
            CashEntrySource(public, if (overrides == JsonNull) emptySet() else overrides.jsonObject.keys intersect commonCashFields)
        }
        for (row in prepared.transactions) row.entryEdit?.let { edit ->
            val identifier = id(row.target.reference)
            val existing = current(identifier)
            val entry = existing ?: ensureEntry(identifier)
            edits.patch(entry, decodeLocalCashValue(edit.patch), if (existing == null) entry.revision else edit.entry?.expectedRevision?.toLong()) {
                entryMembers(entry.id)
            }
        }
        val relations = prepared.relations.associateBy { it.target.reference }
        for (batch in prepareCashImportWriteSetCommonChoices(prepared, facts)) for (group in batch) {
            val choices = group.relations.map(relations::getValue)
            if (choices.first().state != "confirmed") continue
            val parents = choices.flatMap { listOfNotNull(it.primaryTransaction.reference, it.secondaryTransaction?.reference) }.distinct()
                .sortedWith { a, b -> pythonStringOrder(ids.text(a), ids.text(b)) }.map(::id)
            @Suppress("UNCHECKED_CAST") val patch = decodeLocalCashValue(group.commonFields) as Map<String, Any?>
            edits.chooseCommon(parents, patch, {}, ::ensureEntry)
        }
        val final = reconcile(topology.groups.mapIndexed { index, group ->
            CashEntryProjectionGroup("local-group:$index", id(group.root), group.members.map(::id), group.economicRoots.map(::id))
        })
        val byMembers = topology.groups.withIndex().associate { it.value.members to it.index }
        val edited = mutableMapOf<Int, MutableSet<String>>()
        for (edit in finalEdits) {
            val targets = edit.getValue("target").jsonObject.getValue("parent_ids").jsonArray.mapTo(linkedSetOf()) { reference(it.jsonPrimitive.content) }
            val index = byMembers.getValue(targets)
            val entry = final.getValue("local-group:$index")
            val patch = edit.getValue("patch").jsonObject
            edits.patch(entry, decodeLocalCashValue(patch), entry.revision) { entryMembers(entry.id) }
            edited.getOrPut(index) { linkedSetOf() } += patch.keys
        }
        val results = topology.groups.mapIndexed { index, group ->
            val entry = final.getValue("local-group:$index"); val values = entry.values
            CashEntryWriteDto(target(group.root), values.getValue("category_id").takeUnless { it == JsonNull }?.jsonPrimitive?.content,
                values.getValue("note").jsonPrimitive.content, values.getValue("counterparty").jsonPrimitive.content,
                values.getValue("counterparty_account").jsonPrimitive.content, values.getValue("counterparty_account_attrs"), entry.manualFields,
                group.members.map { reference -> members.getValue(id(reference)).let { member ->
                    CashEntryMemberWriteDto(target(reference), member.standaloneValues, member.standaloneManualFields)
                } }, edited[index].orEmpty().sortedWith(::pythonStringOrder))
        }
        return prepared.copy(entries = results, entryDependencies = topology.affected.map { version(it.id, it.revision) }).also {
            validateCashImportWriteSetEntryTopology(it, facts)
        }
    }

    private fun target(ref: CashWriteReferenceDto): CashWriteVersionedReferenceDto = written[ref]?.target ?: (ref as ExistingCashWriteReferenceDto).let {
        version(it.id, rows.getValue(it.id).rowRevision)
    }
    private fun current(id: CashImportPreviewInteger) = members[id]?.let { entries.getValue(it.entryId) }
    private fun entryMembers(entry: String) = membersByEntry[entry].orEmpty().map(members::getValue)
    private fun ensureEntry(id: CashImportPreviewInteger): CashEntry<CashImportPreviewInteger> = current(id) ?: reconcile(
        listOf(CashEntryProjectionGroup("singleton", id, listOf(id), emptyList()))).getValue("singleton")
    private fun reconcile(groups: List<CashEntryProjectionGroup<CashImportPreviewInteger>>): Map<String, CashEntry<CashImportPreviewInteger>> {
        val selected = groups.flatMap { it.memberIds }.toSet()
        val before = selected.associateWith { members[it]?.entryId }
        val owners = before.values.filterNotNull().toSet()
        val currentMembers = owners.flatMap(::entryMembers).associateBy { it.transactionId }
        val result = coordinator.reconcileGroups(groups, currentMembers, owners.associateWith(entries::getValue), sources)
        result.newEntries.forEach { entries[it.id] = it }
        result.newMembers.forEach { members[it.transactionId] = it }
        for (member in selected) {
            before[member]?.let { membersByEntry.getValue(it).remove(member) }
            membersByEntry.getOrPut(members.getValue(member).entryId) { linkedSetOf() } += member
        }
        return result.entriesByProjection
    }
    private fun publicValues(category: String?, note: String, counterparty: String, account: String, attrs: JsonElement) = buildJsonObject {
        put("category_id", category?.let(::JsonPrimitive) ?: JsonNull); put("note", note); put("counterparty", counterparty)
        put("counterparty_account", account); put("counterparty_account_attrs", attrs)
    }
}
