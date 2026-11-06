package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashWriteSetDto
import kotlinx.serialization.json.*

/** Input normalization only; final manual flags, stable identity, intent and effects still require trusted-state checks. */
fun normalizeCashWriteSetEntryValues(changes: CashWriteSetDto): CashWriteSetDto = changes.copy(
    relationGroups = changes.relationGroups.mapIndexed { index, group ->
        if (group.commonFields.isEmpty()) group else group.copy(commonFields = normalizeWriteEntryPublicValues(
            group.commonFields, "relation_groups[$index].common_fields", complete = false))
    },
    transactions = changes.transactions.mapIndexed { index, row ->
        row.entryEdit?.let { edit -> row.copy(entryEdit = edit.copy(patch = normalizeWriteEntryPublicValues(
            edit.patch, "transactions[$index].entry_edit.patch", complete = false))) } ?: row
    },
    entries = changes.entries.mapIndexed { index, row ->
        val path = "entries[$index]"
        val values = normalizeWriteEntryPublicValues(buildJsonObject {
            put("category_id", row.categoryId?.let(::JsonPrimitive) ?: JsonNull)
            put("note", row.note); put("counterparty", row.counterparty); put("counterparty_account", row.counterpartyAccount)
            put("counterparty_account_attrs", row.counterpartyAccountAttrs)
        }, path)
        row.copy(categoryId = values.getValue("category_id").takeUnless { it == JsonNull }?.jsonPrimitive?.content,
            note = values.getValue("note").jsonPrimitive.content, counterparty = values.getValue("counterparty").jsonPrimitive.content,
            counterpartyAccount = values.getValue("counterparty_account").jsonPrimitive.content,
            counterpartyAccountAttrs = values.getValue("counterparty_account_attrs"),
            manualFields = normalizeWriteEntryFields(row.manualFields, "$path.manual_fields"),
            editedFields = normalizeWriteEntryFields(row.editedFields, "$path.edited_fields"),
            members = row.members.mapIndexed { memberIndex, member ->
                val memberPath = "$path.members[$memberIndex]"
                val standalone = member.standaloneValues as? JsonObject ?: invalidWriteEntryValue("$memberPath.standalone_values")
                member.copy(standaloneValues = normalizeWriteEntryPublicValues(standalone, "$memberPath.standalone_values"),
                    standaloneManualFields = normalizeWriteEntryFields(member.standaloneManualFields, "$memberPath.standalone_manual_fields"))
            })
    },
)

private fun invalidWriteEntryValue(path: String): Nothing = throw CashPolicyError("invalid_field", listOf(path))

private fun normalizeWriteEntryFields(fields: List<String>, path: String): List<String> {
    if (fields.any { it !in commonCashFields }) throw CashPolicyError("field_not_editable", listOf(path))
    return fields.distinct().sortedWith(::pythonStringOrder)
}

private fun normalizeWriteEntryPublicValues(values: JsonObject, path: String, complete: Boolean = true): JsonObject {
    if (values.isEmpty() || complete && values.keys != commonCashFields) invalidWriteEntryValue(path)
    if (values.keys.any { it !in commonCashFields }) throw CashPolicyError("field_not_editable", listOf(path))
    // Inspect the fixed public shape before decoding; arbitrary nested JSON is never traversed here.
    val decoded = values.mapValues { (field, value) ->
        when {
            value == JsonNull -> null
            value is JsonPrimitive && value.isString -> value.content
            field == "counterparty_account_attrs" && value is JsonArray -> value.map {
                if (it !is JsonPrimitive || !it.isString) invalidWriteEntryValue("$path.$field")
                it.content
            }
            else -> invalidWriteEntryValue("$path.$field")
        }
    }
    return try { CashEntryEditRules(LocalCashRecordValues).normalize(decoded) } catch (_: InvalidCashValue) { invalidWriteEntryValue(path) }
}
