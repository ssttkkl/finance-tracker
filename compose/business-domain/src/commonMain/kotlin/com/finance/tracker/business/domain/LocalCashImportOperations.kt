package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Build operations locally from saved input and one authoritative snapshot; no network or writes. */
fun prepareLocalCashImportOperations(files: List<ParsedImportFile>, snapshot: LedgerSnapshotDto,
    accountSelections: JsonElement = JsonArray(emptyList()), rowChoices: JsonElement = JsonArray(emptyList()),
    recordIds: Map<String, String> = emptyMap(),
    wireValues: Boolean = true): PreparedCashImportOperations {
    fun input(value: JsonElement): List<Any?> = decodeLocalCashValue(value) as? List<*>
        ?: throw CashImportPlanError("plan_input_invalid")
    val rules = CashImportOperationRules(
        reads = LocalCashImportOperationRead(snapshot), recordValues = LocalCashRecordValues,
        previewInput = { accounts, rows -> prepareLocalCashImportInput(encodeLocalCashValue(accounts), encodeLocalCashValue(rows), JsonArray(emptyList())) },
        scanFunding = ::scanLocalCashFunding,
        resolveFunding = { scan, selections, accounts -> resolveLocalCashFunding(scan, encodeLocalCashValue(selections), encodeLocalCashValue(accounts)) },
        postingValidator = { LocalCashRecordValues::validateForPosting }, wireValue = ::wireLocalCashOperation,
        digestValues = ::localCashSourceDigest, allocateRecordId = ::allocateLocalCashSourceRecordId,
        issueValue = { code, field, after -> cashImportIssue(code, fieldPath = field, afterImportAction = after) },
        integerValue = ::SourceJsonInteger,
    )
    return rules.prepare(files, input(accountSelections), input(rowChoices), recordIds, null, wireValues)
}

private fun localCashReadIds(values: Collection<Any?>): Set<String> = values.mapNotNull { value ->
    val text = if (value is String) value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
        else localCashIntegerText(value)
    text?.toLongOrNull()?.takeIf { it > 0 }?.toString()
}.toSet()

private class LocalCashImportOperationRead(private val snapshot: LedgerSnapshotDto) : CashImportOperationRead {
    private val facts get() = snapshot.facts
    override fun source(files: List<ParsedImportFile>) = prepareLocalCashImportSources(files, snapshot)
    override fun accounts(selections: Map<String, Map<String, Any?>>, records: List<SourceRecord>,
        choices: Map<String, Map<String, Any?>>): Map<String, Map<String, Any?>> {
        val references = mutableListOf<Any?>()
        selections.values.forEach { references += it["account_id"] }
        records.forEach { references += it.fields["account_id"] }
        for (choice in choices.values) {
            val patch = choice["patch"] as? Map<*, *> ?: continue
            references += patch["account_id"]
            (patch["components"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { references += it["account_id"] }
        }
        val ids = localCashReadIds(references)
        return facts.accounts.filter { it.id in ids }.associate { account -> account.id to mapOf(
            "id" to account.id, "revision" to JsonPrimitive(account.revision.toLong()), "active" to account.active,
            "type" to account.type, "currencies" to decodeLocalCashValue(account.currencies)) }
    }
    override fun targets(records: Map<String, SourceRecord>): Pair<Map<String, CashImportOperationTarget>, Map<String, List<Map<String, Any?>>>> {
        val ids = localCashReadIds(records.keys)
        val targets = facts.cashTransactions.filter { it.id in ids && it.deletedAt == null }.associate { tx ->
            @Suppress("UNCHECKED_CAST") val metadata = decodeLocalCashValue(tx.relationMetadata) as? Map<String, Any?>
            tx.id to CashImportOperationTarget(tx.recordId, requireNotNull(tx.sourceType), metadata)
        }
        val issues = facts.cashTransactionIssues.filter { it.transactionId in ids }.groupBy { it.transactionId }.mapValues { (_, items) ->
            items.map { item -> mapOf("issue_id" to item.id, "revision" to item.revision.toLong(), "code" to item.code,
                "field_path" to item.fieldPath, "issue_key" to item.issueKey) }
        }
        return targets to issues
    }
    override fun categories(choices: Map<String, Map<String, Any?>>): Set<String> {
        val ids = choices.values.mapNotNull { (it["patch"] as? Map<*, *>)?.get("category_id") as? String }.toSet()
        return facts.cashCategories.map { it.id }.filter { it in ids }.toSet()
    }
    override fun entries(transactionIds: Collection<String>): Pair<Map<String, CashImportEntryVersion>, Map<String, List<Any?>>> {
        val ids = transactionIds.mapNotNull { it.toLongOrNull()?.toString() }.toSet()
        val entries = facts.cashEntries.associateBy { it.id }
        val members = facts.cashEntryMembers.filter { it.transactionId in ids }
        val versions = members.associate { member -> entries.getValue(member.entryId).let {
            member.transactionId to CashImportEntryVersion(it.id, it.revision.toLong())
        } }
        val snapshots = members.sortedBy { it.transactionId.toLong() }.associate { member ->
            val entry = entries.getValue(member.entryId)
            member.transactionId to listOf<Any?>(listOf(member.transactionId.toLong(), member.entryId,
                entry.revision.toLong(), localCashEntrySnapshotDigest(entry, member)))
        }
        return versions to snapshots
    }
}

/** Entry dependencies retain persisted JSON number tokens, unlike source-value display/digest encoding. */
internal fun localCashEntrySnapshotDigest(entry: LedgerCashEntryFactDto, member: LedgerCashEntryMemberFactDto): String {
    fun stringList(value: JsonElement): JsonArray = JsonArray(value.jsonArray.map {
        require(it is JsonPrimitive && it.isString)
        JsonPrimitive(it.content)
    })
    fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedWith { a, b -> pythonStringOrder(a.key, b.key) }
            .associate { it.key to canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
    val values = buildJsonObject {
        put("category_id", entry.categoryId?.let(::JsonPrimitive) ?: JsonNull); put("note", entry.note)
        put("counterparty", entry.counterparty); put("counterparty_account", entry.counterpartyAccount)
        put("counterparty_account_attrs", entry.counterpartyAccountAttrs)
    }
    val payload = JsonArray(listOf(values, stringList(entry.manualFields), JsonPrimitive(entry.identityTransactionId.toLong()),
        member.standaloneValues.jsonObject, stringList(member.standaloneManualFields)))
    return sha256Hex(canonical(payload).toString().encodeToByteArray(throwOnInvalidSequence = true))
}

private fun wireLocalCashOperation(value: Any?): Any? = when (value) {
    null -> null
    is CashAmount -> parseSourceAccountAmount(value.toString()).toPlainString()
    is Instant -> formatPythonCashTimestamp(value)
    is Map<*, *> -> value.entries.associate { it.key.toString() to wireLocalCashOperation(it.value) }
    is List<*> -> value.map(::wireLocalCashOperation)
    else -> value
}
