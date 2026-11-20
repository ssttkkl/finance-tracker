package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** One logical position whose complete fragment can be rebuilt from stored facts and verified by digest. */
data class CashWriteSetReplayItem(val index: Int, val id: String, val revision: String)

data class CashWriteSetReplayEntry(val index: Int, val id: String, val revision: String)

data class CashWriteSetReplayGroup(val transactions: List<CashWriteSetReplayItem>,
    val entry: CashWriteSetReplayEntry, val digest: String)

/** The exact logical fragments a repeated item replaces; bindings are always the live stored identities. */
data class CashWriteSetReplayFragment(val transactions: List<CashTransactionWriteDto>,
    val bindings: List<CashSourceBindingWriteDto>, val entry: CashEntryWriteDto)

class CashWriteSetReplayMismatch : IllegalArgumentException("write_set_replay_mismatch")

/** The compact representation would expand beyond the same 64 MiB logical command limit. */
class CashWriteSetPayloadTooLarge : RuntimeException("payload_too_large")

private val compactTopLevelKeys = setOf("command_version", "base_ledger_seq", "rules_version", "changes", "coverage_table", "replay_groups", "request_digest")

private fun existingId(reference: CashWriteReferenceDto): String? = (reference as? ExistingCashWriteReferenceDto)?.id

private fun stringArray(value: JsonElement): List<String> =
    (value as? JsonArray)?.map { check(it is JsonPrimitive && it.isString) { "write_set_replay_invalid" }; it.content }
        ?: throw IllegalArgumentException("write_set_replay_invalid")

private fun canonicalCashJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.keys.sorted().associateWith { canonicalCashJson(value.getValue(it)) })
    is JsonArray -> JsonArray(value.map(::canonicalCashJson))
    else -> value
}

private fun canonicalText(value: JsonElement): String = canonicalCashJson(value).toString()

internal fun cashWriteSetReplayTransactionJson(value: CashTransactionWriteDto): JsonElement =
    cashWriteSetJson.encodeToJsonElement(CashTransactionWriteDto.serializer(), value)

/** Order-insensitive only where the write path is: components by ordinal and members by identity. */
private fun canonicalLogicalCommand(root: JsonObject): JsonObject {
    fun fields(value: JsonElement?): JsonElement = (value as? JsonArray)?.let { array ->
        JsonArray(array.map { it.jsonPrimitive.content }.sorted().map(::JsonPrimitive))
    } ?: JsonNull
    fun referenceKey(value: JsonElement?): String {
        val reference = (value as? JsonObject)?.get("reference") as? JsonObject ?: return ""
        return (reference["id"] ?: reference["ref"])?.jsonPrimitive?.content.orEmpty()
    }
    val changes = root["changes"] as? JsonObject ?: return root
    val transactions = (changes["transactions"] as? JsonArray)?.map { raw ->
        val row = raw.jsonObject
        val components = (row["components"] as? JsonArray)?.map { it.jsonObject }?.sortedBy {
            (it["ordinal"] as? JsonPrimitive)?.content?.toIntOrNull() ?: Int.MAX_VALUE
        }?.map { JsonObject(it) } ?: emptyList()
        JsonObject(row + ("components" to JsonArray(components)) + ("edited_fields" to fields(row["edited_fields"])))
    } ?: emptyList()
    val entries = (changes["entries"] as? JsonArray)?.map { raw ->
        val row = raw.jsonObject
        val members = (row["members"] as? JsonArray)?.map { it.jsonObject }?.sortedBy { referenceKey(it["transaction"]) }
            ?.map { member -> JsonObject(member +
                ("standalone_manual_fields" to fields(member["standalone_manual_fields"]))) } ?: emptyList()
        JsonObject(row + ("members" to JsonArray(members)) + ("manual_fields" to fields(row["manual_fields"])) +
            ("edited_fields" to fields(row["edited_fields"])))
    } ?: emptyList()
    return JsonObject(root + ("changes" to JsonObject(changes +
        ("transactions" to JsonArray(transactions)) + ("entries" to JsonArray(entries)))))
}

/** Binds a compact wire body to the exact full logical request it was generated from. */
fun cashWriteSetRequestDigest(request: CashWriteSetRequestDto): String {
    val full = cashWriteSetJson.encodeToJsonElement(CashWriteSetRequestDto.serializer(), request).jsonObject
    return sha256Hex(canonicalText(canonicalLogicalCommand(full)).encodeToByteArray())
}

private fun cashWriteSetRequestDigest(value: JsonObject): String =
    sha256Hex(canonicalText(canonicalLogicalCommand(value)).encodeToByteArray())

private fun cashWriteSetReplayBindingJson(value: CashSourceBindingWriteDto): JsonElement =
    cashWriteSetJson.encodeToJsonElement(CashSourceBindingWriteDto.serializer(), value)

private fun cashWriteSetReplayEntryJson(value: CashEntryWriteDto): JsonElement =
    cashWriteSetJson.encodeToJsonElement(CashEntryWriteDto.serializer(), value)

/** Stable digest shared by the client proof and the server's fact reconstruction. */
fun cashWriteSetReplayDigest(fragment: CashWriteSetReplayFragment): String {
    fun fields(values: List<String>): JsonArray = JsonArray(values.sorted().map(::JsonPrimitive))
    fun transaction(value: CashTransactionWriteDto): JsonElement = buildJsonObject {
        put("target", cashWriteSetJson.encodeToJsonElement(CashWriteVersionedReferenceDto.serializer(), value.target))
        put("values", cashWriteSetJson.encodeToJsonElement(CashTransactionWriteValuesDto.serializer(), value.values))
        put("components", JsonArray(value.components.sortedBy { it.ordinal.toIntOrNull() ?: Int.MAX_VALUE }.map { part ->
            buildJsonObject {
                put("reference", cashWriteSetJson.encodeToJsonElement(CashWriteReferenceDto.serializer(), part.reference))
                put("account_id", part.accountId); put("amount", part.amount); put("currency", part.currency)
                put("ordinal", part.ordinal); put("label", part.label); put("source_key", part.sourceKey)
                put("metadata_json", part.metadataJson)
            }
        }))
        put("edited_fields", fields(value.editedFields))
        put("entry_edit", value.entryEdit?.let { edit ->
            buildJsonObject {
                put("entry", edit.entry?.let { cashWriteSetJson.encodeToJsonElement(CashWriteVersionedReferenceDto.serializer(), it) } ?: JsonNull)
                put("patch", edit.patch)
            }
        } ?: JsonNull)
    }
    fun entry(value: CashEntryWriteDto): JsonElement = buildJsonObject {
        put("root_transaction", cashWriteSetJson.encodeToJsonElement(CashWriteVersionedReferenceDto.serializer(), value.rootTransaction))
        put("category_id", value.categoryId); put("note", value.note); put("counterparty", value.counterparty)
        put("counterparty_account", value.counterpartyAccount); put("counterparty_account_attrs", value.counterpartyAccountAttrs)
        put("manual_fields", fields(value.manualFields))
        put("members", JsonArray(value.members.sortedBy { existingId(it.transaction.reference).orEmpty() }.map { member ->
            buildJsonObject {
                put("transaction", cashWriteSetJson.encodeToJsonElement(CashWriteVersionedReferenceDto.serializer(), member.transaction))
                put("standalone_values", member.standaloneValues)
                put("standalone_manual_fields", fields(member.standaloneManualFields))
            }
        }))
        put("edited_fields", fields(value.editedFields))
    }
    val projected = buildJsonObject {
        put("bindings", JsonArray(fragment.bindings.sortedBy { existingId(it.target.reference).orEmpty() }
            .map(::cashWriteSetReplayBindingJson)))
        put("entry", entry(fragment.entry))
        put("transactions", JsonArray(fragment.transactions.sortedBy { existingId(it.target.reference).orEmpty() }
            .map(::transaction)))
    }
    return sha256Hex(canonicalText(projected).encodeToByteArray())
}

/** Rebuild exactly the logical command fragment for one unchanged entry from persisted facts. */
fun cashWriteSetReplayFragmentFromFacts(facts: LedgerFactsDto, transactionIds: List<String>,
    entryId: String): CashWriteSetReplayFragment? {
    if (transactionIds.isEmpty() || transactionIds.toSet().size != transactionIds.size) return null
    val rows = facts.cashTransactions.associateBy { it.id }
    val components = facts.cashTransactionComponents.groupBy { it.cashTransactionId }
    val entry = facts.cashEntries.find { it.id == entryId } ?: return null
    if (entry.rootTransactionId !in transactionIds) return null
    val members = facts.cashEntryMembers.filter { it.entryId == entryId }
    if (members.map { it.transactionId }.toSet() != transactionIds.toSet()) return null
    val memberById = members.associateBy { it.transactionId }
    val transactions = transactionIds.map { id ->
        val row = rows[id] ?: return null
        if (row.deletedAt != null) return null
        val parts = components[id].orEmpty().map { part -> part to (part.ordinal.toIntOrNull() ?: return null) }
            .sortedBy { it.second }.map { it.first }
        CashTransactionWriteDto(
            CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(id), row.rowRevision),
            CashTransactionWriteValuesDto(row.accountId, row.state, row.recordOrigin, row.manualTransferId, row.cashGranularity,
                row.sourceType, row.recordId, row.sourcePayload, row.relationMetadata, row.sourceFingerprint, row.manualOverrides,
                row.occurredAt, row.amount, row.currency, row.counterparty, row.counterpartyAccount, row.counterpartyAccountAttrs,
                row.note, row.categoryId, row.recordType, row.recordSubtype),
            parts.map { part ->
                CashComponentWriteDto(ExistingCashWriteReferenceDto(part.id), part.accountId, part.amount, part.currency,
                    part.ordinal, part.label, part.sourceKey, part.metadataJson)
            }, emptyList(), null)
    }
    val root = rows.getValue(entry.rootTransactionId)
    val entryWrite = CashEntryWriteDto(
        CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(entry.rootTransactionId), root.rowRevision),
        entry.categoryId, entry.note, entry.counterparty, entry.counterpartyAccount, entry.counterpartyAccountAttrs,
        stringArray(entry.manualFields),
        transactionIds.map { id ->
            val member = memberById.getValue(id)
            CashEntryMemberWriteDto(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(id), rows.getValue(id).rowRevision),
                member.standaloneValues, stringArray(member.standaloneManualFields))
        }, emptyList())
    val bindings = facts.cashSourceBindings.filter { it.transactionId in transactionIds }.map { binding ->
        CashSourceBindingWriteDto(
            CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(binding.id), binding.revision),
            ExistingCashWriteReferenceDto(binding.transactionId), binding.sourceType, binding.sourceScopeKind,
            binding.sourceScopeKey, binding.identityKind, binding.identityKey, binding.identityStrength,
            binding.stableEvidenceKey, binding.strategyVersion, binding.coverage)
    }
    return CashWriteSetReplayFragment(transactions, bindings, entryWrite)
}

/** Only unchanged rows without any other command dependency can be replaced by a verified proof. */
fun buildCashWriteSetReplayGroups(request: CashWriteSetRequestDto, facts: LedgerFactsDto): List<CashWriteSetReplayGroup> {
    val changes = request.changes
    val rows = facts.cashTransactions.associateBy { it.id }
    val writesById = linkedMapOf<String, MutableList<Pair<Int, CashTransactionWriteDto>>>()
    changes.transactions.forEachIndexed { index, row ->
        existingId(row.target.reference)?.let { id -> writesById.getOrPut(id) { mutableListOf() } += index to row }
    }
    val referenced = mutableSetOf<String>()
    val referencedEntries = mutableSetOf<String>()
    fun reference(value: CashWriteReferenceDto?) { existingId(value ?: return)?.let(referenced::add) }
    changes.sourceBindings.forEach { reference(it.transaction) }
    changes.deletedTransactions.forEach { reference(it.target.reference) }
    changes.relations.forEach { row ->
        reference(row.primaryTransaction.reference); reference(row.secondaryTransaction?.reference)
        reference(row.anchorTransaction.reference); row.candidates.forEach { reference(it.transaction.reference) }
    }
    changes.issues.forEach { row ->
        reference(row.target.reference); reference(row.transaction.reference)
        row.candidates.forEach { reference(it.reference) }
    }
    val issues = facts.cashTransactionIssues.associateBy { it.id }
    fun issue(value: CashWriteVersionedReferenceDto) {
        existingId(value.reference)?.let { issues[it]?.transactionId?.let(referenced::add) }
    }
    changes.resolvedIssues.forEach(::issue)
    changes.issueResolutions.forEach { issue(it.issue) }
    changes.entrySplitChoices.forEach { existingId(it.entry.reference)?.let(referencedEntries::add) }
    changes.transactions.forEach { row -> row.entryEdit?.entry?.reference?.let { existingId(it)?.let(referencedEntries::add) } }

    val usedTransactions = mutableSetOf<Int>()
    val usedEntries = mutableSetOf<Int>()
    val groups = mutableListOf<CashWriteSetReplayGroup>()
    changes.entries.forEachIndexed { entryIndex, entry ->
        if (entryIndex in usedEntries || entry.editedFields.isNotEmpty()) return@forEachIndexed
        if (existingId(entry.rootTransaction.reference)?.let { it in referencedEntries } == true) return@forEachIndexed
        val memberIds = entry.members.map { existingId(it.transaction.reference) ?: return@forEachIndexed }
        if (memberIds.isEmpty() || memberIds.toSet().size != memberIds.size) return@forEachIndexed
        if (memberIds.any { it in referenced }) return@forEachIndexed
        val rootId = existingId(entry.rootTransaction.reference) ?: return@forEachIndexed
        if (rootId !in memberIds) return@forEachIndexed
        val factEntry = facts.cashEntries.find { it.rootTransactionId == rootId } ?: return@forEachIndexed
        val factMembers = facts.cashEntryMembers.filter { it.entryId == factEntry.id }.map { it.transactionId }.toSet()
        if (factMembers != memberIds.toSet()) return@forEachIndexed
        if (memberIds.any { id -> rows[id]?.deletedAt != null || rows[id] == null }) return@forEachIndexed
        if (memberIds.any { writesById[it].isNullOrEmpty() }) return@forEachIndexed
        val positions = memberIds.flatMap { id -> writesById[id].orEmpty().map { it.first } }
        if (positions.isEmpty() || positions.toSet().size != positions.size || positions.any { it in usedTransactions }) return@forEachIndexed
        val representatives = memberIds.map { id ->
            val writes = writesById.getValue(id)
            val first = writes.first().second
            if (writes.any { canonicalText(cashWriteSetReplayTransactionJson(it.second)) !=
                    canonicalText(cashWriteSetReplayTransactionJson(first)) }) return@forEachIndexed
            if (first.editedFields.isNotEmpty() || first.entryEdit != null) return@forEachIndexed
            first
        }
        val full = CashWriteSetReplayFragment(representatives.sortedBy { existingId(it.target.reference).orEmpty() },
            facts.cashSourceBindings.filter { it.transactionId in memberIds }.map { binding ->
                CashSourceBindingWriteDto(
                    CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(binding.id), binding.revision),
                    ExistingCashWriteReferenceDto(binding.transactionId), binding.sourceType, binding.sourceScopeKind,
                    binding.sourceScopeKey, binding.identityKind, binding.identityKey, binding.identityStrength,
                    binding.stableEvidenceKey, binding.strategyVersion, binding.coverage)
            }, entry)
        val stored = cashWriteSetReplayFragmentFromFacts(facts, memberIds.sorted(), factEntry.id) ?: return@forEachIndexed
        val digest = cashWriteSetReplayDigest(full)
        if (digest != cashWriteSetReplayDigest(stored)) return@forEachIndexed
        val transactionItems = memberIds.flatMap { id -> writesById.getValue(id) }.sortedBy { it.first }.map { (index, row) ->
            CashWriteSetReplayItem(index, existingId(row.target.reference)!!,
                (row.target.expectedRevision ?: return@forEachIndexed))
        }
        groups += CashWriteSetReplayGroup(transactionItems,
            CashWriteSetReplayEntry(entryIndex, factEntry.id, factEntry.revision), digest)
        usedTransactions += positions
        usedEntries += entryIndex
    }
    return groups
}

/**
 * Compact transport for an otherwise unchanged logical command. Returns null when no representation is smaller.
 * The command digest still covers this exact JSON; the server expands it only after the receipt lookup.
 */
fun compactCashWriteSetTransport(request: CashWriteSetRequestDto, facts: LedgerFactsDto?): JsonObject? {
    val full = cashWriteSetJson.encodeToJsonElement(CashWriteSetRequestDto.serializer(), request).jsonObject
    val changes = full.getValue("changes").jsonObject
    val replay = facts?.let { buildCashWriteSetReplayGroups(request, it) }.orEmpty()
    val bindings = changes["source_bindings"]?.jsonArray ?: JsonArray(emptyList())
    val table = mutableListOf<JsonElement>()
    val tableIndex = linkedMapOf<String, Int>()
    val compactBindings = bindings.map { raw ->
        val binding = raw.jsonObject
        val coverage = binding["coverage"] ?: return@map binding
        val index = tableIndex.getOrPut(canonicalText(coverage)) { table += coverage; table.size - 1 }
        JsonObject(binding - "coverage" + ("coverage_ref" to JsonPrimitive(index)))
    }
    val bindingsSaved = table.isNotEmpty() && table.toString().length <
        bindings.sumOf { it.jsonObject["coverage"]?.toString()?.length ?: 0 }
    if (replay.isEmpty() && !bindingsSaved) return null
    val replayedTransactions = replay.flatMap { group -> group.transactions.map { it.index } }.toSet()
    val replayedEntries = replay.map { it.entry.index }.toSet()
    val logicalChanges = buildJsonObject {
        changes.forEach { (key, value) ->
            when (key) {
                "source_bindings" -> put(key, JsonArray(if (bindingsSaved) compactBindings else bindings))
                "transactions" -> put(key, JsonArray(value.jsonArray.filterIndexed { index, _ -> index !in replayedTransactions }))
                "entries" -> put(key, JsonArray(value.jsonArray.filterIndexed { index, _ -> index !in replayedEntries }))
                else -> put(key, value)
            }
        }
    }
    return buildJsonObject {
        put("command_version", 2)
        full.forEach { (key, value) -> if (key != "changes") put(key, value) }
        if (bindingsSaved) put("coverage_table", JsonArray(table))
        put("request_digest", cashWriteSetRequestDigest(request))
        if (replay.isNotEmpty()) put("replay_groups", JsonArray(replay.map { group ->
            buildJsonObject {
                put("transactions", JsonArray(group.transactions.map { item -> buildJsonObject {
                    put("index", item.index); put("id", item.id); put("revision", item.revision)
                } }))
                put("entry", buildJsonObject {
                    put("index", group.entry.index); put("id", group.entry.id); put("revision", group.entry.revision)
                })
                put("digest", group.digest)
            }
        }))
        put("changes", logicalChanges)
    }
}

/** Server-side inverse; the caller has already verified the receipt replay for the received bytes. */
fun expandCashWriteSetTransport(payload: JsonObject, facts: LedgerFactsDto?,
    sourceChanges: JsonObject? = null): JsonObject {
    if ((payload["command_version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull != 2) {
        throw IllegalArgumentException("write_set_payload_invalid")
    }
    if (payload.keys.any { it !in compactTopLevelKeys }) throw IllegalArgumentException("write_set_payload_invalid")
    val changes = payload["changes"] as? JsonObject ?: throw IllegalArgumentException("write_set_payload_invalid")
    val table = payload["coverage_table"]?.let { it as? JsonArray ?: throw IllegalArgumentException("write_set_payload_invalid") }
        ?: JsonArray(emptyList())
    if (table.size > 100_000) throw IllegalArgumentException("write_set_payload_invalid")
    var budget = 0L
    fun charge(bytes: Long) {
        budget += bytes
        if (budget > 64L * 1024 * 1024) throw CashWriteSetPayloadTooLarge()
    }
    val tableBytes = LongArray(table.size) { table[it].toString().encodeToByteArray().size.toLong() }
    val expandedBindings = (changes["source_bindings"] as? JsonArray ?: JsonArray(emptyList())).map { raw ->
        val binding = raw as? JsonObject ?: throw IllegalArgumentException("write_set_payload_invalid")
        val hasCoverage = "coverage" in binding
        val hasReference = "coverage_ref" in binding
        if (hasCoverage == hasReference) throw IllegalArgumentException("write_set_payload_invalid")
        if (hasCoverage) return@map binding
        val index = (binding["coverage_ref"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw IllegalArgumentException("write_set_payload_invalid")
        if (index !in table.indices) throw IllegalArgumentException("write_set_payload_invalid")
        charge(tableBytes[index] + 64)
        JsonObject(binding - "coverage_ref" + ("coverage" to table[index]))
    }
    val groups = payload["replay_groups"]?.let { it as? JsonArray ?: throw IllegalArgumentException("write_set_payload_invalid") }
        ?: JsonArray(emptyList())
    if (groups.size > 100_000) throw IllegalArgumentException("write_set_payload_invalid")
    val replayFacts = if (groups.isEmpty()) null else if (sourceChanges != null) null else facts ?: throw CashWriteSetReplayMismatch()
    fun objectOf(value: JsonElement?): JsonObject = value as? JsonObject ?: throw IllegalArgumentException("write_set_payload_invalid")
    fun textOf(value: JsonElement?): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("write_set_payload_invalid")
    fun intOf(value: JsonElement?): Int =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw IllegalArgumentException("write_set_payload_invalid")
    val storedRows = replayFacts?.cashTransactions?.associateBy { it.id }.orEmpty()
    val storedEntries = replayFacts?.cashEntries?.associateBy { it.id }.orEmpty()
    val transactionPositions = mutableMapOf<Int, JsonElement>()
    val entryPositions = mutableMapOf<Int, JsonElement>()
    for (raw in groups) {
        val group = objectOf(raw)
        if (group.keys != setOf("transactions", "entry", "digest")) throw IllegalArgumentException("write_set_payload_invalid")
        val items = group.getValue("transactions") as? JsonArray ?: throw IllegalArgumentException("write_set_payload_invalid")
        if (items.isEmpty()) throw IllegalArgumentException("write_set_payload_invalid")
        val parsed = items.map { itemRaw ->
            val item = objectOf(itemRaw)
            if (item.keys != setOf("index", "id", "revision")) throw IllegalArgumentException("write_set_payload_invalid")
            val index = intOf(item["index"]); val id = textOf(item["id"]); val revision = textOf(item["revision"])
            if (index < 0 || id.isEmpty() || revision.isEmpty()) throw IllegalArgumentException("write_set_payload_invalid")
            if (sourceChanges == null) {
                val row = storedRows[id] ?: throw CashWriteSetReplayMismatch()
                if (row.deletedAt != null || row.rowRevision != revision) throw CashWriteSetReplayMismatch()
            }
            Triple(index, id, revision)
        }
        if (parsed.map { it.first }.toSet().size != parsed.size) throw IllegalArgumentException("write_set_payload_invalid")
        val entry = objectOf(group["entry"])
        if (entry.keys != setOf("index", "id", "revision")) throw IllegalArgumentException("write_set_payload_invalid")
        val entryIndex = intOf(entry["index"]); val entryId = textOf(entry["id"]); val entryRevision = textOf(entry["revision"])
        if (entryIndex < 0) throw IllegalArgumentException("write_set_payload_invalid")
        if (sourceChanges != null) {
            val sourceTransactions = sourceChanges["transactions"] as? JsonArray ?: JsonArray(emptyList())
            val sourceEntries = sourceChanges["entries"] as? JsonArray ?: JsonArray(emptyList())
            for ((index, id, revision) in parsed) {
                if (index in transactionPositions) throw IllegalArgumentException("write_set_payload_invalid")
                val source = sourceTransactions.getOrNull(index) as? JsonObject ?: throw CashWriteSetReplayMismatch()
                val target = source["target"] as? JsonObject ?: throw CashWriteSetReplayMismatch()
                val reference = target["reference"] as? JsonObject ?: throw CashWriteSetReplayMismatch()
                if ((reference["type"] as? JsonPrimitive)?.content != "existing" ||
                    (reference["id"] as? JsonPrimitive)?.content != id ||
                    (target["expected_revision"] as? JsonPrimitive)?.content != revision) throw CashWriteSetReplayMismatch()
                charge(source.toString().encodeToByteArray().size.toLong())
                transactionPositions[index] = source
            }
            val sourceEntry = sourceEntries.getOrNull(entryIndex) as? JsonObject ?: throw CashWriteSetReplayMismatch()
            val root = sourceEntry["root_transaction"] as? JsonObject ?: throw CashWriteSetReplayMismatch()
            val rootReference = root["reference"] as? JsonObject ?: throw CashWriteSetReplayMismatch()
            if ((rootReference["type"] as? JsonPrimitive)?.content != "existing") throw CashWriteSetReplayMismatch()
            charge(sourceEntry.toString().encodeToByteArray().size.toLong())
            if (entryIndex in entryPositions) throw IllegalArgumentException("write_set_payload_invalid")
            entryPositions[entryIndex] = sourceEntry
            continue
        }
        val storedEntry = storedEntries[entryId] ?: throw CashWriteSetReplayMismatch()
        if (storedEntry.revision != entryRevision) throw CashWriteSetReplayMismatch()
        val ids = parsed.map { it.second }.distinct()
        val fragment = cashWriteSetReplayFragmentFromFacts(checkNotNull(replayFacts), ids, entryId) ?: throw CashWriteSetReplayMismatch()
        if (cashWriteSetReplayDigest(fragment) != textOf(group["digest"])) throw CashWriteSetReplayMismatch()
        val transactionJson = fragment.transactions.associateBy { existingId(it.target.reference)!! }
        val entryJson = cashWriteSetReplayEntryJson(fragment.entry)
        charge(entryJson.toString().encodeToByteArray().size.toLong())
        for ((index, id, _) in parsed) {
            if (index in transactionPositions) throw IllegalArgumentException("write_set_payload_invalid")
            val value = cashWriteSetReplayTransactionJson(transactionJson.getValue(id))
            charge(value.toString().encodeToByteArray().size.toLong())
            transactionPositions[index] = value
        }
        if (entryIndex in entryPositions) throw IllegalArgumentException("write_set_payload_invalid")
        entryPositions[entryIndex] = entryJson
    }
    if (transactionPositions.size + entryPositions.size > 1_000_000) throw IllegalArgumentException("write_set_payload_invalid")
    fun merge(key: String, positions: Map<Int, JsonElement>): JsonArray {
        val transmitted = (changes[key] as? JsonArray)?.toList() ?: emptyList()
        if (positions.isEmpty()) return JsonArray(transmitted)
        val total = transmitted.size + positions.size
        if (positions.keys.any { it >= total }) throw IllegalArgumentException("write_set_payload_invalid")
        val merged = MutableList<JsonElement?>(total) { null }
        for ((index, value) in positions) merged[index] = value
        var next = 0
        for (value in transmitted) {
            while (next < total && merged[next] != null) next++
            if (next >= total) throw IllegalArgumentException("write_set_payload_invalid")
            merged[next++] = value
        }
        if (merged.any { it == null }) throw IllegalArgumentException("write_set_payload_invalid")
        return JsonArray(merged.map { it!! })
    }
    val expandedChanges = buildJsonObject {
        changes.forEach { (key, value) -> if (key !in setOf("source_bindings", "transactions", "entries")) put(key, value) }
        put("source_bindings", JsonArray(expandedBindings))
        put("transactions", merge("transactions", transactionPositions))
        put("entries", merge("entries", entryPositions))
    }
    val expanded = buildJsonObject {
        payload.forEach { (key, value) -> if (key in setOf("base_ledger_seq", "rules_version")) put(key, value) }
        put("changes", expandedChanges)
    }
    if (expanded.toString().encodeToByteArray().size > 64 * 1024 * 1024) throw CashWriteSetPayloadTooLarge()
    val expectedDigest = (payload["request_digest"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("write_set_payload_invalid")
    if (cashWriteSetRequestDigest(expanded) != expectedDigest) throw CashWriteSetReplayMismatch()
    return expanded
}

/** A frozen compact wire body is valid only when it expands to the exact stored logical request. */
fun verifyCashWriteSetTransport(request: CashWriteSetRequestDto, payload: JsonObject) {
    val full = cashWriteSetJson.encodeToJsonElement(CashWriteSetRequestDto.serializer(), request).jsonObject
    if ((payload["request_digest"] as? JsonPrimitive)?.takeIf { it.isString }?.content != cashWriteSetRequestDigest(request)) {
        throw IllegalArgumentException("cash_import_submission_state_invalid")
    }
    val changes = full["changes"] as? JsonObject ?: throw IllegalArgumentException("cash_import_submission_state_invalid")
    val expanded = try {
        expandCashWriteSetTransport(payload, null, sourceChanges = changes)
    } catch (_: CashWriteSetReplayMismatch) {
        throw IllegalArgumentException("cash_import_submission_state_invalid")
    } catch (_: CashWriteSetPayloadTooLarge) {
        throw IllegalArgumentException("cash_import_submission_state_invalid")
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("cash_import_submission_state_invalid", failure)
    }
    if (canonicalText(canonicalLogicalCommand(expanded)) != canonicalText(canonicalLogicalCommand(full))) {
        throw IllegalArgumentException("cash_import_submission_state_invalid")
    }
}
