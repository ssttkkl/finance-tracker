package com.finance.tracker.business.domain

import kotlinx.serialization.json.JsonElement

/** Apply entry inheritance after operation overlays, using the entries read for the original stored parents. */
fun <I : Comparable<I>, T : Comparable<T>> applyCashImportPreviewEntryPublic(
    snapshot: CashImportPreviewSnapshot<I, T>,
    members: Map<Long, CashEntryMember<Long>>,
    entries: Map<String, CashEntry<Long>>,
    decode: (JsonElement?) -> Any?,
) {
    val patches = linkedMapOf<String, MutableMap<String, Any?>>()
    for ((identifier, operation) in snapshot.parentOps) {
        val member = identifier.toLongOrNull()?.let(members::get) ?: continue
        @Suppress("UNCHECKED_CAST") val edit = operation["entry_edit"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val patch = edit?.get("patch") as? Map<String, Any?>
        if (patch != null) patches.getOrPut(member.entryId) { linkedMapOf() }.putAll(patch)
    }
    for ((identifier, existingFact) in snapshot.facts.toMap()) {
        val member = identifier.toLongOrNull()?.let(members::get)
        val entry = member?.let { entries[it.entryId] } ?: continue
        val root = snapshot.values[entry.rootTransactionId.toString()] ?: snapshot.values.getValue(identifier)
        val public = linkedMapOf<String, Any?>()
        for (field in commonCashFields) public[field] = if (field in entry.manualFields) decode(entry.values[field]) else root[field]
        public.putAll(patches[entry.id].orEmpty())
        snapshot.entryPublic[identifier] = linkedMapOf("entry_id" to entry.id, "values" to public,
            "manual_fields" to (entry.manualFields + patches[entry.id].orEmpty().keys).toSet().sortedWith(::pythonStringOrder))
        snapshot.facts[identifier] = existingFact.withPublicFields(
            public["counterparty"] as String, public["category_id"] as? String, public["note"] as String)
    }
    for ((identifier, operation) in snapshot.parentOps) if (identifier !in snapshot.entryPublic) {
        val public = linkedMapOf<String, Any?>()
        for (field in commonCashFields) public[field] = snapshot.values.getValue(identifier)[field]
        @Suppress("UNCHECKED_CAST") val edit = operation["entry_edit"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val patch = edit?.get("patch") as? Map<String, Any?>
        public.putAll(patch.orEmpty())
        @Suppress("UNCHECKED_CAST") val manual = operation["manual_fields"] as? List<String>
        snapshot.entryPublic[identifier] = linkedMapOf("entry_id" to "new:$identifier", "values" to public,
            "manual_fields" to (manual.orEmpty() + patch.orEmpty().keys).toSet().sortedWith(::pythonStringOrder))
        snapshot.facts[identifier] = snapshot.facts.getValue(identifier).withPublicFields(
            public["counterparty"] as String, public["category_id"] as? String, public["note"] as String)
    }
}
