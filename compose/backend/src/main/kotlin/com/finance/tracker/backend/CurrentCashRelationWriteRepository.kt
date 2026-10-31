package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigInteger
import java.time.Instant
import java.util.IdentityHashMap
import kotlinx.serialization.json.*
import org.jooq.Field
import org.jooq.Record

/** 当前角色合同；调用方负责工作区锁、完整图和外层事务。 */
internal class CurrentCashRelationWriteRepository(private val context: CashSqlContext) {
    private val relation = TRANSACTION_RELATIONS
    private val candidate = CASH_RELATION_CANDIDATES
    private val parent = CASH_TRANSACTIONS
    private val component = CASH_TRANSACTION_COMPONENTS
    private val fields = listOf("kind", "subtype", "state", "active", "primary_transaction_id", "secondary_transaction_id",
        "primary_component_id", "secondary_component_id", "anchor_transaction_id", "anchor_component_id", "primary_role", "secondary_role",
        "applied_amount", "decision_group_id", "decision_source", "evidence", "rule_id", "created_by", "decided_by", "decided_at", "decision_reason")
    private val ids = listOf("primary_transaction_id", "secondary_transaction_id", "primary_component_id", "secondary_component_id", "anchor_transaction_id", "anchor_component_id")
    private fun fail(code: String = "relation_data_invalid"): Nothing = relationFailure(code)
    private fun integer(value: Any?): BigInteger? = when (value) {
        is Byte, is Short, is Int, is Long, is BigInteger -> value.toString().toBigInteger()
        else -> null
    }
    private fun id(value: Any?): Long {
        val number = if (value is String && value.length in 1..19 && value.all { it in '0'..'9' }) value.toBigInteger() else integer(value)
        return number?.takeIf { it.signum() > 0 && it < BigInteger.ONE.shiftLeft(63) }?.toLong() ?: fail()
    }
    private fun mapping(value: Any?): Map<String, Any?> {
        if (value !is Map<*, *> || value.keys.any { it !is String }) fail()
        @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
    }
    private fun evidence(value: Any?): JsonObject {
        if (value is JsonObject) return evidence(cashSourceValue(value))
        if (value !is Map<*, *>) fail()
        val active = IdentityHashMap<Any, Boolean>()
        var bytes = 0L
        fun count(size: Int) { bytes += size; if (bytes > 64 * 1024) fail() }
        fun primitive(value: JsonElement): JsonElement {
            if (value is JsonPrimitive && !value.isString && value.content.trimStart('-').length > 4300) fail()
            count(receiptAsciiJson(value).length)
            return value
        }
        fun encode(value: Any?): JsonElement = when (value) {
            null, is String, is Boolean, is Byte, is Short, is Int, is Long, is BigInteger -> primitive(sourceJson(value))
            is Double -> if (value.isFinite()) primitive(sourceJson(value)) else fail()
            is Float -> if (value.isFinite()) primitive(sourceJson(value)) else fail()
            is Map<*, *> -> {
                if (active.put(value, true) != null) fail()
                count(2 + maxOf(0, value.size - 1) * 2)
                val result = JsonObject(value.entries.associate { (key, item) ->
                    val text = when (key) {
                        is String -> key
                        null -> "null"
                        is Boolean -> key.toString()
                        is Byte, is Short, is Int, is Long, is BigInteger -> key.toString()
                        is Double -> if (key.isFinite()) pythonFloatText(key) else fail()
                        is Float -> if (key.isFinite()) pythonFloatText(key.toDouble()) else fail()
                        else -> fail()
                    }
                    count(receiptAsciiJson(JsonPrimitive(text)).length + 2)
                    text to encode(item)
                })
                active.remove(value)
                result
            }
            is List<*> -> {
                if (active.put(value, true) != null) fail()
                count(2 + maxOf(0, value.size - 1) * 2)
                val result = JsonArray(value.map { encode(it) })
                active.remove(value)
                result
            }
            else -> fail()
        }
        return encode(value) as JsonObject
    }
    private fun model(identifier: Any?): Record {
        context.requireActive()
        return context.sql.selectFrom(relation).where(relation.WORKSPACE_ID.eq(context.workspace).and(relation.ID.eq(id(identifier))))
            .fetchOne() ?: fail("relation_not_found")
    }
    fun get(identifier: Any?): Map<String, Any?> {
        val row = model(identifier)
        val candidates = context.sql.selectFrom(candidate).where(candidate.WORKSPACE_ID.eq(context.workspace)
            .and(candidate.RELATION_ID.eq(row[relation.ID]))).orderBy(candidate.CANDIDATE_TRANSACTION_ID, candidate.CANDIDATE_COMPONENT_ID).fetch().map { item ->
                listOf("candidate_transaction_id", "candidate_component_id", "expected_row_revision", "evidence", "validity").associateWith { item.get(it) }
            }
        return linkedMapOf("id" to row[relation.ID], "workspace_id" to context.workspace, "row_revision" to row[relation.ROW_REVISION],
            "created_at" to row[relation.CREATED_AT]) + fields.associateWith { row.get(it) } + ("candidates" to candidates)
    }
    private fun origin(rows: Collection<Record>, kind: String) {
        if (rows.any { it[parent.RECORD_ORIGIN] == "manual" }) fail("manual_relation_forbidden")
        val transfers = rows.filter { it[parent.RECORD_ORIGIN] == "manual_transfer" }
        if (transfers.isNotEmpty() && (kind != "transfer_pair" || rows.size != 2 || transfers.size != 2 ||
                transfers[0][parent.MANUAL_TRANSFER_ID].isNullOrEmpty() || transfers[0][parent.MANUAL_TRANSFER_ID] != transfers[1][parent.MANUAL_TRANSFER_ID]))
            fail("manual_relation_forbidden")
    }
    private fun values(raw: Any?): Map<String, Any?> {
        val input = mapping(raw)
        if ((input.keys - fields.toSet()).isNotEmpty()) fail()
        val result = linkedMapOf<String, Any?>("subtype" to "", "state" to "draft", "active" to true, "secondary_transaction_id" to null,
            "primary_component_id" to null, "secondary_component_id" to null, "anchor_component_id" to null, "primary_role" to null,
            "secondary_role" to null, "decision_group_id" to null, "decision_source" to "rule", "evidence" to emptyMap<String, Any?>(),
            "applied_amount" to "0", "rule_id" to "", "created_by" to "system", "decided_by" to "", "decided_at" to null, "decision_reason" to "")
        result.putAll(input)
        if (result["kind"] !in setOf("payment_mirror", "refund_offset", "transfer_pair") || result["state"] !in setOf("draft", "confirmed", "ignored") || result["active"] !is Boolean) fail()
        ids.forEach { result[it] = result[it]?.let(::id) }
        if (result["primary_transaction_id"] == null || result["anchor_transaction_id"] == null) fail("relation_endpoint_incomplete")
        for ((key, maximum) in mapOf("subtype" to 64, "decision_source" to 32, "rule_id" to 128, "created_by" to 128, "decided_by" to 128, "decision_reason" to 4096)) {
            val text = result[key] as? String ?: fail()
            if (text.codePointCount(0, text.length) > maximum) fail()
        }
        for (key in listOf("primary_role", "secondary_role")) if (result[key] != null && result[key] !in setOf("expense", "refund", "p2p_out", "p2p_in", "outgoing", "incoming")) fail()
        result["decision_group_id"]?.let { if (it !is String || it.codePointCount(0, it.length) !in 1..128) fail() }
        if (result["decided_at"] != null && result["decided_at"] !is Instant) fail()
        result["evidence"] = evidence(result["evidence"])
        val amount = result["applied_amount"].let { if (it is CashStoredDecimal) it.text else it.toString() }
        val normalized = try { exactCashAmount(amount) } catch (_: IllegalArgumentException) { fail() }
        if (normalized.arithmeticValue().signum() < 0) fail()
        result["applied_amount"] = CashStoredDecimal(amount)
        val parents = context.sql.selectFrom(parent).where(parent.WORKSPACE_ID.eq(context.workspace)
            .and(parent.ID.`in`(listOfNotNull(result["primary_transaction_id"] as Long?, result["secondary_transaction_id"] as Long?, result["anchor_transaction_id"] as Long?))))
            .fetch().associateBy { it[parent.ID]!! }
        val parts = context.sql.selectFrom(component).where(component.WORKSPACE_ID.eq(context.workspace)
            .and(component.ID.`in`(listOfNotNull(result["primary_component_id"] as Long?, result["secondary_component_id"] as Long?, result["anchor_component_id"] as Long?))))
            .fetch().associateBy { it[component.ID]!! }
        for (role in listOf("primary", "secondary", "anchor")) {
            val p = result["${role}_transaction_id"] as Long?
            val c = result["${role}_component_id"] as Long?
            if (p != null && p !in parents || c != null && (c !in parts || parts.getValue(c)[component.CASH_TRANSACTION_ID] != p)) fail("relation_endpoint_incomplete")
        }
        if (result["secondary_transaction_id"] == result["primary_transaction_id"] || result["secondary_transaction_id"] == null && result["secondary_role"] != null ||
            result["anchor_transaction_id"] !in setOf(result["primary_transaction_id"], result["secondary_transaction_id"]) ||
            result["anchor_component_id"] != null && result["anchor_component_id"] !in setOf(result["primary_component_id"], result["secondary_component_id"])) fail("relation_endpoint_incompatible")
        if (result["active"] == true && result["state"] != "ignored") origin(parents.values, result["kind"] as String)
        if (result["state"] == "confirmed" && result["active"] == true) {
            fun endpoint(role: String): CashRelationEndpoint {
                val p = parents[result["${role}_transaction_id"]] ?: fail("relation_endpoint_incomplete")
                val c = parts[result["${role}_component_id"]] ?: fail("relation_endpoint_incomplete")
                if (result["anchor_component_id"] == null) fail("relation_endpoint_incomplete")
                return CashRelationEndpoint(c[component.ID].toString(), p[parent.ID].toString(), c[component.AMOUNT]?.let { CashDecimalLiteral(it.text) },
                    c[component.CURRENCY], c[component.ACCOUNT_ID]?.toString(), p[parent.RECORD_TYPE], p[parent.RECORD_SUBTYPE], p[parent.STATE], p[parent.DELETED_AT] == null)
            }
            validateCashRelationRoles(result["kind"], result["subtype"], endpoint("primary"), endpoint("secondary"), result["primary_role"], result["secondary_role"])
        }
        return result
    }
    private fun fields(values: Map<String, Any?>): Map<Field<*>, Any?> = values.mapKeys { relation.field(it.key)!! }
    fun add(raw: Any?): Map<String, Any?> {
        context.requireActive()
        val identifier = context.sql.insertInto(relation).set(fields(values(raw)) + mapOf(relation.WORKSPACE_ID to context.workspace,
            relation.ROW_REVISION to 1L, relation.CREATED_AT to context.now())).returningResult(relation.ID).fetchSingle().value1()!!
        return get(identifier)
    }
    private fun checkRevision(row: Record, expected: Any?): Long = integer(expected)?.takeIf { it == row[relation.ROW_REVISION]!!.toBigInteger() }
        ?.toLong() ?: fail("relation_revision_changed")
    fun replace(identifier: Any?, raw: Any?, expectedRevision: Any?): Map<String, Any?> {
        val row = model(identifier)
        val revision = checkRevision(row, expectedRevision)
        val changes = mapping(raw)
        if ((changes.keys - fields.toSet()).isNotEmpty()) fail()
        val values = values(fields.associateWith { row.get(it) } + changes)
        val count = context.sql.update(relation).set(fields(values)).set(relation.ROW_REVISION, Math.addExact(revision, 1L))
            .where(relation.WORKSPACE_ID.eq(context.workspace).and(relation.ID.eq(row[relation.ID])).and(relation.ROW_REVISION.eq(revision))).execute()
        if (count != 1) fail("relation_revision_changed")
        if (values["state"] != "draft" || values["active"] != true) context.sql.deleteFrom(candidate).where(candidate.WORKSPACE_ID.eq(context.workspace).and(candidate.RELATION_ID.eq(row[relation.ID]))).execute()
        return get(row[relation.ID])
    }
    fun setCandidates(identifier: Any?, raw: Any?, expectedRevision: Any?): Map<String, Any?> {
        val row = model(identifier)
        val revision = checkRevision(row, expectedRevision)
        if (row[relation.STATE] != "draft" || row[relation.ACTIVE] != true || raw !is List<*> || raw.size > 100) fail()
        data class Item(val parentId: Long, val componentId: Long?, val revision: Long, val value: Map<String, Any?>)
        val keys = mutableSetOf<Pair<Long, Long?>>()
        val entries = raw.map { value ->
            val item = mapping(value)
            if ((item.keys - setOf("candidate_transaction_id", "candidate_component_id", "expected_row_revision", "evidence", "validity")).isNotEmpty()) fail()
            val p = id(item["candidate_transaction_id"])
            val c = item["candidate_component_id"]?.let(::id)
            val r = integer(item["expected_row_revision"])?.takeIf { it.signum() > 0 && it <= Long.MAX_VALUE.toBigInteger() }?.toLong() ?: fail()
            if (!keys.add(p to c)) fail()
            if (p == row[relation.ANCHOR_TRANSACTION_ID]) fail("relation_endpoint_incompatible")
            Item(p, c, r, item)
        }
        val parents = context.sql.selectFrom(parent).where(parent.WORKSPACE_ID.eq(context.workspace).and(parent.ID.`in`(entries.map { it.parentId } + row[relation.ANCHOR_TRANSACTION_ID]!!))).fetch().associateBy { it[parent.ID]!! }
        val parts = context.sql.selectFrom(component).where(component.WORKSPACE_ID.eq(context.workspace).and(component.ID.`in`(entries.mapNotNull { it.componentId }))).fetch().associateBy { it[component.ID]!! }
        if (entries.isNotEmpty()) origin(parents.values, row[relation.KIND]!!)
        val prepared = entries.map { item ->
            val p = parents[item.parentId] ?: fail("relation_endpoint_incomplete")
            if (item.componentId != null && parts[item.componentId]?.get(component.CASH_TRANSACTION_ID) != item.parentId) fail("relation_endpoint_incomplete")
            var validity = item.value.getOrDefault("validity", "valid")
            if (validity !in setOf("valid", "incomplete", "stale")) fail()
            if (p[parent.ROW_REVISION] != item.revision || p[parent.DELETED_AT] != null) validity = "stale"
            else if (item.componentId == null || p[parent.STATE] != "posted") validity = "incomplete"
            mapOf<Field<*>, Any?>(candidate.WORKSPACE_ID to context.workspace, candidate.RELATION_ID to row[relation.ID],
                candidate.CANDIDATE_TRANSACTION_ID to item.parentId, candidate.CANDIDATE_COMPONENT_ID to item.componentId,
                candidate.EXPECTED_ROW_REVISION to item.revision, candidate.EVIDENCE to evidence(item.value.getOrDefault("evidence", emptyMap<String, Any?>())), candidate.VALIDITY to validity)
        }
        if (context.sql.update(relation).set(relation.ROW_REVISION, Math.addExact(revision, 1L)).where(relation.WORKSPACE_ID.eq(context.workspace)
                .and(relation.ID.eq(row[relation.ID])).and(relation.ROW_REVISION.eq(revision))).execute() != 1) fail("relation_revision_changed")
        context.sql.deleteFrom(candidate).where(candidate.WORKSPACE_ID.eq(context.workspace).and(candidate.RELATION_ID.eq(row[relation.ID]))).execute()
        prepared.forEach { context.sql.insertInto(candidate).set(it).execute() }
        return get(row[relation.ID])
    }
}
