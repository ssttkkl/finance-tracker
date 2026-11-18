package com.finance.tracker.business.domain

val commonCashFields = setOf("category_id", "note", "counterparty", "counterparty_account", "counterparty_account_attrs")

class CashImportPlanError(val code: String, var commitState: String? = null,
    var diagnostic: com.finance.tracker.business.contracts.CashDiagnosticDto? = null) : RuntimeException(code)

/** Input decisions only; platform adapters retain integer, copy and field validation semantics. */
class CashImportInputRules(
    private val positiveInteger: (Any?) -> Boolean,
    private val copyValue: (Any?) -> Any?,
    private val validateCommonFields: (Map<*, *>) -> Unit,
) {
    private val common = commonCashFields
    private fun fail(): Nothing = throw CashImportPlanError("plan_input_invalid")
    fun reference(value: Any?, maximum: Int = 255): Boolean =
        value is String && Regex("[A-Za-z0-9_:.~-]{1,$maximum}").matches(value)

    fun choices(value: Any?, key: String, fields: Set<String>, actions: Set<String>? = null): List<Any?> {
        val items = value as? List<*> ?: fail()
        if (items.size > 100_000) fail()
        val seen = mutableSetOf<Any?>()
        for (raw in items) {
            val item = raw as? Map<*, *> ?: fail()
            if (item.keys.any { it !is String } || item.keys.any { it !in fields } || !reference(item[key]) ||
                item[key] in seen || actions != null && (item["action"] !is String || item["action"] !in actions)) fail()
            if ("patch" in item && item["patch"] !is Map<*, *>) fail()
            if (key == "source_account_ref") {
                val identifier = item["account_id"]
                val revision = item["expected_account_revision"]
                if (identifier != null && (!reference(identifier, 128) || !positiveInteger(revision)) ||
                    identifier == null && revision != null) fail()
            }
            val candidate = item["candidate_id"]
            if (candidate != null && !reference(candidate, 128)) fail()
            if ("common_fields" in item) {
                val commonValue = item["common_fields"] as? Map<*, *> ?: fail()
                if (item["action"] != "accept" || commonValue.keys.any { it !is String || it !in common }) fail()
                try { validateCommonFields(commonValue) }
                catch (_: CashPolicyError) { fail() }
                catch (_: InvalidCashValue) { fail() }
            }
            if ("inherit_common_fields" in item && item["inherit_common_fields"] !is Boolean) fail()
            seen += item[key]
        }
        @Suppress("UNCHECKED_CAST") return copyValue(items) as List<Any?>
    }

    fun previewInput(accountSelections: Any?, rowChoices: Any?, relationChoices: Any?, entryChoices: Any? = null): Map<String, Any?> {
        val entries = choices(entryChoices ?: emptyList<Any?>(), "edit_ref", setOf("edit_ref", "patch"))
        if (entries.any { item -> (item as Map<*, *>)["patch"] !is Map<*, *> || ((item["patch"] as Map<*, *>).keys - common).isNotEmpty() })
            fail()
        return linkedMapOf(
            "entry_choices" to entries,
            "account_selections" to choices(accountSelections, "source_account_ref",
                setOf("source_account_ref", "account_id", "expected_account_revision")),
            "row_choices" to choices(rowChoices, "row_ref", setOf("row_ref", "action", "patch"),
                setOf("apply", "keep_existing", "defer")),
            "relation_choices" to choices(relationChoices, "suggestion_id",
                setOf("suggestion_id", "action", "candidate_id", "common_fields", "inherit_common_fields"),
                setOf("accept", "defer", "ignore")),
        )
    }
}
