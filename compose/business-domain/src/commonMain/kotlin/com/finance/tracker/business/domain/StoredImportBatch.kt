package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.StoredImportBatch
import kotlinx.serialization.json.*

fun decodeStoredImportBatch(text: String): StoredImportBatch {
    val root = Json.parseToJsonElement(text)
    if (root !is JsonArray) root.jsonObject["attempt"]?.let {
        check(it is JsonPrimitive && !it.isString && it.intOrNull?.let { n -> n >= 0 && n.toString() == it.content } == true) { "cash_import_batch_invalid" }
    }
    val batch = if (root is JsonArray) StoredImportBatch(1, Json.decodeFromJsonElement(root))
        else Json.decodeFromJsonElement<StoredImportBatch>(root)
    check(batch.version == 1 && (root !is JsonArray || batch.files.isNotEmpty())) { "cash_import_batch_invalid" }
    return batch
}
