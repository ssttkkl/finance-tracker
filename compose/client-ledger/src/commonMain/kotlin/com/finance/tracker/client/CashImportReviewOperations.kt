package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashImportPlanDto
import com.finance.tracker.business.contracts.CashPlanItemPageDto
import com.finance.tracker.business.contracts.CashPlanItemDetailDto
import com.finance.tracker.business.contracts.CashPlanProcessingPageDto
import com.finance.tracker.business.contracts.CashImportPreviewInput
import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.LocalCashImportPlanReader
import com.finance.tracker.business.domain.LocalCashImportResultInput
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.business.domain.recalculate
import com.finance.tracker.business.domain.ParsedImportFile
import com.finance.tracker.business.domain.calculateLocalCashImportBusinessPlan
import com.finance.tracker.business.domain.freezeLocalCashImportPreviewPlan
import com.finance.tracker.business.domain.identifyCashImportPreview
import com.finance.tracker.business.domain.parsePythonCashTimestamp
import com.finance.tracker.business.domain.summarizeLocalCashImportMonths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

/** Original calculation and recovery inputs from one committed snapshot. */
class CashImportCalculation(
    val plan: JsonObject,
    val review: LocalCashImportReview,
    val request: CashWriteSetRequestDto,
    val previousReviewFor: (JsonObject) -> LocalCashImportReview,
)

fun calculateCashImportReview(
    files: List<ParsedImportFile>,
    snapshot: LedgerSnapshotDto,
    input: JsonObject,
    previous: LocalCashImportReview?,
    requireActive: () -> Unit,
): CashImportCalculation {
    val choices = freezeCashImportChoices(input)
    val (plan, changes) = calculateLocalCashImportBusinessPlan(
        files, snapshot, choices, previous?.previousPlan().orEmpty(), previous?.recordIds().orEmpty(),
        requireActive, Clock.System::now,
    )
    return CashImportCalculation(
        freezeLocalCashImportPreviewPlan(plan),
        LocalCashImportReview.freeze(choices, plan, snapshot, changes),
        CashWriteSetRequestDto(snapshot.ledgerSeq, CASH_RULES_VERSION, changes),
        LocalCashImportReview.forPreview(plan),
    )
}

fun rebaseCashImportReview(
    files: List<ParsedImportFile>,
    snapshot: LedgerSnapshotDto,
    review: LocalCashImportReview,
    requireActive: () -> Unit,
): CashWriteSetRequestDto {
    val result = review.recalculate(snapshot) { facts, choices, previous, ids ->
        calculateLocalCashImportBusinessPlan(files, facts, choices, previous, ids, requireActive, Clock.System::now)
    }
    if (!result.conclusionUnchanged) throw CashImportPlanError("plan_changed")
    return CashWriteSetRequestDto(snapshot.ledgerSeq, CASH_RULES_VERSION, result.changes)
}

fun freezeCashImportChoices(input: JsonObject): JsonObject {
    val copied = Json.parseToJsonElement(input.toString()).jsonObject
    return JsonObject(listOf("account_selections", "row_choices", "relation_choices", "entry_choices")
        .associateWith { copied[it] ?: JsonArray(emptyList()) })
}

fun cashImportPlanReader(plan: JsonObject, calendar: CashLedgerCalendar): LocalCashImportPlanReader =
    LocalCashImportPlanReader(plan) { rows, zone ->
        calendar.validateImportZone(zone)
        summarizeLocalCashImportMonths(rows) { text -> calendar.monthAt(parsePythonCashTimestamp(text, allowNaive = true), zone) }
    }

class CashImportReviewOperations(private val workspaceId: String, private val calendar: CashLedgerCalendar) {
    class Review internal constructor(
        val workspaceId: String,
        val plan: CashImportPlanDto,
        val calculation: CashImportCalculation,
        private val reader: LocalCashImportPlanReader,
    ) {
        fun items(cursor: String?, limit: Int, status: String = "all"): CashPlanItemPageDto =
            clientJson.decodeFromJsonElement(CashPlanItemPageDto.serializer(), reader.page("items", cursor, limit = limit, confirmationStatus = status))

        fun item(id: String): CashPlanItemDetailDto =
            clientJson.decodeFromJsonElement(CashPlanItemDetailDto.serializer(), reader.detail(id))

        fun processing(cursor: String?, limit: Int): CashPlanProcessingPageDto =
            clientJson.decodeFromJsonElement(CashPlanProcessingPageDto.serializer(), reader.page("processing_details", cursor, limit = limit))

        fun resultInput(): LocalCashImportResultInput = LocalCashImportResultInput.freeze(calculation.plan)
    }

    fun review(
        snapshot: LedgerSnapshotDto,
        batchId: String,
        planId: String,
        revision: Long,
        files: List<ParsedImportFile>,
        choices: CashImportPreviewInput,
        previous: LocalCashImportReview? = null,
        requireActive: () -> Unit = {},
    ): Review {
        if (workspaceId != snapshot.workspaceId) throw ClientOperationFailure("workspace_conflict")
        if (choices.expectedSessionRevision != revision || revision < 1) throw CashImportPlanError("plan_changed")
        val input = clientJson.encodeToJsonElement(CashImportPreviewInput.serializer(), choices).jsonObject
        val computation = calculateCashImportReview(files, snapshot, input, previous, requireActive)
        val reader = cashImportPlanReader(identifyCashImportPreview(computation.plan, planId, batchId, revision), calendar)
        reader.validateReadable()
        val plan = clientJson.decodeFromJsonElement(CashImportPlanDto.serializer(), reader.view(choices.timezoneName))
        return Review(workspaceId, plan, computation, reader)
    }
}
