package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashWriteSetDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import kotlinx.serialization.json.*

/** A recalculated review, not a submission permit. Receipt verification and original-operation recovery remain separate. */
class LocalCashImportRecalculation internal constructor(val input: JsonObject, val plan: Map<String, Any?>,
    val changes: CashWriteSetDto, val conclusionUnchanged: Boolean)

/** Run the original calculation on one snapshot, restoring relations before entry edits and comparing the full result. */
fun LocalCashImportReview.recalculate(source: LedgerSnapshotDto,
    calculate: (LedgerSnapshotDto, JsonObject, Map<String, Any?>, Map<String, String>) -> Pair<Map<String, Any?>, CashWriteSetDto>,
): LocalCashImportRecalculation {
    val original = conclusion() ?: throw CashImportPlanError("plan_changed")
    val undecided = JsonObject(inputJson() + mapOf("relation_choices" to JsonArray(emptyList()), "entry_choices" to JsonArray(emptyList())))
    val fresh = calculate(source, undecided, previousPlan(), recordIds())
    val relations = rebindRelations(source, fresh.first, fresh.second)
    val decided = calculate(source, relations.inputJson(), relations.previousPlan(), recordIds())
    val entries = rebindEntries(source, decided.first, decided.second, relations)
    val input = entries.inputJson()
    val final = calculate(source, input, entries.previousPlan(), recordIds())
    val current = localCashImportConclusion(source, freezeLocalCashImportPreviewPlan(final.first), final.second)
    return LocalCashImportRecalculation(input, final.first, final.second, current == original)
}
