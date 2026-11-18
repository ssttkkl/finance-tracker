package com.finance.tracker.business.domain

class CashImportChoiceContextError : RuntimeException("choice_context_invalid")

const val CASH_IMPORT_CHOICE_CONTEXT_KEY = "relation_choice_context"

/** Minimal retained references, never source bodies or executable plans. Integer kinds stay explicit at the boundary. */
class CashImportChoiceContextRules(
    private val integer: (Any?) -> Long?,
    private val truthy: (Any?) -> Boolean,
    private val copyValue: (Any?) -> Any?,
    private val validateCommonFields: (Map<*, *>) -> Unit,
    private val integerValue: (Int) -> Any?,
) {
    private val reference = Regex("[A-Za-z0-9_:.~-]{1,255}")
    private val actions = setOf("accept", "defer", "ignore")
    private val commonFields = setOf("category_id", "note", "counterparty", "counterparty_account", "counterparty_account_attrs")
    private val contextKeys = setOf("version", "operations", "relation_groups", "invalidated_relation_choices",
        "relation_choice_generations")
    private val choiceKeys = setOf("suggestion_id", "action", "candidate_id", "common_fields")
    private companion object { const val MAX_ITEMS = 100_000 }

    private fun fail(): Nothing = throw CashImportChoiceContextError()
    private fun isReference(value: Any?): Boolean = value is String && reference.matches(value)
    private fun map(value: Any?): Map<*, *> = value as? Map<*, *> ?: fail()
    private fun stringMap(value: Any?): Map<String, Any?> {
        val raw = map(value)
        if (raw.keys.any { it !is String }) fail()
        @Suppress("UNCHECKED_CAST") return raw as Map<String, Any?>
    }
    private fun members(value: Any?): Boolean {
        val items = value as? List<*> ?: return false
        if (items.isEmpty() || items.size > MAX_ITEMS) return false
        return items.all { item ->
            val entry = item as? Map<*, *> ?: return@all false
            entry.size == 1 && entry.keys.all { it == "row_ref" || it == "transaction_id" } && entry.values.all(::isReference)
        }
    }
    private fun generation(value: Any?): Boolean {
        val number = integer(value) ?: return false
        return number >= 0 && number < Long.MAX_VALUE
    }

    /** 校验并返回选择证明；未知键、错误类型或越界规模都按整体无效处理。 */
    fun validated(raw: Any?): Map<String, Any?> {
        val value = stringMap(raw)
        if (value.keys != contextKeys || integer(value["version"]) != 1L) fail()
        val operations = value["operations"] as? List<*> ?: fail()
        val groups = value["relation_groups"] as? List<*> ?: fail()
        if (operations.size > MAX_ITEMS || groups.size > MAX_ITEMS) fail()
        for (item in operations) {
            val operation = stringMap(item)
            val values = stringMap(operation["values"] ?: fail())
            if (operation.keys != setOf("occurrence_key", "values") || !isReference(operation["occurrence_key"]) ||
                values.keys != setOf("record_id") || !isReference(values["record_id"])) fail()
        }
        val seen = mutableSetOf<String>()
        for (item in groups) {
            val group = stringMap(item)
            val identifier = group["suggestion_id"] as? String ?: fail()
            if (group.keys != setOf("suggestion_id", "source_suggestion_id", "choice_generation", "member_refs", "candidates") ||
                !reference.matches(identifier) || !seen.add(identifier) || !isReference(group["source_suggestion_id"]) ||
                !members(group["member_refs"]) || !generation(group["choice_generation"])) fail()
            val candidates = group["candidates"] as? List<*> ?: fail()
            if (candidates.size > MAX_ITEMS) fail()
            for (candidate in candidates) {
                val entry = stringMap(candidate)
                if (entry.keys != setOf("candidate_id") || !isReference(entry["candidate_id"])) fail()
            }
        }
        val stale = stringMap(value["invalidated_relation_choices"] ?: fail())
        val generations = stringMap(value["relation_choice_generations"] ?: fail())
        if (stale.size > MAX_ITEMS || generations.size > MAX_ITEMS) fail()
        for ((identifier, item) in stale) {
            val entry = stringMap(item)
            if (!reference.matches(identifier) || entry.keys != setOf("choice", "member_refs") || !members(entry["member_refs"])) fail()
            val choice = stringMap(entry["choice"])
            if (!choice.keys.containsAll(setOf("suggestion_id", "action")) || (choice.keys - choiceKeys).isNotEmpty() ||
                choice["suggestion_id"] != identifier || choice["action"] !in actions ||
                choice["candidate_id"] != null && !isReference(choice["candidate_id"])) fail()
            if (choice.containsKey("common_fields")) {
                val common = stringMap(choice["common_fields"] ?: fail())
                if (choice["action"] != "accept" || (common.keys - commonFields).isNotEmpty()) fail()
                try { validateCommonFields(common) }
                catch (_: CashPolicyError) { fail() }
                catch (_: InvalidCashValue) { fail() }
            }
        }
        for ((identifier, item) in generations) if (!reference.matches(identifier) || !generation(item)) fail()
        for (item in groups) {
            val group = stringMap(item)
            val expected = generations[group["source_suggestion_id"]]?.let(integer) ?: 0L
            if (integer(group["choice_generation"]) != expected) fail()
        }
        return value
    }

    /** 从计划提取可跨文件变化保留的引用证明；已提交事务和来源正文不进入上下文。 */
    fun fromPlan(plan: Map<String, Any?>): Map<String, Any?> {
        val operations = (plan["operations"] as? List<*>).orEmpty().mapNotNull { raw ->
            val operation = raw as? Map<*, *> ?: fail()
            if (!truthy(operation["occurrence_key"]) || truthy(operation["transaction_id"])) return@mapNotNull null
            val values = operation["values"] as? Map<*, *> ?: fail()
            linkedMapOf<String, Any?>("occurrence_key" to operation["occurrence_key"],
                "values" to linkedMapOf<String, Any?>("record_id" to values["record_id"]))
        }
        val groups = (plan["relation_groups"] as? List<*>).orEmpty().map { raw ->
            val group = raw as? Map<*, *> ?: fail()
            val candidates = group["candidates"] as? List<*> ?: fail()
            linkedMapOf<String, Any?>(
                "suggestion_id" to group["suggestion_id"],
                "source_suggestion_id" to (group["source_suggestion_id"] ?: group["suggestion_id"]),
                "choice_generation" to (group["choice_generation"] ?: integerValue(0)),
                "member_refs" to copyValue(group["member_refs"]),
                "candidates" to candidates.map { candidate ->
                    val entry = candidate as? Map<*, *> ?: fail()
                    linkedMapOf<String, Any?>("candidate_id" to entry["candidate_id"])
                })
        }
        return validated(linkedMapOf<String, Any?>(
            "version" to integerValue(1), "operations" to operations, "relation_groups" to groups,
            "invalidated_relation_choices" to copyValue(plan["invalidated_relation_choices"] ?: emptyMap<String, Any?>()),
            "relation_choice_generations" to copyValue(plan["relation_choice_generations"] ?: emptyMap<String, Any?>())))
    }

    fun retained(contextData: Map<String, Any?>, plan: Map<String, Any?>?): Map<String, Any?> {
        val data = linkedMapOf<String, Any?>()
        contextData.forEach { (key, item) -> data[key] = copyValue(item) }
        if (plan != null) data[CASH_IMPORT_CHOICE_CONTEXT_KEY] = fromPlan(plan)
        else if (CASH_IMPORT_CHOICE_CONTEXT_KEY in data) validated(data[CASH_IMPORT_CHOICE_CONTEXT_KEY])
        return data
    }

    fun previous(contextData: Map<String, Any?>): Map<String, Any?>? =
        if (CASH_IMPORT_CHOICE_CONTEXT_KEY in contextData) validated(contextData[CASH_IMPORT_CHOICE_CONTEXT_KEY]) else null

    fun without(contextData: Map<String, Any?>): Map<String, Any?> =
        contextData.filterKeys { it != CASH_IMPORT_CHOICE_CONTEXT_KEY }
}
