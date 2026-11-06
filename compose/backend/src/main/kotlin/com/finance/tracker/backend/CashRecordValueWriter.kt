package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import kotlinx.serialization.json.*
import org.jooq.Field
import org.jooq.Record

internal class CashRecordValueWriter(private val context: CashSqlContext) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?): Map<String, Any?> = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?): List<Map<String, Any?>> = value as List<Map<String, Any?>>
    private fun id(value: Any?): Long? = value?.toString()?.toLong()
    private fun amount(value: Any?): CashStoredDecimal? = value?.let { CashStoredDecimal(it.toString()) }

    fun saveParent(operation: Map<String, Any?>, values: Map<String, Any?>, parentId: Long?, parent: Record?, sourceChanged: Boolean): Long {
        val fact = CASH_TRANSACTIONS
        val manual = (operation["manual_fields"] as List<*>).map { it.toString() }
        val updates = linkedMapOf<Field<*>, Any?>()
        if (sourceChanged) {
            val fields = mapOf("state" to fact.STATE, "source_type" to fact.SOURCE_TYPE, "record_id" to fact.RECORD_ID,
                "counterparty" to fact.COUNTERPARTY, "counterparty_account" to fact.COUNTERPARTY_ACCOUNT,
                "counterparty_account_attrs" to fact.COUNTERPARTY_ACCOUNT_ATTRS, "note" to fact.NOTE, "category_id" to fact.CATEGORY_ID,
                "record_type" to fact.RECORD_TYPE, "record_subtype" to fact.RECORD_SUBTYPE, "source_payload" to fact.SOURCE_PAYLOAD,
                "amount" to fact.AMOUNT, "currency" to fact.CURRENCY, "occurred_at" to fact.OCCURRED_AT,
                "account_id" to fact.ACCOUNT_ID, "cash_granularity" to fact.CASH_GRANULARITY)
            for ((key, field) in fields) if (key in values) updates[field] = when (key) {
                "account_id" -> id(values[key])
                "amount" -> amount(values[key])
                "source_payload", "counterparty_account_attrs" -> sourceJson(values[key])
                else -> values[key]
            }
            val overrides = (parent?.get(fact.MANUAL_OVERRIDES) as? JsonObject).orEmpty().toMutableMap()
            manual.forEach { overrides[it] = JsonPrimitive(true) }
            updates[fact.MANUAL_OVERRIDES] = JsonObject(overrides)
            if ("source_explanation" in operation && map(operation["pending_fields"]).isEmpty()) updates[fact.RELATION_METADATA] = sourceJson(operation["source_explanation"])
            if (parentId != null) updates[fact.ROW_REVISION] = Math.addExact(parent!![fact.ROW_REVISION]!!, 1L)
        }
        return if (parentId == null) {
            val defaults = linkedMapOf<Field<*>, Any?>(fact.WORKSPACE_ID to workspace, fact.RECORD_ORIGIN to "import", fact.STATE to "posted",
                fact.CASH_GRANULARITY to "atomic", fact.ROW_REVISION to 1L, fact.SOURCE_TYPE to null, fact.RECORD_ID to "",
                fact.SOURCE_PAYLOAD to JsonNull, fact.RELATION_METADATA to JsonNull, fact.MANUAL_OVERRIDES to JsonObject(emptyMap()),
                fact.SOURCE_FINGERPRINT to null, fact.MANUAL_TRANSFER_ID to null, fact.ACCOUNT_ID to null, fact.AMOUNT to null,
                fact.CURRENCY to null, fact.OCCURRED_AT to null, fact.COUNTERPARTY to "", fact.COUNTERPARTY_ACCOUNT to "",
                fact.COUNTERPARTY_ACCOUNT_ATTRS to JsonArray(emptyList()), fact.NOTE to "", fact.CATEGORY_ID to null,
                fact.RECORD_TYPE to null, fact.RECORD_SUBTYPE to null, fact.CREATED_AT to context.now(), fact.DELETED_AT to null,
                fact.DELETED_BY to "", fact.DELETE_REASON to "")
            context.connection.markLedgerChanged(workspace)
            sql.insertInto(fact).set(defaults + updates).returningResult(fact.ID).fetchSingle().value1()!!
        } else {
            if (updates.isNotEmpty()) {
                context.connection.markLedgerChanged(workspace)
                check(sql.update(fact).set(updates).where(fact.WORKSPACE_ID.eq(workspace).and(fact.ID.eq(parentId))).execute() == 1)
            }
            parentId
        }
    }

    fun saveComponents(parent: Long, operation: Map<String, Any?>, values: Map<String, Any?>,
        before: Map<String, Any?>?, existing: Boolean, wasPosted: Boolean, beforeDeleteComponents: (Set<Long>) -> Unit): Map<String, String> {
        val virtual = maps(operation["preview_components"]).associate { it["ordinal"].toString().toLong() to it["component_id"].toString() }
        val previous = before?.get("components")?.let(::maps).orEmpty()
        val identifiers = previous.map { it["component_id"] }
        if (wasPosted && values["state"] == "posted" && before?.get("state") == "posted" && parent.toString() == operation["transaction_id"] &&
            CashRecordPolicy.valuesEqual(previous, map(operation["values"])["components"]) && identifiers.all { it != null } && identifiers.toSet().size == identifiers.size)
            return previous.associate { virtual.getValue(it["ordinal"].toString().toLong()) to it["component_id"].toString() }
        val part = CASH_TRANSACTION_COMPONENTS
        val parts = if (existing) sql.selectFrom(part).where(part.WORKSPACE_ID.eq(workspace).and(part.CASH_TRANSACTION_ID.eq(parent))).fetch().associateBy { it[part.ID]!! }
            else emptyMap()
        val desired = maps(values["components"])
        val wanted = desired.mapNotNull { id(it["component_id"]) }.toSet()
        val removed = parts.keys - wanted
        if (removed.isNotEmpty()) {
            context.connection.markLedgerChanged(workspace)
            beforeDeleteComponents(removed)
            for (batch in removed.sorted().chunked(500)) sql.deleteFrom(part).where(part.WORKSPACE_ID.eq(workspace).and(part.ID.`in`(batch))).execute()
        }
        val moving = desired.mapNotNull { value -> id(value["component_id"])?.let { parts[it] }?.takeIf { it[part.ORDINAL] != id(value["ordinal"]) } }
        if (moving.isNotEmpty()) {
            context.connection.markLedgerChanged(workspace)
            val blocked = (parts.values.map { it[part.ORDINAL]!! } + desired.map { id(it["ordinal"])!! }).toMutableSet()
            var temporary = 0L
            for (row in moving) {
                while (temporary in blocked) temporary++
                sql.update(part).set(part.ORDINAL, temporary).where(part.WORKSPACE_ID.eq(workspace).and(part.ID.eq(row[part.ID]))).execute()
                blocked += temporary
            }
        }
        val mapping = linkedMapOf<String, String>()
        for (value in desired) {
            val old = id(value["component_id"])?.let { parts[it] }
            val fields = linkedMapOf<Field<*>, Any?>(part.ORDINAL to id(value["ordinal"]), part.ACCOUNT_ID to id(value["account_id"]),
                part.AMOUNT to amount(value["amount"]), part.CURRENCY to value["currency"])
            val identifier = if (old == null) {
                context.connection.markLedgerChanged(workspace)
                sql.insertInto(part).set(fields + mapOf(part.WORKSPACE_ID to workspace, part.CASH_TRANSACTION_ID to parent,
                    part.LABEL to "", part.SOURCE_KEY to "", part.METADATA_JSON to JsonObject(emptyMap()), part.CREATED_AT to context.now()))
                    .returningResult(part.ID).fetchSingle().value1()!!
            } else old[part.ID]!!.also { key ->
                if (fields.any { (field, value) ->
                    val previous = old[field]
                    if (previous is CashStoredDecimal && value is CashStoredDecimal)
                        if (context.sqlite) fixedCashDecimal(previous) != fixedCashDecimal(value)
                        else CashAmount.parse(previous.text) != CashAmount.parse(value.text)
                    else !CashRecordPolicy.valuesEqual(previous, value)
                }) {
                    context.connection.markLedgerChanged(workspace)
                    sql.update(part).set(fields).where(part.WORKSPACE_ID.eq(workspace).and(part.ID.eq(key))).execute()
                }
            }
            mapping[virtual.getValue(id(value["ordinal"])!!)] = identifier.toString()
        }
        return mapping
    }

}
