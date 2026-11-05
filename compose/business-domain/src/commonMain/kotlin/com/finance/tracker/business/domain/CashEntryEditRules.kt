package com.finance.tracker.business.domain

import kotlinx.serialization.json.*

/** Applies public edits in place; the caller owns authorization, member reads and transaction rollback. */
class CashEntryEditRules(private val recordValues: CashRecordValues) {
    fun normalize(patch: Map<*, *>): JsonObject = JsonObject(patch.entries.associate { (key, value) ->
        val field = key as String
        field to when (val result = recordValues.normalize(field, value)) {
            null -> JsonNull
            is String -> JsonPrimitive(result)
            is List<*> -> JsonArray(result.map { JsonPrimitive(it as String) })
            else -> error("unexpected common value")
        }
    })

    fun <I> patch(entry: CashEntry<I>, patch: Any?, expectedRevision: Long?,
        readMembers: () -> List<CashEntryMember<I>>): Boolean {
        if (entry.revision != expectedRevision) throw CashPolicyError("entry_changed", emptyList())
        if (patch !is Map<*, *> || patch.keys.any { it !in commonCashFields }) throw CashPolicyError("field_not_editable", emptyList())
        val values = normalize(patch)
        val changed = values.any { (field, value) -> entry.values[field] != value || field !in entry.manualFields }
        if (changed) {
            entry.values = JsonObject(entry.values + values)
            entry.manualFields = (entry.manualFields + values.keys).distinct().sortedWith(::pythonStringOrder)
            entry.revision = incrementCashEntryRevision(entry.revision)
            val group = readMembers()
            if (group.size == 1) group.single().let { member ->
                member.standaloneValues = JsonObject(member.standaloneValues + values)
                member.standaloneManualFields = (member.standaloneManualFields + values.keys).distinct().sortedWith(::pythonStringOrder)
            }
        }
        return changed
    }
}
