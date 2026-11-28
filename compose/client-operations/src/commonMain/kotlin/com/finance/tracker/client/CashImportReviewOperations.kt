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
import com.finance.tracker.business.domain.CASH_WRITE_SET_MAX_BYTES
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.CashImportPlanningStage
import com.finance.tracker.business.domain.CashImportReviewWorkspace
import com.finance.tracker.business.domain.cashWriteSetFitsBudget
import com.finance.tracker.business.domain.LocalCashImportPlanReader
import com.finance.tracker.business.domain.LocalCashImportResultInput
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.business.domain.recalculate
import com.finance.tracker.business.domain.ParsedImportFile
import com.finance.tracker.business.domain.calculateLocalCashImportBusinessPlan
import com.finance.tracker.business.domain.prepareLocalCashImportReview
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
): CashImportCalculation = calculateCashImportReview(files, snapshot, input, previous, includePreviewContext, progress, requireActive, null)

private fun calculateCashImportReview(
    files: List<ParsedImportFile>, snapshot: LedgerSnapshotDto, input: JsonObject, previous: LocalCashImportReview?,
    includePreviewContext: Boolean, progress: (CashImportPlanningStage) -> Unit, requireActive: () -> Unit,
    workspace: CashImportReviewWorkspace?, sample: (String) -> Unit = {},
): CashImportCalculation {
    val choices = freezeCashImportChoices(input)
    val prepared = prepareLocalCashImportReview(
        files, snapshot, choices, previous?.previousPlan().orEmpty(), previous?.recordIds().orEmpty(),
        includePreviewContext, requireActive, progress, Clock.System::now, workspace, sample,
    )
    val request = CashWriteSetRequestDto(snapshot.ledgerSeq, CASH_RULES_VERSION, prepared.changes)
    val body = if (prepared.canCommit) encodeCashImportRequest(request) else null
    sample("request_body")
    val review = prepared.confirmedReview()
    sample("conclusion")
    return CashImportCalculation(prepared.plan, review, request,
        prepared.previousReviewFor, prepared.previewContext, body)
}

fun rebaseCashImportReview(
    files: List<ParsedImportFile>,
    snapshot: LedgerSnapshotDto,
    review: LocalCashImportReview,
    requireActive: () -> Unit,
): CashWriteSetRequestDto {
    val evaluatedAt = Clock.System.now()
    val result = review.recalculate(snapshot) { facts, choices, previous, ids ->
        calculateLocalCashImportBusinessPlan(files, facts, choices, previous, ids, requireActive, now = { evaluatedAt })
    }
    requireActive()
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
    if (requiredCashImportStringsExceed(request, CASH_WRITE_SET_MAX_BYTES))
        throw CashImportPlanError("payload_too_large", "not_committed")
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
    cashImportPlanReader(plan, calendar, false)

private fun cashImportPlanReader(plan: JsonObject, calendar: CashLedgerCalendar, stored: Boolean): LocalCashImportPlanReader =
    LocalCashImportPlanReader(plan, stored) { rows, zone ->
        calendar.validateImportZone(zone)
        summarizeLocalCashImportMonths(rows) { text -> calendar.monthAt(parsePythonCashTimestamp(text, allowNaive = true), zone) }
    }

class CashImportReviewView internal constructor(private val reader: LocalCashImportPlanReader) {
    fun items(cursor: String?, limit: Int, status: String = "all"): CashPlanItemPageDto =
        clientJson.decodeFromJsonElement(CashPlanItemPageDto.serializer(), reader.page("items", cursor, limit = limit, confirmationStatus = status))

    fun item(id: String): CashPlanItemDetailDto =
        clientJson.decodeFromJsonElement(CashPlanItemDetailDto.serializer(), reader.detail(id))

    fun processing(cursor: String?, limit: Int): CashPlanProcessingPageDto =
        clientJson.decodeFromJsonElement(CashPlanProcessingPageDto.serializer(), reader.page("processing_details", cursor, limit = limit))
}

class CashImportReviewOperations(private val workspaceId: String, private val calendar: CashLedgerCalendar) {
    class Review internal constructor(
        val workspaceId: String,
        val plan: CashImportPlanDto,
        val calculation: CashImportCalculation,
        internal val reads: CashImportReviewView,
    ) {
        fun items(cursor: String?, limit: Int, status: String = "all"): CashPlanItemPageDto =
            reads.items(cursor, limit, status)

        fun item(id: String): CashPlanItemDetailDto = reads.item(id)

        fun processing(cursor: String?, limit: Int): CashPlanProcessingPageDto = reads.processing(cursor, limit)

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
    ): Review = review(snapshot, batchId, planId, revision, files, choices, previous, progress, requireActive, null)

    internal fun review(
        snapshot: LedgerSnapshotDto, batchId: String, planId: String, revision: Long, files: List<ParsedImportFile>,
        choices: CashImportPreviewInput, previous: LocalCashImportReview?,
        progress: (CashImportPlanningStage) -> Unit, requireActive: () -> Unit, workspace: CashImportReviewWorkspace?,
        sample: (String) -> Unit = {},
    ): Review {
        if (workspaceId != snapshot.workspaceId) throw ClientOperationFailure("workspace_conflict")
        if (choices.expectedSessionRevision != revision || revision < 1) throw CashImportPlanError("plan_changed")
        val input = clientJson.encodeToJsonElement(CashImportPreviewInput.serializer(), choices).jsonObject
        val computation = calculateCashImportReview(files, snapshot, input, previous, false, progress, requireActive, workspace, sample)
        val reader = cashImportPlanReader(identifyCashImportPreview(computation.plan, planId, batchId, revision), calendar, workspace != null)
        reader.validateReadable()
        val plan = clientJson.decodeFromJsonElement(CashImportPlanDto.serializer(), reader.view(choices.timezoneName))
        sample("display_preview")
        return Review(workspaceId, plan, computation, CashImportReviewView(reader))
    }
}
