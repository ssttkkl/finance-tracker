package com.finance.tracker

import com.finance.tracker.business.domain.LocalCashImportReview
import kotlinx.serialization.json.*

internal fun localCashImportReviewFixture(note: String = "sensitive-review-marker"): LocalCashImportReview = LocalCashImportReview.freeze(
    Json.parseToJsonElement("""{"account_selections":[{"source_account_ref":"funding","account_id":"7","expected_account_revision":9007199254740993}],"row_choices":[{"row_ref":"file:row","action":"apply","patch":{"note":"$note","category_id":null}}],"relation_choices":[],"entry_choices":[]}""").jsonObject,
    emptyMap(),
)

// Storage tests use the fixed complete-fixture digest; domain tests calculate it from the actual full builder.
internal fun localCashImportConfirmedReviewFixture(note: String = "sensitive-review-marker"): LocalCashImportReview {
    val old = Json.parseToJsonElement(localCashImportReviewFixture(note).encode()).jsonObject
    return LocalCashImportReview.decode(JsonObject(old + mapOf("version" to JsonPrimitive(2),
        "conclusion" to JsonPrimitive("79c73a2c64889a1616dff61b08f28c74a303028bd3698b47d4a88ce84ddbe4f8"))).toString())
}
