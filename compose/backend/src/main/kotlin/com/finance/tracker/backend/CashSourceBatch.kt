package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Instant

internal data class SourceBatchResolution(
    val rowRef: String,
    val action: String,
    val transactionId: String? = null,
    val candidateIds: List<String> = emptyList(),
    val reason: String = "",
    val bind: Boolean = false,
    val duplicateOf: String? = null,
    val groupRef: String? = null,
)

internal data class SourceBatchGroup(val groupRef: String, val rowRefs: List<String> = emptyList(),
    val candidateIds: List<String> = emptyList())
internal data class SourceBatchPlan(val rows: List<SourceBatchResolution>, val groups: List<SourceBatchGroup> = emptyList())

internal object CashSourceBatch {
    private val stringOrder = Comparator<String>(::pythonStringOrder)
    private data class UnitRow(val ref: String, val refs: List<String>, val row: SourceOccurrence,
        val normalized: Map<String, Any?>, val certain: Boolean, val targets: Set<String>, val files: Set<String>) {
        val contentKey by lazy { sourceDigest(contentValue(normalized)) }
    }
    private data class Rejected(val refs: List<String>, val files: Set<String>, val row: SourceOccurrence,
        val targets: Set<String>)
    private data class Evaluation(val only: SourceResolution?, val failed: Boolean, val group: String?)

    fun resolve(input: SourceBatchRows, records: List<SourceRecord>,
        knownDocumentEvidence: List<SourceDocumentEvidence> = emptyList()): SourceBatchPlan {
        val rows = input.rows
        require(rows.keys.all { it.isNotEmpty() }) { "invalid_source_row_ref" }
        val active = records.filter { !it.deleted }
        val byBinding = mutableMapOf<String, MutableSet<String>>()
        val byIndex = mutableMapOf<Pair<String, CashSourceResolver.Index>, MutableList<SourceRecord>>()
        for (record in active) {
            CashSourceResolver.recordBindingKeys(record).forEach {
                byBinding.getOrPut(it) { linkedSetOf() } += record.transactionId
            }
            CashSourceResolver.indexOf(record.fields)?.let {
                byIndex.getOrPut(record.sourceType to it) { mutableListOf() } += record
            }
        }
        val output = linkedMapOf<String, SourceBatchResolution>()
        val resultGroups = linkedMapOf<String, SourceBatchGroup>()
        val groupCache = java.util.IdentityHashMap<List<String>, java.util.IdentityHashMap<List<String>, String>>()
        val roots = linkedMapOf<String, String>()
        val proofOwner = mutableMapOf<String, String>()
        val contents = mutableMapOf<String, Map<String, Any?>>()
        val targets = mutableMapOf<String, Set<String>>()
        val rowOrder = rows.keys.withIndex().associate { it.value to it.index }
        val namespaces = rows.values.mapNotNull { row -> row.coverage.documentKey?.let {
            row.sourceType to it to row.parserVersion
        } }.groupBy({ it.first }, { it.second })
        val evidence = input.documentEvidence.ifEmpty {
            input.documentCoverages.flatMap { coverage -> namespaces.filterKeys { it.second == coverage.documentKey }
                .filterValues { it.distinct().size == 1 }.map { (key, versions) ->
                    SourceDocumentEvidence(key.first, versions.first(), coverage)
                }
            }
        }
        val conflictingPages = CashSourceResolver.conflictingSourceDocumentPages(rows.values.toList(), active,
            evidence + knownDocumentEvidence)

        fun sharedGroup(refs: List<String> = emptyList(), ids: List<String> = emptyList()): String {
            return groupCache.getOrPut(refs) { java.util.IdentityHashMap() }.getOrPut(ids) {
                val key = sourceDigest("source-results-v1", refs, ids)
                resultGroups.putIfAbsent(key, SourceBatchGroup(key, refs, ids))
                key
            }
        }
        fun result(ref: String, value: SourceResolution): SourceBatchResolution {
            val group = if (value.candidateIds.size > 1) sharedGroup(ids = value.candidateIds) else null
            return SourceBatchResolution(ref, value.action, value.transactionId,
                if (group == null) value.candidateIds else emptyList(), value.reason, value.bind, groupRef = group)
        }
        fun root(ref: String): String {
            var current = ref
            while (roots.getValue(current) != current) {
                roots[current] = roots.getValue(roots.getValue(current))
                current = roots.getValue(current)
            }
            return current
        }
        fun snapshotWinner(refs: List<String>): String? {
            val files = refs.map { it.substringBefore(':') }
            if (files.distinct().size != files.size) return null
            val metadata = files.associateWith { file -> input.fileMetadata[file] ?: (refs.filter {
                it.substringBefore(':') == file
            }.minOf { rowOrder.getValue(it) } to null) }
            if (metadata.values.any { it.first < 0 || it.second?.let { time -> time < 0 } == true }) return null
            val times = metadata.values.map { it.second }
            val useTimes = times.all { it != null } && times.distinct().size == times.size
            return refs.minWithOrNull(compareByDescending<String> {
                if (useTimes) metadata.getValue(it.substringBefore(':')).second!!
                else metadata.getValue(it.substringBefore(':')).first.toLong()
            }.thenBy { metadata.getValue(it.substringBefore(':')).first }.thenBy { rowOrder.getValue(it) })
        }
        fun pageConflict(row: SourceOccurrence): Boolean = row.documentPage?.let {
            DocumentPageProof(row.sourceType, row.parserVersion, it.first, it.second) in conflictingPages
        } == true

        for ((ref, row) in rows) {
            val normalized = try { CashRecordPolicy.prepare(mapOf("state" to "draft"), CashSourceResolver.patch(row)).normalizedValues }
            catch (failure: CashPolicyError) {
                output[ref] = SourceBatchResolution(ref, "conflict", reason = failure.code)
                continue
            }
            contents[ref] = normalized
            roots[ref] = ref
            val keys = row.bindingKeys + listOfNotNull(row.stableEvidenceBindingKey)
            targets[ref] = keys.flatMap { byBinding[it].orEmpty() }.toSet()
            val proofs = listOf("occurrence:" + row.occurrenceKey) + listOfNotNull(row.documentBindingKey,
                row.providerBindingKey, row.stableEvidenceBindingKey) + targets.getValue(ref).map { "target:$it" }
            for (proof in proofs) {
                val previous = proofOwner.getOrPut(proof) { ref }
                roots[root(ref)] = root(previous)
            }
        }
        val identified = roots.keys.groupBy(::root)
        val units = linkedMapOf<String, UnitRow>()
        val rejected = mutableMapOf<Pair<String, CashSourceResolver.Index>, MutableMap<List<Any?>, Rejected>>()
        for (originalRefs in identified.values) {
            var refs = originalRefs.sortedBy { rowOrder.getValue(it) }
            var first = rows.getValue(refs.first())
            val providers = refs.mapNotNull { rows.getValue(it).providerId }.toSet()
            val proofs = refs.mapNotNull { rows.getValue(it).stableEvidence }.toSet()
            val ids = refs.flatMap { targets.getValue(it) }.toSet()
            val sortedIds = ids.sortedWith(stringOrder)
            val different = refs.size > 1 && refs.map { sourceDigest(contentValue(contents.getValue(it))) }.distinct().size > 1
            val sameShape = refs.map { rows.getValue(it).fields.keys.toSet() }.distinct().size == 1
            val sameScope = refs.map { rows.getValue(it).let { row -> row.sourceType to row.sourceAccountKey } }.distinct().size == 1
            var reliable = different && (providers.size == 1 || proofs.size == 1) && providers.size <= 1 &&
                proofs.size <= 1 && ids.size <= 1 && sameShape && sameScope && refs.none { pageConflict(rows.getValue(it)) }
            if (reliable) {
                val winner = snapshotWinner(refs)
                if (winner == null) reliable = false
                else { refs = listOf(winner) + refs.filter { it != winner }; first = rows.getValue(winner) }
            }
            val failed = when {
                different && !reliable || providers.size > 1 || proofs.size > 1 || !sameScope ||
                    refs.any { pageConflict(rows.getValue(it)) } -> {
                    val group = if (ids.size > 1) sharedGroup(ids = sortedIds) else null
                    refs.forEach { output[it] = SourceBatchResolution(it, "conflict",
                        candidateIds = if (group == null) sortedIds else emptyList(), reason = "batch_identity_conflict", groupRef = group) }
                    true
                }
                ids.size > 1 -> {
                    val group = sharedGroup(ids = sortedIds)
                    refs.forEach { output[it] = SourceBatchResolution(it, "pending", reason = "source_identity_ambiguous", groupRef = group) }
                    true
                }
                else -> {
                    val combined = first.copy(providerId = providers.firstOrNull(), stableEvidence = proofs.firstOrNull())
                    val unit = UnitRow(refs.first(), refs, combined, contents.getValue(refs.first()),
                        ids.isNotEmpty() || combined.providerBindingKey != null || combined.stableEvidenceBindingKey != null,
                        ids, refs.map { rows.getValue(it).observationScopeKey }.toSet())
                    units[unit.ref] = unit
                    false
                }
            }
            val rejectedFiles = if (failed) refs.map { rows.getValue(it).observationScopeKey }.toSet() else emptySet()
            if (failed) for (ref in refs) {
                val row = rows.getValue(ref)
                val index = CashSourceResolver.indexOf(row.fields) ?: continue
                rejected.getOrPut(row.sourceType to index) { linkedMapOf() }[
                    listOf(refs.first(), row.sourceAccountKey, row.providerId, row.stableEvidence)] =
                    Rejected(refs, rejectedFiles, row, ids)
            }
        }
        val groups = linkedMapOf<Triple<String, String?, CashSourceResolver.Index>, MutableList<UnitRow>>()
        val scopes = linkedMapOf<Pair<String, CashSourceResolver.Index>, MutableList<UnitRow>>()
        val canonical = linkedMapOf<String, UnitRow>()
        val destinations = mutableMapOf<String, List<String>>()
        fun select(unit: UnitRow) { canonical[unit.ref] = unit; destinations[unit.ref] = listOf(unit.ref) }
        for (unit in units.values) {
            val index = CashSourceResolver.indexOf(unit.row.fields)
            if (index == null) select(unit)
            else {
                groups.getOrPut(Triple(unit.row.sourceType, unit.row.sourceAccountKey, index)) { mutableListOf() } += unit
                scopes.getOrPut(unit.row.sourceType to index) { mutableListOf() } += unit
            }
        }
        fun defer(subjects: List<UnitRow>, alternatives: List<UnitRow>) {
            val unit = subjects.first()
            val relevant = byIndex[unit.row.sourceType to CashSourceResolver.indexOf(unit.row.fields)].orEmpty()
            val ids = relevant.filter { unit.row.sourceAccountKey == null || it.sourceAccountKey.isNullOrEmpty() ||
                it.sourceAccountKey == unit.row.sourceAccountKey }.map { it.transactionId }.toSet() + alternatives.flatMap { it.targets }
            val group = sharedGroup(alternatives.map { it.ref }.sortedWith(stringOrder), ids.sortedWith(stringOrder))
            subjects.flatMap { it.refs }.forEach {
                output[it] = SourceBatchResolution(it, "pending", reason = "source_identity_ambiguous", groupRef = group)
            }
        }
        val collisionCache = mutableMapOf<List<Any?>, String?>()
        for (unit in units.values.filter { !it.certain }) {
            val cacheKey = listOf(unit.row.sourceType, CashSourceResolver.indexOf(unit.row.fields), unit.row.sourceAccountKey,
                unit.row.providerId, unit.row.stableEvidence, unit.files)
            if (cacheKey !in collisionCache) {
                val collisions = rejected[unit.row.sourceType to CashSourceResolver.indexOf(unit.row.fields)].orEmpty().values.filter {
                it.files.intersect(unit.files).isEmpty() && (unit.row.sourceAccountKey == null || it.row.sourceAccountKey == null ||
                    unit.row.sourceAccountKey == it.row.sourceAccountKey) && CashSourceResolver.compatible(unit.row, it.row)
            }
                collisionCache[cacheKey] = if (collisions.isEmpty()) null else
                    sharedGroup(collisions.flatMap { it.refs }.distinct().sortedWith(stringOrder), collisions.flatMap { it.targets }.distinct().sortedWith(stringOrder))
            }
            collisionCache[cacheKey]?.let { group ->
                unit.refs.forEach { output[it] = SourceBatchResolution(it, "pending", reason = "source_identity_ambiguous", groupRef = group) }
            }
        }
        for (members in scopes.values) {
            val known = members.filter { it.row.sourceAccountKey != null }
            val alternativesCache = mutableMapOf<List<Any?>, String?>()
            for (unit in members.filter { it.row.sourceAccountKey == null && !it.certain }) {
                val key = listOf(unit.row.providerId, unit.row.stableEvidence, unit.files)
                if (key !in alternativesCache) {
                    val alternatives = known.filter { (it.files + unit.files).size > 1 && it.files.intersect(unit.files).isEmpty() &&
                        CashSourceResolver.compatible(unit.row, it.row) }
                    if (alternatives.isNotEmpty()) defer(listOf(unit), alternatives)
                    alternativesCache[key] = if (alternatives.isEmpty()) null else output.getValue(unit.ref).groupRef
                }
                alternativesCache[key]?.let { group -> unit.refs.forEach {
                    output[it] = SourceBatchResolution(it, "pending", reason = "source_identity_ambiguous", groupRef = group)
                } }
            }
        }
        val partitions = groups.values.flatMap { members -> when {
            members.all { it.row.providerId != null } -> members.groupBy { it.row.providerId }.values
            members.all { it.row.stableEvidence != null } -> members.groupBy { it.row.stableEvidence }.values
            else -> listOf(members)
        } }
        for (partition in partitions) {
            val members = partition.filter { it.ref !in output }
            if (members.isEmpty()) continue
            val files = linkedMapOf<String, MutableList<UnitRow>>()
            for (unit in members) unit.files.forEach { files.getOrPut(it) { mutableListOf() } += unit }
            if (files.size == 1 || members.all { it.certain }) { members.forEach(::select); continue }
            val first = members.first().row
            val comparable = first.sourceAccountKey != null && members.flatMap { it.refs }.all {
                first.coverage.comparable(rows.getValue(it).coverage, first.fields["occurred_at"])
            }
            val counts = files.mapValues { (_, values) -> values.groupingBy { it.contentKey }.eachCount() }
            val equivalent = members.map { it.contentKey }.distinct().size == 1 || counts.values.distinct().size == 1
            if (!comparable || !equivalent) {
                val weak = mutableListOf<UnitRow>()
                for (unit in members) {
                    if (unit.certain || members.any { it !== unit && it.certain && it.files.intersect(unit.files).isNotEmpty() }) select(unit)
                    else weak += unit
                }
                if (weak.isNotEmpty()) defer(weak, members)
                continue
            }
            for ((content, values) in members.groupBy { it.contentKey }) {
                val chosen = values.filter { it.certain }.toMutableList()
                val maximum = counts.values.maxOf { it[content] ?: 0 }
                val anchor = counts.keys.minWith(compareByDescending<String> { counts.getValue(it)[content] ?: 0 }.thenBy(stringOrder) { it })
                val weak = values.filter { !it.certain }.sortedWith(compareBy<UnitRow> { anchor !in it.files }.thenBy(stringOrder) { it.ref })
                chosen += weak.take((maximum - chosen.size).coerceAtLeast(0))
                chosen.forEach(::select)
                val selected = chosen.map { it.ref }.toSet()
                val eligibility = mutableMapOf<Set<String>, List<String>>()
                for (unit in values.filter { it.ref !in selected }) {
                    val eligible = eligibility.getOrPut(unit.files) {
                        chosen.filter { it.files.intersect(unit.files).isEmpty() }.map { it.ref }.sortedWith(stringOrder)
                    }
                    if (eligible.isNotEmpty()) destinations[unit.ref] = eligible else defer(listOf(unit), values)
                }
            }
        }
        val ordered = canonical.keys.sortedWith(stringOrder)
        val knownTargets = canonical.values.filter { it.targets.isNotEmpty() }.associate { it.row.occurrenceKey to it.targets }
        val resolved = if (active.isNotEmpty()) ordered.zip(CashSourceResolver.resolveSourceRows(
            ordered.map { canonical.getValue(it).row }, active, knownTargets)).toMap()
        else ordered.associateWith { SourceResolution(canonical.getValue(it).row.occurrenceKey, "new", bind = true) }
        val evaluations = java.util.IdentityHashMap<List<String>, Evaluation>()
        for (unit in units.values) {
            if (unit.ref in output) continue
            val options = destinations.getValue(unit.ref)
            val evaluation = evaluations.getOrPut(options) {
                val values = options.map { resolved.getValue(it) }
                val failed = values.any { it.action in setOf("conflict", "pending") }
                val ids = if (failed) values.flatMap { it.candidateIds + listOfNotNull(it.transactionId) }.distinct().sortedWith(stringOrder) else emptyList()
                val group = if (options != listOf(unit.ref) && (failed || options.size > 1)) sharedGroup(options, ids) else null
                Evaluation(values.singleOrNull(), failed, group)
            }
            if (options == listOf(unit.ref)) output[unit.ref] = result(unit.ref, requireNotNull(evaluation.only))
            for (ref in unit.refs) {
                if (ref in output) continue
                output[ref] = if (evaluation.failed) {
                    if (options == listOf(unit.ref)) result(ref, requireNotNull(evaluation.only)).copy(
                        duplicateOf = if (evaluation.only.action == "pending") unit.ref else null)
                    else SourceBatchResolution(ref, "pending", reason = "source_identity_ambiguous", groupRef = evaluation.group)
                } else SourceBatchResolution(ref, "batch_duplicate", transactionId = evaluation.only?.transactionId,
                    bind = evaluation.only?.bind == true, duplicateOf = if (evaluation.only != null) options.first() else null,
                    groupRef = evaluation.group)
            }
        }
        val usedGroups = output.values.mapNotNull { it.groupRef }.toSet()
        return SourceBatchPlan(rows.keys.map { output.getValue(it) }, resultGroups.filterKeys { it in usedGroups }.values.toList())
    }

    private fun contentValue(value: Any?): Any? = when (value) {
        is CashAmount -> decimalContent(value.representation.negative, value.representation.coefficient, value.representation.exponent)
        is BigDecimal -> decimalContent(value.signum() < 0, value.unscaledValue().abs().toString(), -value.scale().toLong())
        is Instant -> value.atOffset(java.time.ZoneOffset.UTC).toString().replace("Z", "+00:00")
        is Map<*, *> -> value.mapValues { contentValue(it.value) }
        is List<*> -> value.map(::contentValue)
        else -> value
    }
    private fun decimalContent(negative: Boolean, coefficient: String, exponent: Long): Map<String, Any> {
        val digits = coefficient.trimEnd('0')
        return mapOf("decimal" to listOf(if (digits.isEmpty()) 0 else if (negative) 1 else 0,
            digits.map { it.digitToInt() }, if (digits.isEmpty()) 0L else exponent + coefficient.length - digits.length))
    }
}
