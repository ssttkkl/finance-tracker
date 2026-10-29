package com.finance.tracker.backend

import java.util.Base64
import kotlinx.serialization.json.*

/** 冻结维护计划的只读视图、分页与单项详情；不访问实时数据库。 */
internal object MutationPlanView {
    private val commonCashFields = setOf("category_id", "note", "counterparty", "counterparty_account",
        "counterparty_account_attrs")
    private val identityFields = setOf("record_id", "source_type", "source_scope_key", "source_account_key",
        "record_origin", "manual_transfer_id")
    private val summaryCounts = setOf("update_posted", "finalize_draft", "update_draft", "resolve_issue",
        "merge_draft", "create_independent", "duplicate", "skip", "relations_accepted", "relations_deferred",
        "relations_ignored", "relations_removed", "relations_failed")
    private val issueFields = setOf("code", "message", "field_path", "blocking_scope", "next_action",
        "after_import_action")
    private val referenceFields = setOf("row_ref", "transaction_id")

    fun view(plan: JsonObject): JsonObject {
        val page = page(plan, "items")
        return buildJsonObject {
            for (field in listOf("plan_id", "edit_context_id", "plan_revision", "expires_at", "item_counts", "can_commit"))
                plan[field]?.let { put(field, it) }
            put("summary", summary(plan["summary"]))
            put("items", page.getValue("items"))
            put("pages", buildJsonObject { put("items", page["next_cursor"] ?: JsonNull) })
            put("invalidated_choices", objectList(plan["invalidated_choices"] ?: JsonArray(emptyList()),
                setOf("kind", "reference", "code")))
            plan["additional_effects"]?.let { put("additional_effects", it) }
        }
    }

    fun page(plan: JsonObject, kind: String, cursor: String? = null, limit: Int = 50,
        confirmationStatus: String? = null): JsonObject {
        if (limit !in 1..200 || (confirmationStatus != null && confirmationStatus !in setOf("all", "confirmed", "pending")))
            fail("plan_page_invalid")
        val selected = confirmationStatus ?: "all"
        val all = plan[kind] as? JsonArray ?: if (kind == "items") fail("plan_build_failed") else JsonArray(emptyList())
        val values = if (kind == "items" && selected != "all") JsonArray(all.filter {
            it.jsonObject["confirmation_status"]?.jsonPrimitive?.contentOrNull == selected
        }) else all
        var offset = 0
        if (cursor != null) {
            try {
                if (cursor.length !in 1..4096) throw IllegalArgumentException("invalid plan cursor")
                val decoded = String(Base64.getUrlDecoder().decode(pad(cursor)), Charsets.UTF_8)
                val data = Json.parseToJsonElement(decoded) as? JsonArray ?: throw IllegalArgumentException("invalid plan cursor")
                if (data.size != 4 || data[0].jsonPrimitive.contentOrNull != plan.getValue("plan_id").jsonPrimitive.content ||
                    data[1].jsonPrimitive.contentOrNull != kind || data[2].jsonPrimitive.contentOrNull != selected)
                    throw IllegalArgumentException("invalid plan cursor")
                offset = data[3].jsonPrimitive.intOrNull ?: throw IllegalArgumentException("invalid plan cursor")
                if (offset !in 0..values.size) throw IllegalArgumentException("invalid plan cursor")
            } catch (_: Exception) { fail("plan_cursor_invalid") }
        }
        if (offset > values.size) fail("plan_page_invalid")
        val next = offset + limit
        val nextCursor = if (next < values.size) {
            val payload = buildString {
                append('[').append(JsonPrimitive(plan.getValue("plan_id").jsonPrimitive.content)).append(',')
                append(JsonPrimitive(kind)).append(',').append(JsonPrimitive(selected)).append(',').append(next).append(']')
            }
            Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
        } else null
        val slice = values.drop(offset).take(limit)
        val items = if (kind == "items") JsonArray(slice.map(::publicItem)) else JsonArray(slice)
        return buildJsonObject { put("items", items); put("next_cursor", nextCursor?.let(::JsonPrimitive) ?: JsonNull) }
    }

    fun item(plan: JsonObject, previewItemId: Any?): JsonObject {
        val identifier = previewItemId as? String ?: fail("plan_item_not_found")
        val detail = (plan["item_details"] as? JsonObject)?.get(identifier) ?: fail("plan_item_not_found")
        return JsonObject(publicDetail(resolveDetail(plan, detail)) + ("preview_item_id" to JsonPrimitive(identifier)))
    }

    fun validateBuild(result: JsonObject) {
        val items = result["items"] as? JsonArray ?: fail("plan_build_failed")
        summary(result["summary"] ?: fail("plan_build_failed"))
        if (result["can_commit"] !is JsonPrimitive || (result["can_commit"] as JsonPrimitive).booleanOrNull == null ||
            (result["can_commit"] as JsonPrimitive).isString) fail("plan_build_failed")
        val ids = mutableSetOf<String>()
        for (item in items) {
            val value = item as? JsonObject ?: fail("plan_build_failed")
            val id = value["preview_item_id"]?.reference(128) ?: fail("plan_build_failed")
            val status = value["confirmation_status"]?.jsonPrimitive?.contentOrNull
            if (status !in setOf("confirmed", "pending")) fail("plan_build_failed")
            if (!ids.add(id)) fail("plan_build_failed")
        }
        validateReadable(result, ids)
    }

    private fun validateReadable(plan: JsonObject, previewIds: Set<String>) {
        val items = plan.getValue("items").jsonArray
        for (item in items) publicItem(item)
        val details = plan["item_details"] as? JsonObject ?: JsonObject(emptyMap())
        if (details.keys.any { it !in previewIds }) fail("plan_build_failed")
        for (item in items) {
            val value = item.jsonObject
            if (value["detail_available"]?.jsonPrimitive?.booleanOrNull == true &&
                value.getValue("preview_item_id").jsonPrimitive.content !in details) fail("plan_build_failed")
        }
        for (detail in details.values) publicDetail(resolveDetail(plan, detail))
        plan["invalidated_choices"]?.let { objectList(it, setOf("kind", "reference", "code")) }
    }

    private fun summary(value: JsonElement?): JsonObject {
        val objectValue = value as? JsonObject ?: fail("plan_build_failed")
        if (objectValue.keys.any { it !in summaryCounts }) fail("plan_build_failed")
        for ((_, count) in objectValue) {
            val primitive = count as? JsonPrimitive ?: fail("plan_build_failed")
            if (primitive.isString || primitive.booleanOrNull != null) fail("plan_build_failed")
            val number = primitive.content.toLongOrNull() ?: fail("plan_build_failed")
            if (number < 0) fail("plan_build_failed")
        }
        return objectValue
    }

    private fun publicItem(value: JsonElement): JsonObject {
        val item = only(value, setOf("preview_item_id", "confirmation_status", "anticipated_state", "display",
            "statistics_eligible", "member_refs", "attention_reasons", "detail_available"))
        return JsonObject(item + mapOf(
            "display" to display(item["display"]),
            "member_refs" to objectList(item["member_refs"] ?: JsonArray(emptyList()), referenceFields),
            "attention_reasons" to issues(item["attention_reasons"] ?: JsonArray(emptyList())),
        ))
    }

    private fun display(value: JsonElement?): JsonObject {
        val result = only(value, setOf("occurred_at", "amount", "currency", "counterparty", "note", "accounts",
            "category_id", "counterparty_account", "counterparty_account_attrs")).toMutableMap()
        val accounts = result["accounts"] ?: JsonArray(emptyList())
        val accountValues = accounts as? JsonArray ?: fail("plan_build_failed")
        if (accountValues.any { it !is JsonPrimitive || !it.isString }) fail("plan_build_failed")
        result["accounts"] = accountValues
        for (field in listOf("occurred_at", "amount", "currency", "counterparty", "note", "category_id", "counterparty_account")) {
            val fieldValue = result[field]
            if (fieldValue == null) result[field] = JsonNull
            else if (fieldValue != JsonNull && (fieldValue !is JsonPrimitive || !fieldValue.isString)) fail("plan_build_failed")
        }
        return JsonObject(result)
    }

    private fun issues(value: JsonElement, defaultScope: String = "row"): JsonArray = JsonArray(objectList(value, issueFields).map { itemValue ->
        val item = itemValue as? JsonObject ?: fail("plan_build_failed")
        val code = item["code"]?.jsonPrimitive?.contentOrNull
        val reason = try {
            cashImportIssue(code, fieldPath = item["field_path"]?.jsonPrimitive?.contentOrNull,
                blockingScope = item["blocking_scope"]?.jsonPrimitive?.contentOrNull ?: defaultScope,
                nextAction = item["next_action"]?.jsonPrimitive?.contentOrNull,
                afterImportAction = item["after_import_action"]?.jsonPrimitive?.contentOrNull)
        } catch (_: IllegalArgumentException) { fail("plan_build_failed") }
        buildJsonObject {
            put("code", reason.code); put("message", reason.message)
            put("field_path", reason.fieldPath?.let(::JsonPrimitive) ?: JsonNull)
            put("blocking_scope", reason.blockingScope); put("next_action", reason.nextAction)
            reason.afterImportAction?.let { put("after_import_action", JsonPrimitive(it)) }
        }
    })

    private fun publicDetail(value: JsonElement?): JsonObject {
        val detail = only(value, setOf("entry", "members", "relation_members", "field_diffs", "issues",
            "relation_choices", "relation_effects")).toMutableMap()
        detail["entry"]?.let { entryValue ->
            val entry = only(entryValue, setOf("fields", "editable_fields", "edit_ref")).toMutableMap()
            entry["fields"] = only(entry["fields"], commonCashFields)
            detail["entry"] = JsonObject(entry)
        }
        for (role in listOf("members", "relation_members")) {
            if (role == "relation_members" && role !in detail) continue
            detail[role] = JsonArray(objectList(detail[role] ?: JsonArray(emptyList()),
                referenceFields + setOf("planned_action", "fields", "editable_fields", "allowed_actions", "row_revision")).map { memberValue ->
                val result = (memberValue as? JsonObject ?: fail("plan_build_failed")).toMutableMap()
                result["fields"]?.let { fields ->
                    val selected = only(fields, mutableCashFields + financialCashFields + identityFields + setOf("state", "row_revision")).toMutableMap()
                    selected["components"]?.let { parts ->
                        selected["components"] = objectList(parts, setOf("component_id", "account_id", "amount", "currency", "ordinal"))
                    }
                    result["fields"] = JsonObject(selected)
                }
                JsonObject(result)
            })
        }
        detail["field_diffs"] = objectList(detail["field_diffs"] ?: JsonArray(emptyList()),
            setOf("field", "current", "incoming", "effective", "resolution", "row_ref", "transaction_id"))
        detail["issues"] = issues(detail["issues"] ?: JsonArray(emptyList()))
        detail["relation_choices"] = relationChoices(detail["relation_choices"] ?: JsonArray(emptyList()))
        detail["relation_effects"] = JsonArray(objectList(detail["relation_effects"] ?: JsonArray(emptyList()),
            setOf("relation_id", "kind", "subtype", "effect", "member_refs", "code")).map { effectValue ->
            val result = (effectValue as? JsonObject ?: fail("plan_build_failed")).toMutableMap()
            result["member_refs"]?.let { result["member_refs"] = objectList(it, referenceFields) }
            JsonObject(result)
        })
        return JsonObject(detail)
    }

    private fun relationChoices(value: JsonElement): JsonArray = JsonArray(objectList(value,
        setOf("suggestion_id", "kind", "subtype", "action", "candidate_id", "candidates", "member_refs",
            "allowed_actions", "code", "common_fields", "common_field_conflicts", "inherit_common_fields",
            "relation_ids", "split_field_diffs", "relations")).map { choiceValue ->
        val result = (choiceValue as? JsonObject ?: fail("plan_build_failed")).toMutableMap()
        result["member_refs"]?.let { result["member_refs"] = objectList(it, referenceFields) }
        result["candidates"]?.let { candidatesValue ->
            result["candidates"] = JsonArray(objectList(candidatesValue, setOf("candidate_id", "display", "member_refs",
                "code", "relations")).map { candidateValue ->
                val item = (candidateValue as? JsonObject ?: fail("plan_build_failed")).toMutableMap()
                item["display"]?.let { item["display"] = display(it) }
                item["member_refs"]?.let { item["member_refs"] = objectList(it, referenceFields) }
                item["relations"]?.let { item["relations"] = relations(it) }
                JsonObject(item)
            })
        }
        result["relations"]?.let { result["relations"] = relations(it) }
        JsonObject(result)
    })

    private fun relations(value: JsonElement): JsonArray = JsonArray(objectList(value, setOf("kind", "subtype", "member_refs")).map { relationValue ->
        val relation = (relationValue as? JsonObject ?: fail("plan_build_failed")).toMutableMap()
        relation["member_refs"] = objectList(relation["member_refs"] ?: JsonArray(emptyList()), referenceFields)
        JsonObject(relation)
    })

    private fun resolveDetail(plan: JsonObject, value: JsonElement): JsonObject {
        val operations = operationIndex(plan)
        val detail = (value as? JsonObject ?: fail("plan_build_failed")).toMutableMap()
        for (role in listOf("members", "relation_members")) {
            if (role == "relation_members" && role !in detail) continue
            val members = (detail[role] as? JsonArray ?: fail("plan_build_failed")).mapIndexed { index, memberValue ->
                val member = (memberValue as? JsonObject ?: fail("plan_build_failed")).toMutableMap()
                val reference = member.remove("fields_ref") as? JsonObject ?: return@mapIndexed JsonObject(member)
                if (reference.keys !in listOf(setOf("operation_ref"), setOf("transaction_id")) || "fields" in member)
                    fail("plan_build_failed")
                val fields: JsonElement? = if ("operation_ref" in reference) {
                    val key = reference["operation_ref"]?.jsonPrimitive?.contentOrNull ?: fail("plan_build_failed")
                    val operation = operations[key] ?: fail("plan_build_failed")
                    val rowRef = member["row_ref"]?.jsonPrimitive?.contentOrNull
                    val transaction = member["transaction_id"]?.jsonPrimitive?.contentOrNull
                    val rows = operation["row_refs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    if (!(rowRef != null && rowRef in rows || transaction != null && transaction == operation["transaction_id"]?.jsonPrimitive?.contentOrNull))
                        fail("plan_build_failed")
                    operation["values"]
                } else {
                    val key = reference["transaction_id"]?.jsonPrimitive?.contentOrNull ?: fail("plan_build_failed")
                    val values = plan["detail_values"] as? JsonObject ?: fail("plan_build_failed")
                    if (key !in values || member["transaction_id"]?.jsonPrimitive?.contentOrNull != key) fail("plan_build_failed")
                    values[key]
                }
                val selected = only(fields, mutableCashFields + financialCashFields + identityFields + setOf("state")).toMutableMap()
                val components = selected["components"] as? JsonArray ?: JsonArray(emptyList())
                selected["components"] = JsonArray(components.map { part ->
                    val item = (part as? JsonObject ?: fail("plan_build_failed")).toMutableMap()
                    val identifier = item["component_id"]
                    val text = identifier?.jsonPrimitive?.contentOrNull
                    if (text != null && text.all { it in '0'..'9' } &&
                        text.toBigIntegerOrNull()?.let { it >= java.math.BigInteger.ONE.shiftLeft(63) } == true)
                        item.remove("component_id")
                    JsonObject(item)
                })
                member["fields"] = JsonObject(selected)
                JsonObject(member)
            }
            detail[role] = JsonArray(members)
        }
        return JsonObject(detail)
    }

    private fun operationIndex(plan: JsonObject): Map<String, JsonObject> {
        val operations = plan["operations"] ?: JsonArray(emptyList())
        if (operations !is JsonArray || plan["detail_values"]?.let { it !is JsonObject } == true) fail("plan_build_failed")
        val result = linkedMapOf<String, JsonObject>()
        for (operation in operations) {
            val value = operation as? JsonObject ?: fail("plan_build_failed")
            val reference = value["operation_ref"]?.reference(128) ?: fail("plan_build_failed")
            if (result.putIfAbsent(reference, value) != null) fail("plan_build_failed")
        }
        return result
    }

    private fun only(value: JsonElement?, fields: Set<String>): JsonObject {
        val objectValue = value as? JsonObject ?: fail("plan_build_failed")
        return JsonObject(objectValue.filterKeys { it in fields })
    }

    private fun objectList(value: JsonElement?, fields: Set<String>): JsonArray {
        val array = value as? JsonArray ?: fail("plan_build_failed")
        return JsonArray(array.map { item -> only(item, fields) })
    }

    private fun JsonElement?.reference(maximum: Int): String? {
        if (this !is JsonPrimitive || !isString) return null
        return content.takeIf { it.matches(Regex("[A-Za-z0-9_:.~-]{1,$maximum}")) }
    }

    private fun pad(value: String) = value + "=".repeat((4 - value.length % 4) % 4)

    private fun fail(code: String): Nothing = throw MutationPlanFailure(code)
}
