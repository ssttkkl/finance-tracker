package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

/** The write-effects component of a file-import conclusion. Never sufficient by itself to authorize a retry. */
fun localCashImportWriteConclusion(source: LedgerSnapshotDto, changes: CashWriteSetDto): String =
    conclusionHash(buildJsonObject {
        put("version", "cash-import-write-conclusion-v1"); put("workspace", source.workspaceId)
        put("rules", CASH_RULES_VERSION); put("changes", conclusionJson.encodeToJsonElement(CashImportConclusionValues(source.facts, changes).normalized()))
    })

internal class CashImportConclusionValues(private val facts: LedgerFactsDto, private val changes: CashWriteSetDto) {
    private val rows = facts.cashTransactions.associateBy { it.id }
    private val parts = facts.cashTransactionComponents.associateBy { it.id }
    private val oldIssues = facts.cashTransactionIssues.associateBy { it.id }
    private val members = facts.cashEntryMembers.groupBy { it.entryId }
    private val transactions = changes.transactions.associate { row ->
        row.target.reference to transactionIdentity(row.target.reference, row.values.sourceType, row.values.recordId)
    }
    private val components = changes.transactions.flatMap { row -> row.components.map { part ->
        part.reference to identity("component", reference(row.target.reference), JsonPrimitive(integer(part.ordinal)))
    } }.toMap()
    private val relations = changes.relations.associate { relation -> relation.target.reference to identity("relation",
        JsonPrimitive(relation.kind), reference(relation.primaryTransaction.reference), relation.secondaryTransaction?.let { reference(it.reference) } ?: JsonNull,
        relation.primaryComponent?.let(::component) ?: JsonNull, relation.secondaryComponent?.let(::component) ?: JsonNull,
        reference(relation.anchorTransaction.reference), relation.anchorComponent?.let(::component) ?: JsonNull,
        JsonPrimitive(relation.primaryRole), JsonPrimitive(relation.secondaryRole), JsonPrimitive(amount(relation.appliedAmount))) }
    private val transactionKeys = changes.transactions.associate { it.target.reference.key() to it.target.reference }
    private val componentKeys = changes.transactions.flatMap { it.components }.associate { it.reference.key() to it.reference }

    fun parent(id: String): JsonElement = reference(transactionKeys[id] ?: ExistingCashWriteReferenceDto(id))
    fun part(id: String): JsonElement = component(componentKeys[id] ?: ExistingCashWriteReferenceDto(id))
    fun hash(value: JsonElement): String = conclusionHash(value)
    fun ordered(values: List<JsonElement>): JsonArray = JsonArray(ordered<JsonElement>(values))
    fun decimal(text: String): String = amount(text)
    private val timestamps = mutableMapOf<String, String>()
    fun timestamp(text: String): String = timestamps.getOrPut(text) {
        formatPythonCashTimestamp(parsePythonCashTimestamp(text))
    }
    private fun CashWriteReferenceDto.key(): String = when (this) {
        is ExistingCashWriteReferenceDto -> id
        is NewCashWriteReferenceDto -> ref
    }

    fun normalized(): CashWriteSetDto = normalized(deferCollections = false)

    fun forSerialization(trustedSourceBindings: Boolean = false): CashWriteSetDto =
        normalized(deferCollections = true, trustedSourceBindings = trustedSourceBindings)

    private fun normalized(deferCollections: Boolean, trustedSourceBindings: Boolean = false): CashWriteSetDto {
        require(changes.deletedTransactions.isEmpty() && changes.issueResolutions.isEmpty() && changes.entrySplitChoices.isEmpty()) {
            "cash_import_conclusion_file_only"
        }
        require(transactions.size == changes.transactions.size && transactions.values.toSet().size == transactions.size &&
            components.size == changes.transactions.sumOf { it.components.size } && components.values.toSet().size == components.size &&
            relations.size == changes.relations.size && relations.values.toSet().size == relations.size) { "cash_import_conclusion_identity_collision" }
        fun <T> collection(size: Int, read: () -> List<T>): List<T> = when {
            size == 0 -> emptyList()
            deferCollections -> ConclusionWriteCollection(size, read)
            else -> read()
        }
        return changes.copy(
            transactions = collection(changes.transactions.size) { ordered(changes.transactions.map { row -> row.copy(target = transaction(row.target),
                values = row.values.copy(amount = row.values.amount?.let(::amount), occurredAt = row.values.occurredAt?.let(::timestamp)), components = ordered(row.components.map { it.copy(reference = componentRef(it.reference), amount = it.amount?.let(::amount), ordinal = integer(it.ordinal)) }),
                editedFields = row.editedFields.sorted(), entryEdit = row.entryEdit?.let { it.copy(entry = it.entry?.let(::entry)) }) }) },
            relations = collection(changes.relations.size) { ordered(changes.relations.map { relation -> relation.copy(target = CashWriteVersionedReferenceDto(relations.getValue(relation.target.reference), null),
                primaryTransaction = transaction(relation.primaryTransaction), secondaryTransaction = relation.secondaryTransaction?.let(::transaction),
                primaryComponent = relation.primaryComponent?.let(::componentRef), secondaryComponent = relation.secondaryComponent?.let(::componentRef),
                anchorTransaction = transaction(relation.anchorTransaction), anchorComponent = relation.anchorComponent?.let(::componentRef),
                decisionGroupId = null, appliedAmount = amount(relation.appliedAmount), candidates = ordered(relation.candidates.map {
                    it.copy(transaction = transaction(it.transaction), component = it.component?.let(::componentRef))
                })) }) },
            // Group order is retained: B is applied in this order before final entry edits.
            relationGroups = collection(changes.relationGroups.size) {
                changes.relationGroups.map { it.copy(relations = it.relations.map(relations::getValue).sortedBy { it.ref }) }
            },
            issues = collection(changes.issues.size) { ordered(changes.issues.map { it.copy(target = CashWriteVersionedReferenceDto(issue(it.transaction.reference, it.issueKey), null),
                transaction = transaction(it.transaction), candidates = ordered(it.candidates.map(::transaction))) }) },
            resolvedIssues = collection(changes.resolvedIssues.size) { ordered(changes.resolvedIssues.map { version ->
                val old = oldIssues.getValue((version.reference as ExistingCashWriteReferenceDto).id)
                CashWriteVersionedReferenceDto(issue(ExistingCashWriteReferenceDto(old.transactionId), old.issueKey), null)
            }) },
            entries = collection(changes.entries.size) { ordered(changes.entries.map { it.copy(rootTransaction = transaction(it.rootTransaction),
                manualFields = it.manualFields.sorted(), editedFields = it.editedFields.sorted(), members = ordered(it.members.map { member ->
                    member.copy(transaction = transaction(member.transaction), standaloneManualFields = member.standaloneManualFields.sorted())
                })) }) },
            entryDependencies = emptyList(),
            sourceBindings = if (trustedSourceBindings && hasSourceBindingSortPrefix && changes.sourceBindings.size > 1)
                streamedSourceBindings() else collection(changes.sourceBindings.size) {
                    ordered(changes.sourceBindings.map(::normalizeSourceBinding))
                },
            sourceEmptyPages = collection(changes.sourceEmptyPages.size) { ordered(changes.sourceEmptyPages) },
            statementAccountMappings = collection(changes.statementAccountMappings.size) { ordered(changes.statementAccountMappings.map {
                it.copy(target = CashWriteVersionedReferenceDto(
                    identity("mapping", JsonPrimitive(it.sourceType), JsonPrimitive(it.identityKind), JsonPrimitive(it.sourceAccountKey)), null))
            }) },
        )
    }

    private fun normalizeSourceBinding(binding: CashSourceBindingWriteDto) = binding.copy(
        target = CashWriteVersionedReferenceDto(identity("binding", JsonPrimitive(binding.sourceType),
            JsonPrimitive(binding.sourceScopeKind), JsonPrimitive(binding.sourceScopeKey),
            JsonPrimitive(binding.identityKind), JsonPrimitive(binding.identityKey)), null),
        transaction = transactionRef(binding.transaction),
    )

    private fun streamedSourceBindings(): List<CashSourceBindingWriteDto> = object : AbstractList<CashSourceBindingWriteDto>() {
        override val size get() = changes.sourceBindings.size

        override fun iterator(): Iterator<CashSourceBindingWriteDto> = sequence {
            val bindings = changes.sourceBindings.toList()
            if (bindings.any { it.coverage !is JsonObject ||
                    (it.transaction !in transactions && (it.transaction as? ExistingCashWriteReferenceDto)?.id !in rows) }) {
                yieldAll(ordered(bindings.map(::normalizeSourceBinding)))
                return@sequence
            }
            val keys = try {
                sourceBindingKeys(bindings).sortedWith(sourceBindingSortOrder)
            } catch (_: IllegalArgumentException) {
                yieldAll(ordered(bindings.map(::normalizeSourceBinding)))
                return@sequence
            }
            var start = 0
            while (start < keys.size) {
                var end = start + 1
                while (end < keys.size && sourceBindingSortOrder.compare(keys[start], keys[end]) == 0) end++
                if (end == start + 1) yield(normalizeSourceBinding(keys[start].binding))
                else yieldAll(ordered(keys.subList(start, end).map { normalizeSourceBinding(it.binding) }))
                start = end
            }
        }.iterator()

        override fun get(index: Int): CashSourceBindingWriteDto {
            if (index !in indices) throw IndexOutOfBoundsException("Invalid source binding position")
            return iterator().asSequence().drop(index).first()
        }
    }

    private fun sourceBindingKeys(bindings: List<CashSourceBindingWriteDto>): List<SourceBindingSortKey> {
        val coverageKeys = mutableListOf<Pair<JsonElement, String>>()
        return bindings.map { binding ->
            val proof = binding.coverage
            val coverage = coverageKeys.firstOrNull { it.first === proof }?.second ?: conclusionText(
                conclusionJson.encodeToJsonElement(binding).jsonObject.getValue("coverage"),
            ).also {
                require(conclusionJson.parseToJsonElement(it) is JsonObject)
                if (coverageKeys.size < 64) coverageKeys += proof to it
            }
            SourceBindingSortKey(binding, coverage, conclusionText(JsonPrimitive(binding.identityKey)),
                conclusionText(JsonPrimitive(binding.identityKind)), conclusionText(JsonPrimitive(binding.identityStrength)))
        }
    }

    private fun transaction(value: CashWriteVersionedReferenceDto) = CashWriteVersionedReferenceDto(transactionRef(value.reference), null)
    private fun reference(value: CashWriteReferenceDto): JsonElement = conclusionJson.encodeToJsonElement<CashWriteReferenceDto>(transactionRef(value))
    private fun transactionRef(value: CashWriteReferenceDto): NewCashWriteReferenceDto = transactions[value] ?: run {
        val row = rows.getValue((value as ExistingCashWriteReferenceDto).id)
        transactionIdentity(value, row.sourceType, row.recordId)
    }
    private fun transactionIdentity(reference: CashWriteReferenceDto, sourceType: String?, recordId: String): NewCashWriteReferenceDto {
        val deleted = (reference as? ExistingCashWriteReferenceDto)?.let { rows[it.id]?.deletedAt != null } ?: false
        if (!deleted && !sourceType.isNullOrEmpty() && recordId.isNotEmpty())
            return identity("transaction", JsonPrimitive(sourceType), JsonPrimitive(recordId))
        require(reference is ExistingCashWriteReferenceDto) { "cash_import_conclusion_identity_missing" }
        return identity("stored-transaction", JsonPrimitive(reference.id))
    }
    private fun component(value: CashWriteReferenceDto): JsonElement = conclusionJson.encodeToJsonElement<CashWriteReferenceDto>(componentRef(value))
    private fun componentRef(value: CashWriteReferenceDto): NewCashWriteReferenceDto = components[value] ?: run {
        val part = parts.getValue((value as ExistingCashWriteReferenceDto).id)
        identity("component", reference(ExistingCashWriteReferenceDto(part.cashTransactionId)), JsonPrimitive(integer(part.ordinal)))
    }
    private fun entry(value: CashWriteVersionedReferenceDto): CashWriteVersionedReferenceDto {
        val id = (value.reference as ExistingCashWriteReferenceDto).id
        val group = members.getValue(id).map { reference(ExistingCashWriteReferenceDto(it.transactionId)) }.sortedBy(::conclusionText)
        return CashWriteVersionedReferenceDto(identity("entry-members", JsonArray(group)), null)
    }
    private fun issue(owner: CashWriteReferenceDto, key: String) = identity("issue", reference(owner), JsonPrimitive(key))
}

/** The serializer uses each iterator once. The normalized list belongs to that iterator, not the write set. */
private class ConclusionWriteCollection<T>(override val size: Int, private val read: () -> List<T>) : AbstractList<T>() {
    override fun iterator(): Iterator<T> = read().iterator()

    override fun get(index: Int): T = read()[index]
}

private class SourceBindingSortKey(
    val binding: CashSourceBindingWriteDto, val coverage: String, val identity: String, val kind: String, val strength: String,
)

private val sourceBindingSortOrder = compareBy(SourceBindingSortKey::coverage, SourceBindingSortKey::identity,
    SourceBindingSortKey::kind, SourceBindingSortKey::strength)

// Every prefix value is a complete object or string, so its canonical bytes cannot prefix a different value.
@OptIn(ExperimentalSerializationApi::class)
private val hasSourceBindingSortPrefix = CashSourceBindingWriteDto.serializer().descriptor.let { descriptor ->
    (0 until descriptor.elementsCount).map(descriptor::getElementName).sortedWith(::pythonStringOrder).take(4) ==
        listOf("coverage", "identity_key", "identity_kind", "identity_strength")
}

private val conclusionJson = Json { encodeDefaults = true }
private fun identity(kind: String, vararg parts: JsonElement) = NewCashWriteReferenceDto(conclusionHash(JsonArray(listOf(JsonPrimitive(kind)) + parts)))
private fun integer(text: String): String = CashImportPreviewInteger.parse(text).toString()
private fun amount(text: String): String {
    val decimal = requireNotNull(LedgerDecimal.parseRepresentationOrNull(text)) { "cash_import_conclusion_amount_invalid" }
    if (decimal.coefficient == "0") return "0"
    val digits = decimal.coefficient.trimEnd('0')
    return (if (decimal.negative) "-" else "") + digits + "E" + (decimal.exponent + decimal.coefficient.length - digits.length)
}

private inline fun <reified T> ordered(items: List<T>): List<T> = items.map { conclusionText(conclusionJson.encodeToJsonElement(it)) to it }
    .sortedBy { it.first }.map { it.second }

private fun canonicalConclusion(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.entries.sortedWith { a, b -> pythonStringOrder(a.key, b.key) }
        .associate { it.key to canonicalConclusion(it.value) })
    is JsonArray -> JsonArray(value.map(::canonicalConclusion))
    else -> value
}

private fun conclusionHash(value: JsonElement): String {
    val text = canonicalConclusion(value).toString()
    val size = text.length + 5 * text.count { it.code >= 128 }
    val bytes = ByteArray(size)
    var index = 0
    writeConclusionText(text) { bytes[index++] = it.toByte() }
    return sha256Hex(bytes)
}

private fun conclusionText(value: JsonElement): String = buildString {
    writeConclusionText(canonicalConclusion(value).toString()) { append(it.toChar()) }
}
