package com.finance.tracker.backend

import java.math.BigInteger
import java.util.Base64
import kotlinx.serialization.json.*

internal typealias CashImportPlanError = com.finance.tracker.business.domain.CashImportPlanError

/** 导入计划的只读公开视图、分页与冻结详情；不访问实时库。 */
internal object CashImportPlanViews : CashImportPlanViewEngine()

internal open class CashImportPlanViewEngine {
    private val issueFields = setOf("code", "message", "field_path", "blocking_scope", "next_action", "after_import_action")
    private val refFields = setOf("row_ref", "transaction_id")
    private val processingFields = setOf("row_ref", "file_ref", "planned_action", "code", "operation_ref", "transaction_id", "duplicate_of")
    private val mutable = mutableCashFields
    private val financial = financialCashFields
    private val identity = setOf("source_type", "source_scope_key", "source_account_key", "record_origin", "manual_transfer_id", "record_id")
    private val common = commonCashFields

    private fun fail(code: String = "plan_input_invalid"): Nothing = throw CashImportPlanError(code)
    private fun reference(value: Any?, maximum: Int = 255): Boolean =
        inputRules.reference(value, maximum)
    private fun integral(value: Any?): BigInteger? = when (value) {
        is Byte, is Short, is Int, is Long, is BigInteger -> BigInteger(value.toString())
        is Double, is Float -> null
        else -> null
    }
    private fun deep(value: Any?): Any? = CashRecordPolicy.copyValue(value)

    private fun dict(value: Any?, code: String = "plan_build_failed"): Map<String, Any?> {
        val map = value as? Map<*, *> ?: throw CashImportPlanError(code)
        if (map.keys.any { it !is String }) throw CashImportPlanError(code)
        @Suppress("UNCHECKED_CAST") return map as Map<String, Any?>
    }
    private fun list(value: Any?): List<Any?> = value as? List<*> ?: fail()
    private fun strings(value: Any?): List<Any?> = value as? List<*> ?: throw CashImportPlanError("plan_build_failed")

    private val inputRules = com.finance.tracker.business.domain.CashImportInputRules(
        { integral(it)?.signum() == 1 }, ::deep,
        { CashRecordPolicy.prepare(mapOf("state" to "draft"), it) },
    )
    fun choices(value: Any?, key: String, fields: Set<String>, actions: Set<String>? = null): List<Any?> =
        inputRules.choices(value, key, fields, actions)

    fun previewInput(accountSelections: Any?, rowChoices: Any?, relationChoices: Any?, entryChoices: Any? = null): Map<String, Any?> =
        inputRules.previewInput(accountSelections, rowChoices, relationChoices, entryChoices)

    fun sessionId(planId: Any?): String {
        val match = (planId as? String)?.let { Regex("ip_([A-Za-z0-9_-]{43})\\.([A-Za-z0-9_-]{16})").matchEntire(it) }
            ?: throw CashImportPlanError("plan_not_found")
        return match.groupValues[1]
    }

    fun only(value: Any?, fields: Set<String>, copyValues: Boolean = true): Map<String, Any?> {
        val map = value as? Map<*, *> ?: throw CashImportPlanError("plan_build_failed")
        if (map.keys.any { it !is String }) throw CashImportPlanError("plan_build_failed")
        val result = linkedMapOf<String, Any?>()
        for ((key, item) in map) {
            if (key !in fields) continue
            result[key as String] = if (copyValues) deep(item) else item
        }
        return result
    }

    fun objects(value: Any?, fields: Set<String>, copyValues: Boolean = true): List<Any?> {
        val items = value as? List<*> ?: throw CashImportPlanError("plan_build_failed")
        return items.map { only(it, fields, copyValues) }
    }

    fun issues(value: Any?, defaultScope: String = "row"): List<Any?> =
        objects(value, issueFields).map { raw ->
            val item = raw as Map<*, *>
            val scope = (item["blocking_scope"] as? String) ?: defaultScope
            val issue = try {
                cashImportIssue(item["code"], item["field_path"], scope, item["next_action"], item["after_import_action"])
            } catch (_: IllegalArgumentException) { throw CashImportPlanError("plan_build_failed") }
            linkedMapOf<String, Any?>("code" to issue.code, "message" to issue.message,
                "field_path" to issue.fieldPath, "blocking_scope" to issue.blockingScope,
                "next_action" to issue.nextAction) + (issue.afterImportAction?.let { mapOf<String, Any?>("after_import_action" to it) }
                ?: emptyMap())
        }

    fun processing(value: Any?): List<Any?> {
        val items = objects(value, processingFields + issueFields)
        return items.map { raw ->
            @Suppress("UNCHECKED_CAST") val item = linkedMapOf<String, Any?>().apply { putAll(raw as Map<String, Any?>) }
            if (item["code"] != null) {
                val issue = issues(listOf(item)).single() as Map<String, Any?>
                item.putAll(issue)
            } else {
                for (field in issueFields - "code") item.remove(field)
            }
            item
        }
    }

    fun display(value: Any?): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>().apply {
            putAll(only(value, setOf("occurred_at", "amount", "currency", "counterparty", "note", "accounts",
                "category_id", "counterparty_account", "counterparty_account_attrs", "record_type", "record_subtype")))
        }
        if (!result.containsKey("accounts")) result["accounts"] = emptyList<Any?>()
        val accounts = result["accounts"]
        if (accounts !is List<*> || accounts.any { it !is String }) throw CashImportPlanError("plan_build_failed")
        for (field in listOf("occurred_at", "amount", "currency", "counterparty", "note", "category_id", "counterparty_account",
            "record_type", "record_subtype")) {
            val item = result.getOrPut(field) { null }
            if (item != null && item !is String) throw CashImportPlanError("plan_build_failed")
        }
        return result
    }

    fun publicItem(value: Any?): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>().apply {
            putAll(only(value, setOf("preview_item_id", "confirmation_status", "anticipated_state", "display",
                "statistics_eligible", "member_refs", "attention_reasons", "detail_available"), copyValues = false))
        }
        result["display"] = display(result["display"])
        result["member_refs"] = objects(result["member_refs"] ?: emptyList<Any?>(), refFields)
        result["attention_reasons"] = issues(result["attention_reasons"] ?: emptyList<Any?>())
        return result
    }

    fun publicDetail(value: Any?, copyValues: Boolean = true): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>().apply {
            putAll(only(value, setOf("entry", "members", "relation_members", "field_diffs", "issues",
                "relation_choices", "relation_effects"), copyValues = false))
        }
        if ("entry" in result) {
            val entry = linkedMapOf<String, Any?>().apply {
                putAll(only(result["entry"], setOf("fields", "editable_fields", "edit_ref"), copyValues))
            }
            entry["fields"] = only(entry.getOrDefault("fields", emptyMap<String, Any?>()), common, copyValues)
            result["entry"] = entry
        }
        for (role in listOf("members", "relation_members")) {
            if (role == "relation_members" && role !in result) continue
            val members = objects(result.getOrDefault(role, emptyList<Any?>()),
                refFields + setOf("planned_action", "fields", "editable_fields", "allowed_actions", "row_revision"), copyValues)
            val rendered = mutableListOf<Any?>()
            for (raw in members) {
                @Suppress("UNCHECKED_CAST") val member = linkedMapOf<String, Any?>().apply { putAll(raw as Map<String, Any?>) }
                if ("fields" in member) {
                    val fields = only(member["fields"], mutable + financial + identity + setOf("state", "row_revision"),
                        copyValues = false).toMutableMap()
                    if ("components" in fields) fields["components"] = objects(fields["components"],
                        setOf("component_id", "account_id", "amount", "currency", "ordinal"), copyValues)
                    member["fields"] = fields
                }
                rendered += member
            }
            result[role] = rendered
        }
        result["field_diffs"] = objects(result.getOrDefault("field_diffs", emptyList<Any?>()),
            setOf("field", "current", "incoming", "effective", "resolution", "row_ref", "transaction_id"), copyValues)
        result["issues"] = issues(result.getOrDefault("issues", emptyList<Any?>()))
        val choices = objects(result.getOrDefault("relation_choices", emptyList<Any?>()),
            setOf("suggestion_id", "kind", "action", "candidate_id", "candidates", "member_refs",
                "allowed_actions", "code", "common_fields", "common_field_conflicts", "inherit_common_fields",
                "relation_ids", "split_field_diffs", "relations"), copyValues)
        for (raw in choices) {
            @Suppress("UNCHECKED_CAST") val choice = raw as MutableMap<String, Any?>
            if ("member_refs" in choice) choice["member_refs"] = objects(choice["member_refs"], refFields, copyValues)
            if ("candidates" in choice) {
                val candidates = objects(choice["candidates"],
                    setOf("candidate_id", "display", "member_refs", "code", "relations"), copyValues)
                for (candidateRaw in candidates) {
                    @Suppress("UNCHECKED_CAST") val candidate = candidateRaw as MutableMap<String, Any?>
                    if ("display" in candidate) candidate["display"] = display(candidate["display"])
                    if ("member_refs" in candidate) candidate["member_refs"] = objects(candidate["member_refs"], refFields, copyValues)
                }
                choice["candidates"] = candidates
            }
            for (option in listOf(choice) + (choice["candidates"] as? List<*> ?: emptyList<Any?>())) {
                @Suppress("UNCHECKED_CAST") val map = option as MutableMap<String, Any?>
                if ("relations" in map) {
                    val relations = objects(map["relations"], setOf("kind", "member_refs"), copyValues)
                    for (relationRaw in relations) {
                        @Suppress("UNCHECKED_CAST") val relation = relationRaw as MutableMap<String, Any?>
                        relation["member_refs"] = objects(relation["member_refs"] ?: emptyList<Any?>(), refFields, copyValues)
                    }
                    map["relations"] = relations
                }
            }
        }
        result["relation_choices"] = choices
        val effects = objects(result.getOrDefault("relation_effects", emptyList<Any?>()),
            setOf("relation_id", "kind", "effect", "member_refs", "code"), copyValues)
        for (raw in effects) {
            @Suppress("UNCHECKED_CAST") val effect = raw as MutableMap<String, Any?>
            if ("member_refs" in effect) effect["member_refs"] = objects(effect["member_refs"], refFields, copyValues)
        }
        result["relation_effects"] = effects
        return result
    }

    fun operationIndex(plan: Map<String, Any?>): Map<String, Map<String, Any?>> {
        val operations = plan["operations"] ?: emptyList<Any?>()
        if (operations !is List<*> || plan.getOrDefault("detail_values", emptyMap<String, Any?>()) !is Map<*, *>) throw CashImportPlanError("plan_build_failed")
        val result = linkedMapOf<String, Map<String, Any?>>()
        for (raw in operations) {
            val operation = raw as? Map<*, *> ?: throw CashImportPlanError("plan_build_failed")
            val ref = operation["operation_ref"]
            if (!reference(ref, 128) || ref in result) throw CashImportPlanError("plan_build_failed")
            @Suppress("UNCHECKED_CAST") result[ref as String] = operation as Map<String, Any?>
        }
        return result
    }

    open fun resolveDetail(plan: Map<String, Any?>, value: Any?,
        operations: Map<String, Map<String, Any?>> = operationIndex(plan), copyValues: Boolean = true): Map<String, Any?> {
        val result = if (copyValues) deep(value) as? Map<*, *> ?: throw CashImportPlanError("plan_build_failed")
        else (value as? Map<*, *> ?: throw CashImportPlanError("plan_build_failed")).toMutableMap()
        @Suppress("UNCHECKED_CAST") val output = result as MutableMap<String, Any?>
        for (role in listOf("members", "relation_members")) {
            if (role == "relation_members" && role !in output) continue
            val members = output.getOrDefault(role, emptyList<Any?>()) as? List<*> ?: throw CashImportPlanError("plan_build_failed")
            // 与 Python 一致：成员总是浅复制，校验路径不能改写调用方计划。
            val copied = members.map { it as? Map<*, *> ?: throw CashImportPlanError("plan_build_failed") }
                .map { it.toMutableMap() }
            for (index in copied.indices) {
                val member = copied[index] as MutableMap<Any?, Any?>
                if ("fields_ref" !in member) continue
                val ref = member.remove("fields_ref") as? Map<*, *> ?: throw CashImportPlanError("plan_build_failed")
                if (ref.keys !in listOf(setOf("operation_ref"), setOf("transaction_id")) || "fields" in member)
                    throw CashImportPlanError("plan_build_failed")
                val fields: Any? = if ("operation_ref" in ref) {
                    val key = ref["operation_ref"] as? String ?: throw CashImportPlanError("plan_build_failed")
                    val operation = operations[key] ?: throw CashImportPlanError("plan_build_failed")
                    val rowRef = member["row_ref"]
                    val transaction = member["transaction_id"]
                    if (!((rowRef != null && rowRef in (operation["row_refs"] as? List<*> ?: emptyList<Any?>())) ||
                            (transaction != null && transaction == operation["transaction_id"])))
                        throw CashImportPlanError("plan_build_failed")
                    operation["values"]
                } else {
                    val key = ref["transaction_id"] as? String ?: throw CashImportPlanError("plan_build_failed")
                    val detailValues = plan["detail_values"] as? Map<*, *> ?: throw CashImportPlanError("plan_build_failed")
                    if (key !in detailValues || member["transaction_id"] != key) throw CashImportPlanError("plan_build_failed")
                    detailValues[key]
                }
                val mapped = linkedMapOf<String, Any?>().apply {
                    putAll(only(fields, mutable + financial + identity + setOf("state"), copyValues))
                }
                val components = mapped.getOrDefault("components", emptyList<Any?>())
                if (components !is List<*> || components.any { it !is Map<*, *> }) throw CashImportPlanError("plan_build_failed")
                val parts = components.map { (it as Map<*, *>).toMutableMap() }
                for (part in parts) {
                    val identifier = part["component_id"]
                    if (componentIdentifier(identifier)?.let { it >= BigInteger.TWO.pow(63) } == true) part.remove("component_id")
                }
                if (!copyValues || "components" in mapped) mapped["components"] = if (copyValues) parts.map { it.toMap() } else parts
                member["fields"] = mapped
            }
            if (!copyValues || role in output) output[role] = copied
        }
        return output
    }

    fun fileResults(value: Any?): List<Any?> {
        val files = objects(value, setOf("file_ref", "display_name", "status", "recognized_row_count", "issues",
            "code", "can_retry", "diagnostic"))
        for (raw in files) {
            @Suppress("UNCHECKED_CAST") val file = raw as MutableMap<String, Any?>
            val diagnostic = file["diagnostic"]
            if (diagnostic != null && !validCashDiagnostic(diagnostic)) throw CashImportPlanError("plan_build_failed")
            if ("issues" in file) file["issues"] = issues(file["issues"], "file")
        }
        return files
    }

    private fun validCashDiagnostic(value: Any?): Boolean {
        val item = value as? Map<*, *> ?: return false
        if (item.keys != setOf("error_id", "stage", "scope", "locations", "total_locations")) return false
        if ((item["error_id"] as? String)?.matches(Regex("[a-f0-9]{32}")) != true ||
            item["stage"] !in setOf("request", "file_read", "row_conversion", "row_planning", "relation_discovery",
                "submit_revalidation", "source_evidence", "row_write", "batch_write", "account_mapping", "relation_save",
                "balances", "projection", "entry_edit", "database_commit") ||
            item["scope"] !in setOf("row", "related", "file", "request")) return false
        val locations = item["locations"] as? List<*> ?: return false
        val total = integral(item["total_locations"]) ?: return false
        if (locations.size > 100 || total < BigInteger.valueOf(locations.size.toLong())) return false
        return locations.all { raw ->
            val location = raw as? Map<*, *> ?: return@all false
            location.isNotEmpty() && location.all { (key, item) -> when (key) {
                "row_number" -> integral(item)?.signum() == 1
                "position_kind" -> item in setOf("csv_record", "worksheet_row", "document_row")
                "file_ref", "display_name", "row_ref", "document_position", "transaction_id" ->
                    item is String && item.codePointCount(0, item.length) in 1..(if (key == "display_name") 512 else 256)
                else -> false
            } }
        }
    }

    fun currentContract(plan: Map<String, Any?>): Boolean =
        integral(plan["contract_version"]) == BigInteger.ONE && plan["monthly_source"] is List<*> &&
            (plan["items"] as? List<*>)?.all { raw ->
                val display = (raw as? Map<*, *>)?.get("display") as? Map<*, *>
                display != null && "record_type" in display && "record_subtype" in display
            } == true

    fun validateReadable(plan: Map<String, Any?>) {
        val items = plan["items"] as? List<*> ?: throw CashImportPlanError("plan_build_failed")
        for (item in items) publicItem(item)
        val details = plan["item_details"] as? Map<*, *> ?: throw CashImportPlanError("plan_build_failed")
        val identifiers = items.map { (it as Map<*, *>)["preview_item_id"] }.toSet()
        if (details.keys.any { it !in identifiers }) throw CashImportPlanError("plan_build_failed")
        for (item in items) {
            val map = item as Map<*, *>
            if (map["detail_available"] == true && map["preview_item_id"] !in details) throw CashImportPlanError("plan_build_failed")
        }
        val operations = operationIndex(plan)
        for (detail in details.values) {
            if (independentDetail(detail, operations)) continue
            publicDetail(resolveDetail(plan, detail, operations, copyValues = false), copyValues = false)
        }
        processing(plan.getOrDefault("processing_details", emptyList<Any?>()))
        fileResults(plan.getOrDefault("file_results", emptyList<Any?>()))
        objects(plan["invalidated_choices"] ?: emptyList<Any?>(), setOf("kind", "reference", "code"))
    }

    protected open fun independentDetail(value: Any?, operations: Map<String, Map<String, Any?>>): Boolean {
        val detail = value as? Map<*, *> ?: return false
        if (detail.keys.any { it !is String }) return false
        for (key in listOf("relation_members", "field_diffs", "issues", "relation_choices", "relation_effects")) {
            val item = detail[key]
            if (item !is List<*> || item.isNotEmpty()) return false
        }
        val members = detail["members"] as? List<*> ?: return false
        if (members.size != 1) return false
        val entry = detail["entry"] as? Map<*, *> ?: return false
        if (entry.keys.any { it !is String } || entry["fields"] !is Map<*, *> ||
            (entry["fields"] as Map<*, *>).keys.any { it !is String }) return false
        val member = members[0] as? Map<*, *> ?: return false
        if (member.keys.any { it !is String } || "fields" in member) return false
        val reference = member["fields_ref"] as? Map<*, *> ?: return false
        if (reference.keys.any { it !is String } || reference.keys != setOf("operation_ref") ||
            reference["operation_ref"] !is String) return false
        val operation = operations[reference["operation_ref"]] ?: return false
        val rowRef = member["row_ref"]
        val transactionId = member["transaction_id"]
        val savedId = operation["transaction_id"]
        val rows = operation["row_refs"] ?: emptyList<Any?>()
        if (listOf(rowRef, transactionId, savedId).any { it != null && it !is String } ||
            rows !is List<*> || rows.any { it !is String }) return false
        if (!((rowRef != null && rowRef in rows) || (transactionId != null && transactionId == savedId))) return false
        val fields = operation["values"] as? Map<*, *> ?: return false
        if (fields.keys.any { it !is String }) return false
        val components = if ("components" in fields) fields["components"] else emptyList<Any?>()
        if (components !is List<*>) return false
        for (part in components) {
            val item = part as? Map<*, *> ?: return false
            if (item.keys.any { it !is String }) return false
            val identifier = item["component_id"]
            if (identifier != null && identifier !is String && identifier !is Number && identifier !is Boolean) return false
        }
        for (part in components) componentIdentifier((part as Map<*, *>)["component_id"])
        return true
    }

    private fun componentIdentifier(value: Any?): BigInteger? {
        if (value == null) return null
        val text = value.toString()
        if (value is BigInteger && text.length - (if (value.signum() < 0) 1 else 0) > 4300)
            throw IllegalArgumentException("Exceeds the limit (4300 digits) for integer string conversion; use sys.set_int_max_str_digits() to increase the limit")
        if (text.isEmpty() || !text.codePoints().allMatch { code -> code in pythonNondecimalDigits ||
                pythonIntegerDigitStarts.any { code in it until it + 10 } }) return null
        return try { pythonIntegerValue(JsonPrimitive(text)) }
        catch (failure: CategoryFailure) { throw IllegalArgumentException(failure.code) }
    }

    fun page(plan: Map<String, Any?>, kind: String, cursor: String? = null, startIndex: Int? = null,
        limit: Int = 50, confirmationStatus: String? = null, indexed: Boolean = false): Map<String, Any?> {
        if (limit !in 1..200 || startIndex != null && startIndex < 0 || cursor != null && startIndex != null ||
            confirmationStatus != null && confirmationStatus !in setOf("all", "confirmed", "pending"))
            throw CashImportPlanError("plan_page_invalid")
        val selected = confirmationStatus ?: "all"
        val planId = plan["plan_id"] as? String ?: throw CashImportPlanError("plan_build_failed")
        var values = (if (kind == "items") plan["items"] else plan["processing_details"]) as? List<*>
            ?: emptyList<Any?>()
        if (kind == "items" && selected != "all") values = values.filter {
            (it as? Map<*, *>)?.get("confirmation_status") == selected
        }
        var offset = startIndex ?: 0
        if (cursor != null) {
            if (cursor.length !in 1..4096) throw CashImportPlanError("plan_cursor_invalid")
            val data = try {
                val padded = cursor + "=".repeat((4 - cursor.length % 4) % 4)
                Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8))
            } catch (_: Exception) { throw CashImportPlanError("plan_cursor_invalid") }
            val array = data as? JsonArray ?: throw CashImportPlanError("plan_cursor_invalid")
            if (array.size != 4 || (array[0] as? JsonPrimitive)?.contentOrNull != planId ||
                (array[1] as? JsonPrimitive)?.contentOrNull != kind || (array[2] as? JsonPrimitive)?.contentOrNull != selected)
                throw CashImportPlanError("plan_cursor_invalid")
            val index = (array[3] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
                ?: throw CashImportPlanError("plan_cursor_invalid")
            if (index < 0 || index > values.size) throw CashImportPlanError("plan_cursor_invalid")
            offset = index
        }
        if (offset > values.size) throw CashImportPlanError("plan_page_invalid")
        val next = offset + limit
        val nextCursor = if (next < values.size) Base64.getUrlEncoder().withoutPadding().encodeToString(
            buildString {
                append('['); append(JsonPrimitive(planId)); append(','); append(JsonPrimitive(kind)); append(',')
                append(JsonPrimitive(selected)); append(','); append(next); append(']')
            }.encodeToByteArray()) else null
        val page = values.subList(offset, minOf(next, values.size))
        val rendered: List<Any?> = if (kind == "items") page.map(::publicItem) else processing(page)
        val result = linkedMapOf<String, Any?>("items" to deep(rendered), "next_cursor" to nextCursor)
        if (indexed) { result["start_index"] = offset; result["total_count"] = values.size }
        return result
    }

    fun view(plan: Map<String, Any?>, timezoneName: String = "UTC"): Map<String, Any?> {
        val items = page(plan, "items")
        val result = linkedMapOf<String, Any?>()
        for (key in listOf("plan_id", "session_id", "session_revision", "expires_at", "summary", "item_counts", "can_commit"))
            result[key] = deep(plan[key])
        result["monthly_summaries"] = CashImportMonthlySummaries.summarize(plan["monthly_source"] as? List<*> ?: emptyList<Any?>(), timezoneName)
        result["items"] = items["items"]
        result["file_results"] = fileResults(plan["file_results"] ?: emptyList<Any?>())
        result["pages"] = linkedMapOf<String, Any?>("items" to items["next_cursor"], "processing_details" to null)
        result["invalidated_choices"] = objects(plan["invalidated_choices"] ?: emptyList<Any?>(),
            setOf("kind", "reference", "code"))
        return result
    }
}
