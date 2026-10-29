package com.finance.tracker.backend

import java.time.Instant
import java.util.UUID

internal data class SourceRecord(
    val transactionId: String,
    val sourceType: String,
    val sourceAccountKey: String?,
    val fields: Map<String, Any?>,
    val coverages: List<CashSourceCoverage> = emptyList(),
    val bindingKeys: Set<String> = emptySet(),
    val protectedFields: Set<String> = emptySet(),
    val stableEvidence: String? = null,
    val providerId: String? = null,
    val providerBindingKey: String? = null,
    val stableEvidenceBindingKey: String? = null,
    val rowRevision: Int = 1,
    val deleted: Boolean = false,
    val fileOccurrences: Map<String, Set<String>> = emptyMap(),
    val documentEvidence: List<SourceDocumentEvidence> = emptyList(),
)

internal data class SourceResolution(
    val occurrenceKey: String,
    val action: String,
    val transactionId: String? = null,
    val candidateIds: List<String> = emptyList(),
    val reason: String = "",
    val bind: Boolean = false,
    val duplicateOf: String? = null,
)

internal data class DocumentPageProof(val sourceType: String, val parserVersion: String, val documentKey: String,
    val page: String)

/** 来源对应解析：只产生结果，不写账本。 */
internal object CashSourceResolver {
    fun resolveSourceFile(rows: List<SourceOccurrence>, records: List<SourceRecord>): List<SourceResolution> {
        if (rows.map { Triple(it.sourceType, it.fileDigest, it.parserVersion) }.distinct().size > 1)
            throw IllegalArgumentException("one_source_file_required")
        if (rows.map { it.occurrenceKey }.distinct().size != rows.size)
            throw IllegalArgumentException("duplicate_source_locator")
        return resolveSourceRows(rows, records)
    }

    fun resolveSourceRows(rows: List<SourceOccurrence>, records: List<SourceRecord>,
        targetsByOccurrence: Map<String, Set<String>> = emptyMap()): List<SourceResolution> {
        val active = records.filter { !it.deleted }
        val byId = active.associateBy { it.transactionId }
        val conflictingPages = conflictingSourceDocumentPages(rows, active)
        val byBinding = mutableMapOf<String, MutableList<SourceRecord>>()
        val byIndex = mutableMapOf<Triple<String, String?, Index>, MutableList<SourceRecord>>()
        val byUnscopedIndex = mutableMapOf<Pair<String, Index>, MutableList<SourceRecord>>()
        for (record in active) {
            for (key in recordBindingKeys(record)) byBinding.getOrPut(key) { mutableListOf() } += record
            val index = indexOf(record.fields) ?: continue
            byUnscopedIndex.getOrPut(record.sourceType to index) { mutableListOf() } += record
            if (record.sourceAccountKey.present()) byIndex.getOrPut(Triple(record.sourceType, record.sourceAccountKey, index)) {
                mutableListOf()
            } += record
            else if ((if ("state" in record.fields) record.fields["state"] else "posted") == "posted")
                byIndex.getOrPut(Triple(record.sourceType, null, index)) { mutableListOf() } += record
        }
        val results = linkedMapOf<String, SourceResolution>()
        val used = mutableSetOf<String>()
        val groups = linkedMapOf<Triple<String, String?, Index>, MutableList<SourceOccurrence>>()
        val certifiedRows = mutableListOf<SourceOccurrence>()
        val roots = mutableMapOf<String, String>()
        val proofOwner = mutableMapOf<String, String>()
        fun root(key: String): String {
            var current = key
            while (roots[current] != current) {
                roots[current] = roots[roots[current]!!]!!
                current = roots[current]!!
            }
            return current
        }
        for (row in rows) {
            val page = row.documentPage
            if (page != null && DocumentPageProof(row.sourceType, row.parserVersion, page.first, page.second) in conflictingPages) {
                results[row.occurrenceKey] = SourceResolution(row.occurrenceKey, "conflict", reason = "batch_identity_conflict")
                continue
            }
            try {
                CashRecordPolicy.prepare(mapOf("state" to "draft"), patch(row))
            } catch (failure: CashPolicyError) {
                results[row.occurrenceKey] = SourceResolution(row.occurrenceKey, "conflict", reason = failure.code)
                continue
            }
            val proofs = mutableListOf<String>()
            row.providerBindingKey?.let(proofs::add); row.stableEvidenceBindingKey?.let(proofs::add)
            for (proof in proofs.toList()) byBinding[proof].orEmpty().forEach { proofs += "transaction:" + it.transactionId }
            targetsByOccurrence[row.occurrenceKey].orEmpty().forEach { proofs += "transaction:$it" }
            if (proofs.isNotEmpty()) {
                roots[row.occurrenceKey] = row.occurrenceKey
                certifiedRows += row
                for (proof in proofs) {
                    val previous = proofOwner.getOrPut(proof) { row.occurrenceKey }
                    roots[root(row.occurrenceKey)] = root(previous)
                }
            }
        }
        val certifiedGroups = certifiedRows.groupBy { root(it.occurrenceKey) }
        for (values in certifiedGroups.values) {
            if (values.drop(1).any { patch(it) != patch(values.first()) } ||
                values.mapNotNull { it.providerBindingKey }.distinct().size > 1 ||
                values.mapNotNull { it.stableEvidenceBindingKey }.distinct().size > 1) {
                for (row in values)
                    results[row.occurrenceKey] = SourceResolution(row.occurrenceKey, "conflict", reason = "batch_identity_conflict")
            } else {
                for (row in values.drop(1))
                    results[row.occurrenceKey] = SourceResolution(row.occurrenceKey, "batch_duplicate",
                        duplicateOf = values.first().occurrenceKey)
            }
        }
        for (row in rows) {
            if (row.occurrenceKey in results) continue
            val proofKeys = row.bindingKeys.toMutableList()
            row.stableEvidenceBindingKey?.let(proofKeys::add)
            val targets = linkedMapOf<String, SourceRecord>()
            for (key in proofKeys) byBinding[key].orEmpty().forEach { targets[it.transactionId] = it }
            targetsByOccurrence[row.occurrenceKey].orEmpty().forEach { target ->
                targets[target] = byId[target] ?: throw IllegalStateException("unknown_source_target")
            }
            if (targets.isNotEmpty()) {
                if (targets.size == 1 && (row.providerBindingKey != null || targets.keys.first() !in used)) {
                    val target = targets.values.first()
                    val matched = matched(row, target)
                    results[row.occurrenceKey] = matched
                    if (matched.action != "conflict") used += target.transactionId
                } else {
                    results[row.occurrenceKey] = SourceResolution(row.occurrenceKey, "pending",
                        candidateIds = targets.keys.sorted(), reason = "source_identity_ambiguous")
                }
                continue
            }
            val index = indexOf(row.fields)
            if (index != null) groups.getOrPut(Triple(row.sourceType, row.sourceAccountKey, index)) { mutableListOf() } += row
            else results[row.occurrenceKey] = SourceResolution(row.occurrenceKey, "new", bind = true)
        }
        for ((key, members) in groups) {
            val scopedRecords = if (key.second.present())
                byIndex[key].orEmpty() + byIndex[Triple(key.first, null, key.third)].orEmpty()
            else byUnscopedIndex[key.first to key.third].orEmpty()
            val possible = scopedRecords.filter { record -> members.any { compatible(it, record) } }
            val candidates = possible.filter { it.transactionId !in used }
            if (candidates.isEmpty()) {
                for (row in members) {
                    val needsPending = possible.isNotEmpty() && (key.second == null ||
                        possible.any { !it.sourceAccountKey.present() } ||
                        possible.none { record -> record.coverages.any { row.coverage.comparable(it, row.fields["occurred_at"]) } })
                    results[row.occurrenceKey] = if (needsPending)
                        SourceResolution(row.occurrenceKey, "pending",
                            candidateIds = possible.map { it.transactionId }.sorted(), reason = "source_identity_ambiguous")
                    else SourceResolution(row.occurrenceKey, "new", bind = true)
                }
                continue
            }
            val comparable = when {
                key.second == null -> false
                members.none { it.providerId != null || it.stableEvidence != null } &&
                    members.all { it.coverage == members.first().coverage } ->
                    candidates.all { record -> record.sourceAccountKey.present() && record.coverages.any {
                        members.first().coverage.comparable(it, members.first().fields["occurred_at"] as? String)
                    } }
                else -> members.all { row -> candidates.all { record ->
                    record.sourceAccountKey.present() && compatible(row, record) &&
                        (row.stableEvidence != null && row.stableEvidence == record.stableEvidence ||
                            record.coverages.any { row.coverage.comparable(it, row.fields["occurred_at"]) })
                } }
            }
            if (comparable && members.size == 1 && candidates.size == 1) {
                results[members[0].occurrenceKey] = matched(members[0], candidates[0]); continue
            }
            if (comparable && unchangedAssignment(members, candidates)) {
                for (row in members) results[row.occurrenceKey] = SourceResolution(row.occurrenceKey, "duplicate")
                continue
            }
            val uniform = comparable && members.size >= candidates.size &&
                members.all { it.fields == members.first().fields } &&
                members.all { (it.providerId to it.stableEvidence) == (members.first().providerId to members.first().stableEvidence) } &&
                candidates.all { difference(members.first(), it).second.isEmpty() }
            if (uniform) {
                val sorted = candidates.sortedBy { it.transactionId }
                val changed = sorted.any { difference(members.first(), it).first }
                for ((index, row) in members.withIndex()) {
                    results[row.occurrenceKey] = if (changed && index < sorted.size) matched(row, sorted[index])
                    else SourceResolution(row.occurrenceKey, if (index < sorted.size) "duplicate" else "new",
                        bind = index >= sorted.size)
                }
                continue
            }
            val ids = candidates.map { it.transactionId }.sorted()
            for (row in members) results[row.occurrenceKey] = SourceResolution(row.occurrenceKey, "pending",
                candidateIds = ids, reason = "source_identity_ambiguous")
        }
        return rows.map { results.getValue(it.occurrenceKey) }
    }

    fun allocateSourceRecordId(row: SourceOccurrence): String =
        row.providerBindingKey?.let { "provider-v1:$it" } ?: "import-v1:${UUID.randomUUID()}"

    fun conflictingDocumentPages(coverages: List<CashSourceCoverage>): Set<Pair<String, String>> {
        val pages = mutableMapOf<Pair<String, String>, String>()
        val conflicting = mutableSetOf<Pair<String, String>>()
        for (coverage in coverages) {
            if (coverage.documentKey.isNullOrEmpty()) continue
            for ((page, fingerprint) in coverage.pageFingerprints) {
                val key = coverage.documentKey to page
                val previous = pages.putIfAbsent(key, fingerprint)
                if (previous != null && previous != fingerprint) conflicting += key
            }
        }
        return conflicting
    }

    fun conflictingSourceDocumentPages(rows: List<SourceOccurrence>, records: List<SourceRecord> = emptyList(),
        evidence: List<SourceDocumentEvidence> = emptyList()): Set<DocumentPageProof> {
        val proofs = mutableListOf<SourceDocumentEvidence>()
        rows.forEach { proofs += SourceDocumentEvidence(it.sourceType, it.parserVersion, it.coverage) }
        val namespaces = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        for (proof in proofs) {
            val key = proof.coverage.documentKey
            if (!key.isNullOrEmpty()) namespaces.getOrPut(proof.sourceType to key) { mutableSetOf() } += proof.parserVersion
        }
        for (record in records) {
            if (record.documentEvidence.isNotEmpty()) proofs += record.documentEvidence
            else for (coverage in record.coverages) {
                val key = coverage.documentKey ?: continue
                val versions = namespaces[record.sourceType to key].orEmpty()
                if (versions.size == 1) proofs += SourceDocumentEvidence(record.sourceType, versions.first(), coverage)
            }
        }
        proofs += evidence
        val groups = mutableMapOf<DocumentNamespace, MutableList<CashSourceCoverage>>()
        for (proof in proofs) if (!proof.coverage.documentKey.isNullOrEmpty())
            groups.getOrPut(proof.namespace) { mutableListOf() } += proof.coverage
        return groups.flatMap { (namespace, coverages) ->
            conflictingDocumentPages(coverages).map { DocumentPageProof(namespace.sourceType, namespace.parserVersion,
                it.first, it.second) }
        }.toSet()
    }

    private fun indexOf(fields: Map<String, Any?>): Index? {
        val amount = fields["amount"] ?: return null
        val currency = fields["currency"] as? String ?: return null
        val occurred = fields["occurred_at"] ?: return null
        val cash = CashAmount.parseOrNull(amount.toString()) ?: return null
        val time = try { pythonCashTimestamp(occurred) } catch (_: Exception) { return null }
        val value = java.math.BigDecimal(cash.representation.coefficient.ifEmpty { "0" })
            .scaleByPowerOfTen(cash.representation.exponent.toInt()).stripTrailingZeros().toPlainString()
        return Index(time, if (cash.representation.negative && value != "0") "-$value" else value, currency)
    }

    private fun patch(row: SourceOccurrence): Map<String, Any?> =
        row.fields.filterKeys { it in mutableCashFields || it in financialCashFields }

    private fun difference(row: SourceOccurrence, old: SourceRecord): Pair<Boolean, String> = try {
        val update = CashRecordPolicy.prepare(old.fields, patch(row), old.protectedFields)
        (update.values.isNotEmpty()) to ""
    } catch (failure: CashPolicyError) {
        true to failure.code
    }

    private fun matched(row: SourceOccurrence, old: SourceRecord): SourceResolution {
        if ((row.sourceAccountKey != null && old.sourceAccountKey.present() &&
                row.sourceAccountKey != old.sourceAccountKey) || !compatible(row, old))
            return SourceResolution(row.occurrenceKey, "conflict", transactionId = old.transactionId,
                reason = "source_identity_conflict")
        val (changed, error) = difference(row, old)
        return SourceResolution(row.occurrenceKey, if (error.isNotEmpty()) "conflict" else if (changed) "match" else "duplicate",
            transactionId = old.transactionId, reason = error, bind = error.isEmpty())
    }

    private fun distinctPositions(row: SourceOccurrence, old: SourceRecord): Boolean {
        val occurrence = row.documentOccurrenceKey
        if (occurrence != null) {
            if (old.fileOccurrences[row.documentScopeKey]?.let { occurrence !in it } == true) return true
        }
        if (row.providerBindingKey != null && (row.providerBindingKey == old.providerBindingKey ||
                row.providerBindingKey in old.bindingKeys) ||
            row.stableEvidenceBindingKey != null && (row.stableEvidenceBindingKey == old.stableEvidenceBindingKey ||
                row.stableEvidenceBindingKey in old.bindingKeys)) return false
        return old.fileOccurrences[row.fileScopeKey]?.let { row.occurrenceKey !in it } == true
    }

    private fun compatible(row: SourceOccurrence, old: SourceRecord): Boolean = !(
        distinctPositions(row, old) ||
            row.providerId != null && old.providerId.present() && row.providerId != old.providerId ||
            row.providerBindingKey != null && old.providerBindingKey.present() && row.providerBindingKey != old.providerBindingKey ||
            row.stableEvidence != null && old.stableEvidence.present() && row.stableEvidence != old.stableEvidence ||
            row.stableEvidenceBindingKey != null && old.stableEvidenceBindingKey.present() &&
            row.stableEvidenceBindingKey != old.stableEvidenceBindingKey
        )

    private fun unchangedAssignment(rows: List<SourceOccurrence>, old: List<SourceRecord>): Boolean {
        if (rows.size > old.size) return false
        val commonProtected = if (old.isEmpty()) emptySet() else old.map { it.protectedFields }.reduce { a, b -> a intersect b }
        val compared = rows.map { row -> patch(row).filterKeys { it !in commonProtected } }
        if (rows.isNotEmpty() && compared.all { it == compared.first() })
            return old.count { difference(rows.first(), it) == (false to "") } >= rows.size
        val byPayload = mutableMapOf<String, MutableList<Int>>()
        for ((index, record) in old.withIndex())
            byPayload.getOrPut(sourceDigest(record.fields["source_payload"])) { mutableListOf() } += index
        val edges = rows.map { row ->
            val candidates = if ("source_payload" in row.fields && "source_payload" !in commonProtected)
                byPayload[sourceDigest(row.fields["source_payload"])].orEmpty() else old.indices.toList()
            candidates.filter { index -> compatible(row, old[index]) && difference(row, old[index]) == (false to "") }
        }
        val matchedOld = mutableMapOf<Int, Int>()
        val matchedRows = mutableMapOf<Int, Int>()
        for (first in rows.indices) {
            val queue = ArrayDeque<Int>().apply { add(first) }
            val seenRows = mutableSetOf(first)
            val previous = mutableMapOf<Int, Int>()
            var free: Int? = null
            while (queue.isNotEmpty() && free == null) {
                val rowIndex = queue.removeFirst()
                for (oldIndex in edges[rowIndex]) {
                    if (oldIndex in previous) continue
                    previous[oldIndex] = rowIndex
                    if (oldIndex !in matchedOld) { free = oldIndex; break }
                    val following = matchedOld[oldIndex]!!
                    if (following !in seenRows) { seenRows += following; queue += following }
                }
            }
            if (free == null) return false
            var current: Int? = free
            while (current != null) {
                val rowIndex = previous[current]!!
                val nextFree = matchedRows[rowIndex]
                matchedOld[current] = rowIndex
                matchedRows[rowIndex] = current
                current = nextFree
            }
        }
        return true
    }

    private fun recordBindingKeys(record: SourceRecord): Set<String> {
        val keys = record.bindingKeys.toMutableSet()
        if (record.providerId.present() && record.sourceAccountKey.present())
            keys += sourceDigest(record.sourceType, "account", record.sourceAccountKey, "provider_id",
                providerIdentityKey(record.providerId!!))
        record.providerBindingKey?.takeIf { it.isNotEmpty() }?.let(keys::add)
        if (record.stableEvidence.present() && record.sourceAccountKey.present())
            keys += sourceDigest(record.sourceType, record.sourceAccountKey, "verified", record.stableEvidence!!)
        record.stableEvidenceBindingKey?.takeIf { it.isNotEmpty() }?.let(keys::add)
        return keys
    }

    private fun String?.present(): Boolean = !this.isNullOrEmpty()

    private data class Index(val occurredAt: Instant, val amount: String, val currency: String)
}
