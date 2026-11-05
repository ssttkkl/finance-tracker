package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigInteger
import kotlinx.serialization.json.*

internal fun cashSourceCoverageFields(value: CashSourceCoverage): Map<String, Any?> = linkedMapOf(
    "start" to value.start, "end" to value.end, "complete" to value.complete, "evidence" to value.evidence,
    "filters" to value.filters, "start_inclusive" to value.startInclusive, "end_inclusive" to value.endInclusive,
    "time_precision" to value.timePrecision, "strategy_version" to value.strategyVersion, "document_key" to value.documentKey,
    "page_total" to value.pageTotal, "page_fingerprints" to value.pageFingerprints)

internal fun cashSourceDeclaration(value: CashSourceCoverage): Map<String, Any?> =
    cashSourceCoverageFields(value).filterKeys { it !in setOf("complete", "evidence", "page_fingerprints") }

internal data class CashSourceIssueWrite(val id: Long, val revision: Long)

/** 调用方管理工作区锁与事务；所有身份和候选校验先于本次替换。 */
internal class CashSourceWriteRepository(private val context: CashSqlContext) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace
    private val binding = CASH_SOURCE_BINDINGS
    private val transaction = CASH_TRANSACTIONS
    private fun fail(code: String): Nothing = throw CashSourceBindingConflict(code)
    private fun integer(value: Any?): BigInteger? = when (value) {
        is Byte, is Short, is Int, is Long, is BigInteger -> BigInteger(value.toString())
        else -> null
    }
    private fun identifier(value: Any?): Long {
        val number = if (value is String && value.isNotEmpty() && value.all { it in '0'..'9' }) value.toBigInteger() else integer(value)
        return number?.takeIf { it.signum() > 0 && it < BigInteger.ONE.shiftLeft(63) }?.toLong() ?: fail("source_target_unavailable")
    }
    private fun target(value: Any?, includeSource: Boolean = false): org.jooq.Record {
        context.requireActive()
        val fields = listOf(transaction.ID, transaction.ROW_REVISION) + if (includeSource)
            listOf(transaction.STATE, transaction.SOURCE_TYPE, transaction.SOURCE_PAYLOAD, transaction.RECORD_ID) else emptyList()
        return sql.select(fields).from(transaction).where(transaction.WORKSPACE_ID.eq(workspace)
            .and(transaction.ID.eq(identifier(value))).and(transaction.DELETED_AT.isNull)).fetchOne()
            ?: fail("source_target_unavailable")
    }
    private fun sameJson(first: JsonElement?, second: JsonElement?): Boolean =
        CashRecordPolicy.valuesEqual(cashSourceValue(first), cashSourceValue(second))

    fun independentIdentitySnapshot(transactionId: Long): Map<String, Any?> = identitySnapshot(transactionId)

    fun validateDraftMerge(sourceId: Long, targetId: Long): Map<String, Any?> {
        val source = target(sourceId, includeSource = true); val destination = target(targetId, includeSource = true)
        if (source[transaction.STATE] != "draft" || sourceId == targetId || source[transaction.SOURCE_TYPE] != destination[transaction.SOURCE_TYPE])
            fail("source_merge_requires_draft")
        val all = sql.selectFrom(binding).where(binding.WORKSPACE_ID.eq(workspace)).and(binding.TRANSACTION_ID.`in`(sourceId, targetId)).fetch()
        val scopes = linkedSetOf<String>(); val providers = linkedSetOf<String>(); val evidence = linkedSetOf<String>()
        for (parent in listOf(source, destination)) {
            val current = all.filter { it[binding.TRANSACTION_ID] == parent[transaction.ID] }
            val localScopes = current.filter { it[binding.SOURCE_SCOPE_KIND] == "account" }.map { it[binding.SOURCE_SCOPE_KEY]!! }.toMutableSet()
            val localProviders = current.filter { it[binding.IDENTITY_KIND] == "provider_id" }.map { it[binding.IDENTITY_KEY]!! }.toMutableSet()
            scopes += localScopes; providers += localProviders
            evidence += current.mapNotNull { it[binding.STABLE_EVIDENCE_KEY]?.takeIf(String::isNotEmpty) }
        }
        if (scopes.size > 1 || providers.size > 1 || evidence.size > 1) fail("source_identity_conflict")
        val positions = linkedMapOf<String, MutableSet<String>>()
        val pages = all.map { row ->
            val coverage = cashSourceCoverage(row[binding.COVERAGE]); val sourceType = row[binding.SOURCE_TYPE]!!; val parser = row[binding.STRATEGY_VERSION]!!
            if (coverage.documentKey != null && row[binding.SOURCE_SCOPE_KIND] == "file_local" && row[binding.IDENTITY_KIND] == "file_occurrence" &&
                row[binding.SOURCE_SCOPE_KEY] == sourceDigest("source-document-v1", sourceType, parser, coverage.documentKey))
                positions.getOrPut(row[binding.SOURCE_SCOPE_KEY]!!) { linkedSetOf() }.add(row[binding.IDENTITY_KEY]!!)
            SourceDocumentEvidence(sourceType, parser, coverage)
        }
        if (positions.values.any { it.size > 1 }) fail("source_identity_conflict")
        if (CashSourceResolver.conflictingSourceDocumentPages(emptyList(), evidence = pages).isNotEmpty()) fail("source_document_conflict")
        val same = setOf(sourceId, targetId)
        return mapOf(sourceId.toString() to identitySnapshot(sourceId, same), targetId.toString() to identitySnapshot(targetId, same))
    }

    fun transferDraftBindings(sourceId: Long, targetId: Long) {
        val identities = validateDraftMerge(sourceId, targetId)
        if ((identities.getValue(sourceId.toString()) as Map<*, *>)["count"] == 0) return
        context.connection.markLedgerChanged(workspace)
        sql.update(binding).set(binding.TRANSACTION_ID, targetId).set(binding.REVISION, binding.REVISION.plus(1L))
            .set(binding.UPDATED_AT, context.now()).where(binding.WORKSPACE_ID.eq(workspace)).and(binding.TRANSACTION_ID.eq(sourceId)).execute()
    }

    private fun identitySnapshot(transactionId: Long, sameIdentity: Set<Long> = emptySet()): Map<String, Any?> {
        val parent = target(transactionId, includeSource = true)
        val bindings = sql.selectFrom(binding).where(binding.WORKSPACE_ID.eq(workspace))
            .and(binding.TRANSACTION_ID.eq(transactionId)).orderBy(binding.ID).fetch()
        val account = bindings.filter { it[binding.SOURCE_SCOPE_KIND] == "account" }
        if (bindings.any { it[binding.SOURCE_TYPE] != parent[transaction.SOURCE_TYPE] } ||
            account.map { it[binding.SOURCE_SCOPE_KEY] }.distinct().size > 1 ||
            account.filter { it[binding.IDENTITY_KIND] == "provider_id" }.map { it[binding.IDENTITY_KEY] }.distinct().size > 1 ||
            bindings.mapNotNull { it[binding.STABLE_EVIDENCE_KEY]?.takeIf(String::isNotEmpty) }.distinct().size > 1)
            fail("source_identity_conflict")
        val positions = linkedMapOf<String, MutableSet<String>>()
        val evidence = bindings.map { row ->
            val coverage = cashSourceCoverage(row[binding.COVERAGE])
            val source = row[binding.SOURCE_TYPE]!!; val parser = row[binding.STRATEGY_VERSION]!!
            if (coverage.documentKey != null && row[binding.SOURCE_SCOPE_KIND] == "file_local" &&
                row[binding.IDENTITY_KIND] == "file_occurrence" && row[binding.SOURCE_SCOPE_KEY] ==
                sourceDigest("source-document-v1", source, parser, coverage.documentKey))
                positions.getOrPut(row[binding.SOURCE_SCOPE_KEY]!!) { linkedSetOf() }.add(row[binding.IDENTITY_KEY]!!)
            SourceDocumentEvidence(source, parser, coverage)
        }
        if (positions.values.any { it.size > 1 }) fail("source_identity_conflict")
        if (CashSourceResolver.conflictingSourceDocumentPages(emptyList(), evidence = evidence).isNotEmpty()) fail("source_document_conflict")
        val keys = bindings.map { row -> SourceBindingKey(row[binding.SOURCE_TYPE]!!, row[binding.SOURCE_SCOPE_KIND]!!,
            row[binding.SOURCE_SCOPE_KEY]!!, row[binding.IDENTITY_KIND]!!, row[binding.IDENTITY_KEY]!!) }.distinct()
        val others = CashSourceReadRepository(context).forKeys(keys, bindings.mapNotNull {
            it[binding.STABLE_EVIDENCE_KEY]?.takeIf(String::isNotEmpty)
        }.distinct()).map { it.transactionId }.filter { it != transactionId && it !in sameIdentity }.distinct()
        for (batch in others.chunked(500)) if (sql.fetchExists(transaction, transaction.WORKSPACE_ID.eq(workspace)
            .and(transaction.ID.`in`(batch)).and(transaction.DELETED_AT.isNull))) fail("active_source_binding")
        val keyFields = listOf(binding.SOURCE_TYPE, binding.SOURCE_SCOPE_KIND, binding.SOURCE_SCOPE_KEY, binding.IDENTITY_KIND, binding.IDENTITY_KEY)
        val sorted = bindings.sortedWith { first, second ->
            keyFields.firstNotNullOfOrNull { field -> pythonStringOrder(first[field]!!, second[field]!!).takeIf { it != 0 } }
                ?: first[binding.ID]!!.compareTo(second[binding.ID]!!)
        }
        val facts = sorted.map { row ->
            val created = row[binding.CREATED_AT]!!.atOffset(java.time.ZoneOffset.UTC)
            val time = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").format(created) +
                (if (created.nano == 0) "" else "." + (created.nano / 1000).toString().padStart(6, '0')) + "+00:00"
            mapOf("id" to row[binding.ID], "created_at" to time, "transaction_id" to row[binding.TRANSACTION_ID],
                "source_type" to row[binding.SOURCE_TYPE], "source_scope_kind" to row[binding.SOURCE_SCOPE_KIND],
                "source_scope_key" to row[binding.SOURCE_SCOPE_KEY], "identity_kind" to row[binding.IDENTITY_KIND],
                "identity_key" to row[binding.IDENTITY_KEY], "revision" to row[binding.REVISION],
                "identity_strength" to row[binding.IDENTITY_STRENGTH], "stable_evidence_key" to row[binding.STABLE_EVIDENCE_KEY],
                "strategy_version" to row[binding.STRATEGY_VERSION], "coverage" to cashSourceValue(row[binding.COVERAGE]))
        }
        return mapOf("count" to facts.size, "digest" to sourceDigest(facts))
    }
    private fun json(value: Any?, code: String): JsonElement = try {
        sourceJson(CashRecordPolicy.normalize("source_payload", value))
    } catch (_: CashPolicyError) { fail(code) }
    catch (_: InvalidCashValue) { fail(code) }

    private fun validateScope(parent: org.jooq.Record, row: SourceOccurrence) {
        val current = sql.selectFrom(binding).where(binding.WORKSPACE_ID.eq(workspace)
            .and(binding.TRANSACTION_ID.eq(parent[transaction.ID]))).fetch()
        val scopes = current.filter { it[binding.SOURCE_SCOPE_KIND] == "account" }.map { it[binding.SOURCE_SCOPE_KEY]!! }.toMutableSet()
        val providers = current.filter { it[binding.IDENTITY_KIND] == "provider_id" }.map { it[binding.IDENTITY_KEY]!! }.toMutableSet()
        val evidence = current.mapNotNull { it[binding.STABLE_EVIDENCE_KEY]?.takeIf(String::isNotEmpty) }.toSet()
        val incomingProvider = row.bindingSpecs.firstOrNull { it.identityKind == "provider_id" }?.identityKey
        if (parent[transaction.SOURCE_TYPE] != row.sourceType || row.sourceAccountKey != null && scopes.isNotEmpty() && scopes != setOf(row.sourceAccountKey) ||
            incomingProvider != null && providers.isNotEmpty() && incomingProvider !in providers ||
            row.stableEvidenceBindingKey != null && evidence.isNotEmpty() && row.stableEvidenceBindingKey !in evidence) fail("source_identity_conflict")
        val positions = current.filter { existing ->
            val coverage = cashSourceCoverage(existing[binding.COVERAGE])
            existing[binding.SOURCE_SCOPE_KIND] == "file_local" && existing[binding.IDENTITY_KIND] == "file_occurrence" &&
                coverage.documentKey != null && existing[binding.SOURCE_SCOPE_KEY] ==
                sourceDigest("source-document-v1", existing[binding.SOURCE_TYPE], existing[binding.STRATEGY_VERSION], coverage.documentKey) &&
                existing[binding.SOURCE_SCOPE_KEY] == row.documentScopeKey
        }.map { it[binding.IDENTITY_KEY]!! }.toSet()
        if (row.documentOccurrenceKey != null && positions.isNotEmpty() && positions != setOf(row.documentOccurrenceKey)) fail("source_identity_conflict")
    }

    fun saveOccurrence(transactionId: Any?, row: SourceOccurrence, uncertain: Boolean = false, firstBinding: Boolean = false): Boolean {
        val parent = target(transactionId, includeSource = true)
        val parentId = parent[transaction.ID]!!
        if (parent[transaction.SOURCE_TYPE] != row.sourceType || uncertain && parent[transaction.STATE] != "draft") fail("source_target_incompatible")
        if (!firstBinding) validateScope(parent, row)
        row.documentPage?.let { (_, page) ->
            val empty = CASH_SOURCE_EMPTY_PAGES
            val old = sql.select(empty.COVERAGE).from(empty).where(empty.WORKSPACE_ID.eq(workspace)
                .and(empty.SOURCE_TYPE.eq(row.sourceType)).and(empty.PARSER_VERSION.eq(row.parserVersion))
                .and(empty.DOCUMENT_KEY.eq(row.coverage.documentKey)).and(empty.PAGE_NUMBER.eq(page.toLong()))).fetchOne(empty.COVERAGE)
            if (old != null) {
                val coverage = cashSourceCoverage(old)
                if (coverage.pageFingerprints[page] != row.coverage.pageFingerprints[page] ||
                    cashSourceDeclaration(coverage) != cashSourceDeclaration(row.coverage)) fail("source_document_conflict")
            }
            val existing = sql.select(binding.COVERAGE).from(binding).join(transaction)
                .on(transaction.WORKSPACE_ID.eq(binding.WORKSPACE_ID)).and(transaction.ID.eq(binding.TRANSACTION_ID))
                .where(binding.WORKSPACE_ID.eq(workspace).and(transaction.DELETED_AT.isNull)
                    .and(transaction.RECORD_ORIGIN.`in`("import", "legacy")).and(binding.SOURCE_TYPE.eq(row.sourceType))
                    .and(binding.SOURCE_SCOPE_KIND.eq("file_local")).and(binding.SOURCE_SCOPE_KEY.eq(row.documentPageScopeKey))).fetch(binding.COVERAGE)
            if (existing.any { value -> cashSourceCoverage(value).pageFingerprints[page]?.let { it != row.coverage.pageFingerprints[page] } == true })
                fail("source_document_conflict")
        }
        val found = CashSourceReadRepository(context).forKeys(row.bindingSpecs, listOfNotNull(row.stableEvidenceBindingKey))
        val otherIds = found.map { it.transactionId }.filter { it != parentId }.distinct()
        for (batch in otherIds.chunked(400)) if (sql.fetchExists(transaction, transaction.WORKSPACE_ID.eq(workspace)
            .and(transaction.ID.`in`(batch)).and(transaction.DELETED_AT.isNull))) fail("active_source_binding")
        val coverage = row.documentPage?.let { (_, page) -> row.coverage.copy(pageFingerprints = mapOf(page to row.coverage.pageFingerprints.getValue(page))) }
            ?: row.coverage
        val encoded = json(cashSourceCoverageFields(coverage), "invalid_source_coverage")
        val byKey = found.associateBy { listOf(it.sourceType, it.scopeKind, it.scopeKey, it.identityKind, it.identityKey) }
        var changed = false
        for (spec in row.bindingSpecs) {
            val old = byKey[listOf(spec.sourceType, spec.scopeKind, spec.scopeKey, spec.identityKind, spec.identityKey)]
            val strength = if (spec.scopeKind == "file_local") "file_local" else if (spec.identityKind == "provider_id") "provider" else "validated"
            if (old == null) {
                context.connection.markLedgerChanged(workspace)
                sql.insertInto(binding).set(binding.WORKSPACE_ID, workspace).set(binding.TRANSACTION_ID, parentId)
                    .set(binding.SOURCE_TYPE, spec.sourceType).set(binding.SOURCE_SCOPE_KIND, spec.scopeKind).set(binding.SOURCE_SCOPE_KEY, spec.scopeKey)
                    .set(binding.IDENTITY_KIND, spec.identityKind).set(binding.IDENTITY_KEY, spec.identityKey).set(binding.IDENTITY_STRENGTH, strength)
                    .set(binding.STABLE_EVIDENCE_KEY, if (spec.scopeKind == "account") row.stableEvidenceBindingKey else null)
                    .set(binding.STRATEGY_VERSION, row.parserVersion).set(binding.COVERAGE, encoded).set(binding.REVISION, 1L)
                    .set(binding.CREATED_AT, context.now()).set(binding.UPDATED_AT, context.now()).execute()
                changed = true
            } else {
                val saved = sql.selectFrom(binding).where(binding.WORKSPACE_ID.eq(workspace).and(binding.ID.eq(old.id))).fetchSingle()
                val evidence = if (spec.scopeKind == "account" && row.stableEvidenceBindingKey != null) row.stableEvidenceBindingKey else saved[binding.STABLE_EVIDENCE_KEY]
                if (old.transactionId != parentId || saved[binding.IDENTITY_STRENGTH] != strength || old.strategyVersion != row.parserVersion ||
                    evidence != saved[binding.STABLE_EVIDENCE_KEY] || !sameJson(saved[binding.COVERAGE], encoded)) {
                    context.connection.markLedgerChanged(workspace)
                    sql.update(binding).set(binding.TRANSACTION_ID, parentId).set(binding.IDENTITY_STRENGTH, strength)
                        .set(binding.STRATEGY_VERSION, row.parserVersion).set(binding.COVERAGE, encoded).set(binding.STABLE_EVIDENCE_KEY, evidence)
                        .set(binding.REVISION, Math.addExact(saved[binding.REVISION]!!, 1L)).set(binding.UPDATED_AT, context.now())
                        .where(binding.WORKSPACE_ID.eq(workspace).and(binding.ID.eq(old.id))).execute()
                    changed = true
                }
            }
        }
        return changed
    }

    fun saveIssue(transactionId: Any?, issueKey: Any?, code: Any?, fieldPath: String? = null,
        pendingSourcePayload: Any? = null, pendingFields: Any? = null, origin: Any? = null,
        candidates: Map<*, *>? = null): CashSourceIssueWrite {
        val parent = target(transactionId)[transaction.ID]!!
        if (code !is String || !Regex("[a-z][a-z0-9_]{0,63}").matches(code) || issueKey !is String ||
            issueKey.isEmpty() || issueKey.codePointCount(0, issueKey.length) > 128) fail("invalid_source_issue")
        val payload = json(pendingSourcePayload, "invalid_source_issue")
        val fields = json(pendingFields, "invalid_source_issue")
        val rawOrigin = when (origin) {
            null, false -> emptyMap<String, Any?>()
            is String -> if (origin.isEmpty()) emptyMap<String, Any?>() else origin
            is Collection<*> -> if (origin.isEmpty()) emptyMap<String, Any?>() else origin
            is Number -> if (origin.toDouble() == 0.0) emptyMap<String, Any?>() else origin
            else -> origin
        }
        val location = json(rawOrigin, "invalid_source_issue") as? JsonObject ?: fail("invalid_source_issue")
        if ((location.keys - setOf("file_digest", "table_key", "position", "source_type", "source_scope_kind", "source_scope_key", "parser_version")).isNotEmpty())
            fail("invalid_source_issue")
        val desired = linkedMapOf<Long, Long>()
        for ((id, revision) in candidates.orEmpty()) {
            val candidate = target(id)
            if (candidate[transaction.ID] == parent || integer(revision) != BigInteger.valueOf(candidate[transaction.ROW_REVISION]!!)) fail("source_candidate_changed")
            desired[candidate[transaction.ID]!!] = candidate[transaction.ROW_REVISION]!!
        }
        val issue = CASH_TRANSACTION_ISSUES
        val candidate = CASH_ISSUE_CANDIDATES
        val old = sql.selectFrom(issue).where(issue.WORKSPACE_ID.eq(workspace).and(issue.TRANSACTION_ID.eq(parent)).and(issue.ISSUE_KEY.eq(issueKey))).fetchOne()
        val previous = if (old == null) emptyMap() else sql.selectFrom(candidate)
            .where(candidate.WORKSPACE_ID.eq(workspace).and(candidate.ISSUE_ID.eq(old[issue.ID])))
            .fetch().associate { it[candidate.CANDIDATE_TRANSACTION_ID]!! to it[candidate.EXPECTED_ROW_REVISION]!! }
        val changed = old != null && (previous != desired || old[issue.CODE] != code || old[issue.FIELD_PATH] != fieldPath ||
            !sameJson(old[issue.PENDING_SOURCE_PAYLOAD], payload) || !sameJson(old[issue.PENDING_FIELDS], fields) || !sameJson(old[issue.ORIGIN], location))
        val revision = if (old == null) 1L else if (changed) Math.addExact(old[issue.REVISION]!!, 1L) else old[issue.REVISION]!!
        if (old == null || changed) context.connection.markLedgerChanged(workspace)
        val id = if (old == null) {
            sql.insertInto(issue).set(issue.WORKSPACE_ID, workspace).set(issue.TRANSACTION_ID, parent).set(issue.ISSUE_KEY, issueKey)
                .set(issue.CODE, code).set(issue.FIELD_PATH, fieldPath).set(issue.PENDING_SOURCE_PAYLOAD, payload).set(issue.PENDING_FIELDS, fields)
                .set(issue.ORIGIN, location).set(issue.REVISION, revision).set(issue.CREATED_AT, context.now()).set(issue.UPDATED_AT, context.now())
                .returningResult(issue.ID).fetchSingle().value1()!!
        } else {
            if (changed) sql.update(issue).set(issue.CODE, code).set(issue.FIELD_PATH, fieldPath).set(issue.PENDING_SOURCE_PAYLOAD, payload)
                .set(issue.PENDING_FIELDS, fields).set(issue.ORIGIN, location).set(issue.REVISION, revision).set(issue.UPDATED_AT, context.now())
                .where(issue.WORKSPACE_ID.eq(workspace).and(issue.ID.eq(old[issue.ID]))).execute()
            old[issue.ID]!!
        }
        if (previous != desired) {
            sql.deleteFrom(candidate).where(candidate.WORKSPACE_ID.eq(workspace).and(candidate.ISSUE_ID.eq(id))).execute()
            for ((target, expected) in desired) sql.insertInto(candidate).set(candidate.WORKSPACE_ID, workspace).set(candidate.ISSUE_ID, id)
                .set(candidate.CANDIDATE_TRANSACTION_ID, target).set(candidate.EXPECTED_ROW_REVISION, expected).execute()
        }
        return CashSourceIssueWrite(id, revision)
    }

    fun clearIssue(transactionId: Any?, issueKey: String) {
        context.requireActive()
        val table = CASH_TRANSACTION_ISSUES
        val condition = table.WORKSPACE_ID.eq(workspace).and(table.TRANSACTION_ID.eq(identifier(transactionId))).and(table.ISSUE_KEY.eq(issueKey))
        if (!sql.fetchExists(table, condition)) return
        context.connection.markLedgerChanged(workspace)
        sql.deleteFrom(table).where(condition).execute()
    }
}
