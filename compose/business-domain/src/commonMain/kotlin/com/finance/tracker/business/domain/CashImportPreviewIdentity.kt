package com.finance.tracker.business.domain

import kotlinx.serialization.json.*

fun identifyCashImportPreview(plan: JsonObject, id: String, batchId: String, revision: Long): JsonObject {
    val items = plan.getValue("items").jsonArray
    return JsonObject(plan + mapOf(
        "plan_id" to JsonPrimitive(id), "session_id" to JsonPrimitive(batchId),
        "session_revision" to JsonPrimitive(revision), "expires_at" to JsonNull,
        "item_counts" to buildJsonObject {
            for (status in listOf("confirmed", "pending")) put(status,
                items.count { it.jsonObject.getValue("confirmation_status").jsonPrimitive.content == status })
        },
    ))
}
