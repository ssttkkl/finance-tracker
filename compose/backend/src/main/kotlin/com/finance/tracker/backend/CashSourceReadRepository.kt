package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.*
import org.jooq.Condition
import org.jooq.impl.DSL

internal class CashSourceBindingConflict(val code: String) : RuntimeException(code)

/** 当前来源绑定的只读行；覆盖证据按原 Python 数据类边界解码。 */
internal data class CashSourceBindingRow(
    val id: Long, val transactionId: Long, val sourceType: String, val scopeKind: String, val scopeKey: String,
    val identityKind: String, val identityKey: String, val stableEvidenceKey: String?, val strategyVersion: String,
    val coverage: CashSourceCoverage,
) {
    val bindingKey: String get() = sourceDigest(sourceType, scopeKind, scopeKey, identityKind, identityKey)
}

private val coverageKeys = setOf("start", "end", "complete", "evidence", "filters", "start_inclusive", "end_inclusive",
    "time_precision", "strategy_version", "document_key", "page_total", "page_fingerprints")

/** 与 Python `SourceCoverage(**coverage)` 相同：缺省字段取默认值，未知键或损坏结构停止读取。 */
internal fun cashSourceCoverage(value: JsonElement?): CashSourceCoverage {
    val item = value as? JsonObject ?: return CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1")
    if (item.keys.any { it !in coverageKeys }) throw CashRecordReadUnavailable(IllegalStateException("invalid source coverage"))
    fun text(key: String): String? = (item[key]?.takeIf { it != JsonNull } as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun flag(key: String, default: Boolean): Boolean = item[key]?.takeIf { it != JsonNull }?.let {
        (it as? JsonPrimitive)?.booleanOrNull ?: throw CashRecordReadUnavailable(IllegalStateException("invalid source coverage"))
    } ?: default
    fun filters(): Map<String, String> {
        val raw = item["filters"]?.takeIf { it != JsonNull } ?: return emptyMap()
        val objectValue = raw as? JsonObject ?: throw CashRecordReadUnavailable(IllegalStateException("invalid source coverage"))
        return objectValue.mapValues { (_, item) ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw CashRecordReadUnavailable(IllegalStateException("invalid source coverage"))
        }
    }
    fun fingerprints(): Map<String, String> {
        val raw = item["page_fingerprints"]?.takeIf { it != JsonNull } ?: return emptyMap()
        val objectValue = raw as? JsonObject ?: throw CashRecordReadUnavailable(IllegalStateException("invalid source coverage"))
        return objectValue.mapValues { (_, item) ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw CashRecordReadUnavailable(IllegalStateException("invalid source coverage"))
        }
    }
    val pageTotal = item["page_total"]?.takeIf { it != JsonNull }?.let {
        (it as? JsonPrimitive)?.takeIf { value -> !value.isString && value.booleanOrNull == null }?.content?.toIntOrNull()
            ?: throw CashRecordReadUnavailable(IllegalStateException("invalid source coverage"))
    }
    return try {
        CashSourceCoverage(text("start"), text("end"), flag("complete", false), text("evidence") ?: "",
            filters(), text("strategy_version") ?: "source-coverage-v1", flag("start_inclusive", true),
            flag("end_inclusive", false), text("time_precision") ?: "second", text("document_key"), pageTotal, fingerprints())
    } catch (_: IllegalArgumentException) {
        throw CashRecordReadUnavailable(IllegalStateException("invalid source coverage"))
    }
}

/** 规划所需当前来源流水及认证空页；只读，不写绑定或问题。 */
internal class CashSourceReadRepository(private val context: CashSqlContext) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace
    private val binding = CASH_SOURCE_BINDINGS
    private val bindingFields = listOf(binding.ID, binding.TRANSACTION_ID, binding.SOURCE_TYPE, binding.SOURCE_SCOPE_KIND,
        binding.SOURCE_SCOPE_KEY, binding.IDENTITY_KIND, binding.IDENTITY_KEY, binding.STABLE_EVIDENCE_KEY,
        binding.STRATEGY_VERSION, binding.COVERAGE)

    private fun bindingRow(row: org.jooq.Record) = CashSourceBindingRow(row[binding.ID]!!, row[binding.TRANSACTION_ID]!!,
        row[binding.SOURCE_TYPE]!!, row[binding.SOURCE_SCOPE_KIND]!!, row[binding.SOURCE_SCOPE_KEY]!!,
        row[binding.IDENTITY_KIND]!!, row[binding.IDENTITY_KEY]!!, row[binding.STABLE_EVIDENCE_KEY],
        row[binding.STRATEGY_VERSION]!!, cashSourceCoverage(row[binding.COVERAGE]))

    /** 每片最多 125 个身份，避免 SQLite 参数上限；未命中时不逐片往返。 */
    fun forKeys(keys: List<SourceBindingKey>, evidenceKeys: List<String>): List<CashSourceBindingRow> {
        if (keys.isEmpty() && evidenceKeys.isEmpty()) return emptyList()
        if (keys.size + evidenceKeys.size > 125 &&
            !sql.fetchExists(binding, binding.WORKSPACE_ID.eq(workspace))) return emptyList()
        val result = linkedMapOf<Long, CashSourceBindingRow>()
        val size = maxOf(keys.size, evidenceKeys.size)
        var offset = 0
        while (offset < size) {
            for ((scope, identities) in keys.drop(offset).take(125).groupBy {
                listOf(it.sourceType, it.scopeKind, it.scopeKey, it.identityKind)
            }) {
                sql.select(bindingFields).from(binding).where(binding.WORKSPACE_ID.eq(workspace)
                    .and(binding.SOURCE_TYPE.eq(scope[0])).and(binding.SOURCE_SCOPE_KIND.eq(scope[1]))
                    .and(binding.SOURCE_SCOPE_KEY.eq(scope[2])).and(binding.IDENTITY_KIND.eq(scope[3]))
                    .and(binding.IDENTITY_KEY.`in`(identities.map { it.identityKey }.distinct().sorted())))
                    .orderBy(binding.ID).fetch().forEach { row -> bindingRow(row).let { result[it.id] = it } }
            }
            val evidence = evidenceKeys.drop(offset).take(125)
            if (evidence.isNotEmpty()) sql.select(bindingFields).from(binding)
                .where(binding.WORKSPACE_ID.eq(workspace).and(binding.STABLE_EVIDENCE_KEY.`in`(evidence.sorted())))
                .orderBy(binding.ID).fetch().forEach { row -> bindingRow(row).let { result[it.id] = it } }
            offset += 125
        }
        return result.values.toList()
    }

    private fun forOccurrences(rows: Collection<SourceOccurrence>): List<CashSourceBindingRow> =
        forKeys(rows.flatMap { it.bindingSpecs }.distinctBy { listOf(it.sourceType, it.scopeKind, it.scopeKey, it.identityKind, it.identityKey) }
            .sortedWith(compareBy({ it.sourceType }, { it.scopeKind }, { it.scopeKey }, { it.identityKind }, { it.identityKey })),
            rows.mapNotNull { it.stableEvidenceBindingKey }.distinct().sorted())

    private fun legacyProvider(parent: org.jooq.Record): String? {
        val payload = parent[CASH_TRANSACTIONS.SOURCE_PAYLOAD]
        val raw = (payload as? JsonObject)?.get("交易单号") as? JsonPrimitive ?: return null
        if (!raw.isString) return null
        val value = raw.content
        val stripped = value.trim { it.code in pythonWhitespace }
        if (parent[CASH_TRANSACTIONS.SOURCE_TYPE] != "wechat" || stripped.isEmpty() || stripped in setOf("/", "-", "--") ||
            stripped.codePointCount(0, stripped.length) > 512 || parent[CASH_TRANSACTIONS.RECORD_ID] != "wechat_$value") return null
        return stripped
    }

    private fun positions(rows: List<CashSourceBindingRow>, documentOnly: Boolean = true): Map<String, Set<String>> {
        val result = linkedMapOf<String, MutableSet<String>>()
        for (row in rows) {
            if (row.scopeKind != "file_local" || row.identityKind != "file_occurrence") continue
            if (documentOnly) {
                val document = row.coverage.documentKey ?: continue
                if (row.scopeKey != sourceDigest("source-document-v1", row.sourceType, row.strategyVersion, document)) continue
            }
            result.getOrPut(row.scopeKey) { linkedSetOf() }.add(row.identityKey)
        }
        return result
    }

    private fun occurredAt(fields: Map<String, Any?>): Instant? {
        val value = fields["occurred_at"] ?: return null
        if (value is Instant) return value
        if (value is OffsetDateTime) return value.toInstant()
        if (value !is String) return null
        if (!Regex("(?:[zZ]|[+-][0-9]{2}:?[0-9]{2})$").containsMatchIn(value)) return null
        return runCatching { pythonCashTimestamp(value) }.getOrNull()
    }

    /** 直接绑定和同时间/旧微信候选；不以内容散列或内部账户建立身份。 */
    fun recordsForFile(rows: Collection<SourceOccurrence>): List<SourceRecord> {
        if (rows.isEmpty()) return emptyList()
        val directIds = forOccurrences(rows).map { it.transactionId }.distinct().sorted()
        val sourceTypes = rows.map { it.sourceType }.distinct().sortedWith(::pythonStringOrder).toSet()
        val legacyNumbers = rows.filter { it.sourceType == "wechat" &&
            it.sourceAccountKey == CashSourceScope.WECHAT_DEFAULT_SCOPE && !it.providerId.isNullOrEmpty() }
            .mapNotNull { it.providerId }.distinct().sortedWith(::pythonStringOrder)
        val times = rows.mapNotNull { occurredAt(it.fields) }.distinct().sorted()
        val tx = CASH_TRANSACTIONS
        val byId = linkedMapOf<Long, org.jooq.Record>()
        val maximum = maxOf(directIds.size, times.size, legacyNumbers.size)
        var offset = 0
        while (offset < maximum) {
            val conditions = mutableListOf<Condition>()
            val ids = directIds.drop(offset).take(250)
            if (ids.isNotEmpty()) conditions += tx.ID.`in`(ids)
            val moments = times.drop(offset).take(250)
            if (moments.isNotEmpty()) conditions += tx.OCCURRED_AT.`in`(moments)
            val numbers = legacyNumbers.drop(offset).take(250)
            if (numbers.isNotEmpty()) {
                val trimmed = if (context.sqlite)
                    DSL.function("trim", String::class.java, DSL.substring(tx.RECORD_ID, 8), DSL.inline(idWhitespace))
                else DSL.function("btrim", String::class.java, DSL.substring(tx.RECORD_ID, 8), DSL.inline(idWhitespace))
                conditions += tx.SOURCE_TYPE.eq("wechat").and(tx.RECORD_ID.like("wechat!_%", '!')).and(trimmed.`in`(numbers))
            }
            if (conditions.isNotEmpty()) sql.selectFrom(tx).where(tx.WORKSPACE_ID.eq(workspace)
                .and(tx.DELETED_AT.isNull).and(tx.RECORD_ORIGIN.`in`("import", "legacy"))
                .and(tx.SOURCE_TYPE.`in`(sourceTypes)).and(DSL.or(*conditions.toTypedArray())))
                .orderBy(tx.ID).fetch().forEach { byId[it[tx.ID]!!] = it }
            offset += 250
        }
        if (byId.isEmpty()) return emptyList()
        val ids = byId.keys.sorted()
        val bindings = linkedMapOf<Long, MutableList<CashSourceBindingRow>>()
        val children = linkedMapOf<Long, MutableList<org.jooq.Record>>()
        for (batch in ids.chunked(400)) {
            sql.select(bindingFields).from(binding).where(binding.WORKSPACE_ID.eq(workspace)
                .and(binding.TRANSACTION_ID.`in`(batch))).orderBy(binding.ID).fetch()
                .forEach { row -> bindingRow(row).let { bindings.getOrPut(it.transactionId) { mutableListOf() } += it } }
            sql.select(CASH_TRANSACTION_COMPONENTS.ID, CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID,
                CASH_TRANSACTION_COMPONENTS.ACCOUNT_ID, CASH_TRANSACTION_COMPONENTS.AMOUNT,
                CASH_TRANSACTION_COMPONENTS.CURRENCY, CASH_TRANSACTION_COMPONENTS.ORDINAL)
                .from(CASH_TRANSACTION_COMPONENTS).where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(workspace)
                    .and(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID.`in`(batch)))
                .orderBy(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID, CASH_TRANSACTION_COMPONENTS.ORDINAL,
                    CASH_TRANSACTION_COMPONENTS.ID).fetch()
                .forEach { row -> children.getOrPut(row[CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID]!!) { mutableListOf() } += row }
        }
        return byId.values.map { parent ->
            val aliases = bindings[parent[tx.ID]] ?: emptyList()
            val scopes = aliases.filter { it.scopeKind == "account" }.map { it.scopeKey }.toSet()
            if (scopes.size > 1) throw CashSourceBindingConflict("source_binding_ambiguous")
            val keys = aliases.map { SourceBindingKey(it.sourceType, it.scopeKind, it.scopeKey, it.identityKind, it.identityKey) }
            val verified = if (scopes.isEmpty()) aliases else aliases.filter { it.scopeKind == "account" && it.scopeKey in scopes }
            val legacy = if (scopes.isEmpty()) legacyProvider(parent) else null
            val fields = linkedMapOf<String, Any?>(
                "state" to parent[tx.STATE], "amount" to parent[tx.AMOUNT]?.text?.toBigDecimal(),
                "occurred_at" to parent[tx.OCCURRED_AT], "currency" to parent[tx.CURRENCY],
                "account_id" to parent[tx.ACCOUNT_ID], "cash_granularity" to parent[tx.CASH_GRANULARITY],
                "counterparty" to parent[tx.COUNTERPARTY], "counterparty_account" to parent[tx.COUNTERPARTY_ACCOUNT],
                "counterparty_account_attrs" to cashSourceValue(parent[tx.COUNTERPARTY_ACCOUNT_ATTRS]),
                "note" to parent[tx.NOTE], "category_id" to parent[tx.CATEGORY_ID],
                "record_type" to parent[tx.RECORD_TYPE], "record_subtype" to parent[tx.RECORD_SUBTYPE],
                "source_payload" to cashSourceValue(parent[tx.SOURCE_PAYLOAD]),
                "components" to (children[parent[tx.ID]] ?: emptyList()).map { child ->
                    linkedMapOf<String, Any?>(
                        "component_id" to child[CASH_TRANSACTION_COMPONENTS.ID],
                        "account_id" to child[CASH_TRANSACTION_COMPONENTS.ACCOUNT_ID],
                        "amount" to child[CASH_TRANSACTION_COMPONENTS.AMOUNT]?.text?.toBigDecimal(),
                        "currency" to child[CASH_TRANSACTION_COMPONENTS.CURRENCY],
                        "ordinal" to child[CASH_TRANSACTION_COMPONENTS.ORDINAL],
                    )
                })
            SourceRecord(
                transactionId = parent[tx.ID]!!.toString(), sourceType = parent[tx.SOURCE_TYPE]!!,
                sourceAccountKey = scopes.firstOrNull() ?: if (legacy != null) CashSourceScope.WECHAT_DEFAULT_SCOPE else null,
                fields = fields, coverages = verified.map { it.coverage }, bindingKeys = keys.map { it.key }.toSet(),
                fileOccurrences = positions(aliases, documentOnly = false),
                providerId = legacy,
                providerBindingKey = keys.firstOrNull { it.identityKind == "provider_id" }?.key,
                stableEvidenceBindingKey = aliases.mapNotNull { it.stableEvidenceKey?.takeIf(String::isNotEmpty) }.firstOrNull(),
                rowRevision = parent[tx.ROW_REVISION]!!.toInt(),
                documentEvidence = verified.filter { it.coverage.documentKey != null }.map {
                    SourceDocumentEvidence(it.sourceType, it.strategyVersion, it.coverage)
                })
        }
    }
}

/** 认证空页与已绑定文档证据的只读读取；不写绑定。 */
internal class CashSourceDocumentReadRepository(private val context: CashSqlContext) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace
    private val binding = CASH_SOURCE_BINDINGS
    private val empty = CASH_SOURCE_EMPTY_PAGES

    fun read(files: List<ParsedImportFile> = emptyList(),
        candidateEvidence: List<SourceDocumentEvidence> = emptyList()): List<SourceDocumentEvidence> {
        val evidence = CashSourceRows.fileDocumentEvidence(files) + candidateEvidence
        val namespaces = evidence.mapNotNull { item -> item.coverage.documentKey?.let { item.namespace } }
            .distinct().sortedWith(compareBy<DocumentNamespace>({ it.sourceType }, { it.parserVersion }, { it.documentKey ?: "" }))
        val result = mutableListOf<SourceDocumentEvidence>()
        for (batch in namespaces.chunked(150)) {
            val scopes = batch.map { (source, parser, document) ->
                listOf(source, parser, sourceDigest("source-document-v1", source, parser, document))
            }
            var scopeCondition: Condition = DSL.falseCondition()
            for (scope in scopes) scopeCondition = scopeCondition.or(binding.SOURCE_TYPE.eq(scope[0])
                .and(binding.STRATEGY_VERSION.eq(scope[1])).and(binding.SOURCE_SCOPE_KEY.eq(scope[2])))
            sql.select(binding.SOURCE_TYPE, binding.STRATEGY_VERSION, binding.COVERAGE)
                .from(binding).join(CASH_TRANSACTIONS).on(
                    CASH_TRANSACTIONS.WORKSPACE_ID.eq(binding.WORKSPACE_ID)
                        .and(CASH_TRANSACTIONS.ID.eq(binding.TRANSACTION_ID)))
                .where(binding.WORKSPACE_ID.eq(workspace).and(CASH_TRANSACTIONS.DELETED_AT.isNull)
                    .and(binding.SOURCE_SCOPE_KIND.eq("file_local")).and(binding.IDENTITY_KIND.eq("file_occurrence"))
                    .and(scopeCondition))
                .fetch().forEach { row ->
                    result += SourceDocumentEvidence(row[binding.SOURCE_TYPE]!!, row[binding.STRATEGY_VERSION]!!,
                        cashSourceCoverage(row[binding.COVERAGE]))
                }
            var emptyCondition: Condition = DSL.falseCondition()
            for (item in batch) emptyCondition = emptyCondition.or(empty.SOURCE_TYPE.eq(item.sourceType)
                .and(empty.PARSER_VERSION.eq(item.parserVersion)).and(empty.DOCUMENT_KEY.eq(item.documentKey ?: "")))
            sql.select(empty.SOURCE_TYPE, empty.PARSER_VERSION, empty.COVERAGE).from(empty)
                .where(empty.WORKSPACE_ID.eq(workspace).and(emptyCondition))
                .fetch().forEach { row ->
                    result += SourceDocumentEvidence(row[empty.SOURCE_TYPE]!!, row[empty.PARSER_VERSION]!!,
                        cashSourceCoverage(row[empty.COVERAGE]))
                }
        }
        return result
    }
}

private val idWhitespace: String = buildString {
    append(" \t\n\r\u000B\u000C\u001C\u001D\u001E\u001F\u0085\u00A0\u1680")
    for (code in 0x2000..0x200A) append(code.toChar())
    append("\u2028\u2029\u202F\u205F\u3000")
}
