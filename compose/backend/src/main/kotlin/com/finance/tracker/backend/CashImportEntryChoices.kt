package com.finance.tracker.backend

internal typealias EntryTargetContext = com.finance.tracker.business.domain.EntryTargetContext
internal typealias EntryTargetSnapshot = com.finance.tracker.business.domain.EntryTargetSnapshot
internal typealias EntryTargetIndex = com.finance.tracker.business.domain.EntryTargetIndex

/** Native value and numeric-identity bridges; decisions live in the shared rule. */
internal object CashImportEntryChoices {
    private val rules = com.finance.tracker.business.domain.CashImportEntryChoiceRules(CashRecordPolicy,
        { values -> cashImportDigest(*values.toTypedArray()) })
    private val edgeOrder = Comparator<String> { first, second ->
        val a = first.toBigIntegerOrNull(); val b = second.toBigIntegerOrNull()
        if (a != null && b != null) a.compareTo(b) else cashRelationStringOrder.compare(first, second)
    }
    fun index(snapshot: EntryTargetSnapshot, groups: List<Map<String, Any?>>) = rules.index(snapshot, groups)
    fun target(snapshot: EntryTargetSnapshot, parents: Collection<String>, index: EntryTargetIndex) =
        rules.target(snapshot, parents, index, edgeOrder)
    fun apply(result: MutableMap<String, Any?>, choices: List<Any?>, previous: Map<String, Any?>?,
        categoryExists: (String) -> Boolean) = rules.apply(result, choices, previous, categoryExists)
}
