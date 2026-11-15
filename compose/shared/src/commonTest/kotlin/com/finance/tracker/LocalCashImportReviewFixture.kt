package com.finance.tracker

import com.finance.tracker.business.domain.LocalCashImportReview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal fun localCashImportReviewFixture(note: String = "sensitive-review-marker"): LocalCashImportReview = LocalCashImportReview.freeze(
    Json.parseToJsonElement("""{"account_selections":[{"source_account_ref":"funding","account_id":"7","expected_account_revision":9007199254740993}],"row_choices":[{"row_ref":"file:row","action":"apply","patch":{"note":"$note","category_id":null}}],"relation_choices":[],"entry_choices":[]}""").jsonObject,
    emptyMap(),
)
