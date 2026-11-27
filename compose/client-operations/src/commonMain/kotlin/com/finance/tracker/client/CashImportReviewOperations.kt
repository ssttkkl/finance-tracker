package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashImportPlanDto
import com.finance.tracker.business.contracts.CashPlanItemPageDto
import com.finance.tracker.business.contracts.CashPlanItemDetailDto
import com.finance.tracker.business.contracts.CashPlanProcessingPageDto
import com.finance.tracker.business.contracts.CashImportPreviewInput
import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.StoredCashImportSubmission
import com.finance.tracker.business.domain.cashWriteSetJson
import com.finance.tracker.business.domain.isCashWriteSetKey
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.CashImportPlanningStage
import com.finance.tracker.business.domain.cashWriteSetFitsBudget
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

/** Original calculation and recovery inputs from one committed snapshot. */
class CashImportCalculation(
    val plan: JsonObject,
    val review: LocalCashImportReview,
    val request: CashWriteSetRequestDto,
    val previousReviewFor: (JsonObject) -> LocalCashImportReview,
    val previewContext: JsonElement? = null,
    val requestBody: String? = null,
)

fun calculateCashImportReview(
    files: List<ParsedImportFile>,
    snapshot: LedgerSnapshotDto,
    input: JsonObject,
    previous: LocalCashImportReview?,
    includePreviewContext: Boolean = false,
    progress: (CashImportPlanningStage) -> Unit = {},
    requireActive: () -> Unit,
): CashImportCalculation {
    val choices = freezeCashImportChoices(input)
    val (plan, changes) = calculateLocalCashImportBusinessPlan(
        files, snapshot, choices, previous?.previousPlan().orEmpty(), previous?.recordIds().orEmpty(),
        requireActive, progress, Clock.System::now,
    )
    val request = CashWriteSetRequestDto(snapshot.ledgerSeq, CASH_RULES_VERSION, changes)
    val body = if (plan["can_commit"] == true) encodeCashImportRequest(request) else null
    val frozenPlan = freezeLocalCashImportPreviewPlan(plan)
    return CashImportCalculation(
        frozenPlan,
        LocalCashImportReview.freeze(choices, plan, snapshot, changes, frozenPlan),
        request,
        LocalCashImportReview.forPreview(plan),
        if (includePreviewContext) LocalCashImportReview.encodePreviewContext(plan) else null,
        body,
    )
}

fun rebaseCashImportReview(
    files: List<ParsedImportFile>,
    snapshot: LedgerSnapshotDto,
    review: LocalCashImportReview,
    requireActive: () -> Unit,
): CashWriteSetRequestDto {
    val result = review.recalculate(snapshot) { facts, choices, previous, ids ->
        calculateLocalCashImportBusinessPlan(files, facts, choices, previous, ids, requireActive, now = Clock.System::now)
    }
    if (!result.conclusionUnchanged) throw CashImportPlanError("plan_changed")
    return CashWriteSetRequestDto(snapshot.ledgerSeq, CASH_RULES_VERSION, result.changes)
}

/** Freeze the exact confirmed request and the result context used to present its permanent receipt. */
fun freezeCashImportConfirmation(
    key: String, canCommit: Boolean, request: CashWriteSetRequestDto, resultPlan: JsonObject,
): StoredCashImportSubmission {
    if (!canCommit) throw CashImportPlanError("plan_changed")
    if (!isCashWriteSetKey(key)) throw CashImportPlanError("plan_input_invalid")
    return StoredCashImportSubmission(key, encodeCashImportRequest(request), null, false,
        LocalCashImportResultInput.freeze(resultPlan).encode(), null)
}

private val cashImportJsonWithDefaultsOmitted = Json(cashWriteSetJson) { encodeDefaults = false }

fun encodeCashImportRequest(request: CashWriteSetRequestDto): String {
    val full = cashWriteSetJson.encodeToString(request)
    if (cashWriteSetFitsBudget(full)) return full
    val withoutDefaults = cashImportJsonWithDefaultsOmitted.encodeToString(request)
    if (cashWriteSetFitsBudget(withoutDefaults)) return withoutDefaults
    throw CashImportPlanError("payload_too_large", "not_committed")
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
        progress: (CashImportPlanningStage) -> Unit = {},
        requireActive: () -> Unit = {},
    ): Review {
        if (workspaceId != snapshot.workspaceId) throw ClientOperationFailure("workspace_conflict")
        if (choices.expectedSessionRevision != revision || revision < 1) throw CashImportPlanError("plan_changed")
        val input = clientJson.encodeToJsonElement(CashImportPreviewInput.serializer(), choices).jsonObject
        val computation = calculateCashImportReview(files, snapshot, input, previous, progress = progress, requireActive = requireActive)
        val reader = cashImportPlanReader(identifyCashImportPreview(computation.plan, planId, batchId, revision), calendar)
        reader.validateReadable()
        val plan = clientJson.decodeFromJsonElement(CashImportPlanDto.serializer(), reader.view(choices.timezoneName))
        return Review(workspaceId, plan, computation, reader)
    }
}
