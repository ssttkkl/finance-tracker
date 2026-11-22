package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.*
import com.finance.tracker.domain.*

@Composable
internal fun CashDraftEditor(record: CashCurrentRecord, savedRevision: Int, accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions,
    sizeClass: WindowSizeClass, busy: Boolean, onInput: (Boolean, StructuredObject?) -> Unit, onSave: (StructuredObject, Boolean) -> Unit,
    serverErrorCode: String? = null) {
    val connection = LocalSessionConnection.current
    val initial = remember(record.transactionId, record.rowRevision, savedRevision) { cashDraftInitialValues(record) }
    var values by remember(record.transactionId, record.rowRevision, savedRevision) { mutableStateOf(initial) }
    val initialAllocations = remember(record.transactionId, record.rowRevision, savedRevision) { cashDraftInitialAllocations(record) }
    var allocationInput by remember(record.transactionId, record.rowRevision, savedRevision) { mutableStateOf(CashDraftAllocationInput(initialAllocations)) }
    var error by remember(record.transactionId, record.rowRevision, savedRevision) { mutableStateOf<String?>(null) }
    var errorRevision by remember(record.transactionId, record.rowRevision, savedRevision) { mutableIntStateOf(0) }
    val split = values["cash_granularity"] == "aggregate"
    var fieldErrors by remember(record.transactionId, record.rowRevision, savedRevision) { mutableStateOf<List<CashFormError>>(emptyList()) }
    var dismissedServerError by remember(serverErrorCode, record.transactionId, record.rowRevision, savedRevision) { mutableStateOf(false) }
    val effectiveError = error ?: serverErrorCode.takeUnless { dismissedServerError }
    val serverField = serverErrorCode?.takeUnless { dismissedServerError }?.let { cashFieldErrorKeyFor(it, split = split) }
    val effectiveErrors = (fieldErrors + listOfNotNull(serverField?.let { CashFormError(it.field, it.messageKey) }))
        .distinctBy { it.field }
    val fieldErrorMessages = effectiveErrors.associate { it.field to localizedText(it.messageKey) }
    fun clearFieldError(field: String) {
        fieldErrors = fieldErrors.filterNot { it.field == field || it.field == "components" && field == "cash_granularity" }
        if (serverField?.field == field || serverField?.field == "components" && field == "cash_granularity") {
            dismissedServerError = true
        }
    }
    val changes = values.filter { (field, value) -> initial[field] != value }.toMutableMap().apply {
        if (split && initial["account_id"].orEmpty().isNotEmpty()) put("account_id", "")
        else if (split) remove("account_id")
    }
    val allocationChanges = allocationInput.patchParts(split, values, initialAllocations)
    val effectiveAllocations = if (split) allocationInput.parts else allocationChanges ?: initialAllocations
    val dirty = changes.isNotEmpty() || allocationChanges != null
    PlatformControlAccessibility("cash.draft.save", ControlAccessibilityState.Button(connection.canSubmit && !busy && dirty))
    val zone = getLocalTimeZoneId()
    val currentPatch = remember(record, changes, allocationChanges, zone) { runCatching { cashDraftPatch(record, changes, zone, allocationChanges).also { validateCashDraftFinancialPatch(record, it, accounts) } }.getOrNull() }
    LaunchedEffect(dirty, currentPatch) { onInput(dirty, currentPatch) }
    fun change(field: String, value: String) {
        if (field == "cash_granularity") allocationInput = allocationInput.switchMode()
        clearFieldError(field)
        values = values + (field to value)
    }
    fun save(finalize: Boolean) {
        if (!connection.canSubmit) return
        val preflight = buildList {
            addAll(validateCashScalarChanges(changes, zone))
            validateCashAllocation(if (split) allocationInput.parts else effectiveAllocations)?.let(::add)
        }
        if (preflight.isNotEmpty()) {
            fieldErrors = preflight
            error = null
            errorRevision++
            dismissedServerError = false
            return
        }
        val patch = try {
            cashDraftPatch(record, changes, zone, allocationChanges).also { validateCashDraftFinancialPatch(record, it, accounts) }
        } catch (failure: DomainFailure) {
            val mapped = cashFieldErrorKeyFor(failure.code, split = split)
            fieldErrors = mapped?.let { listOf(CashFormError(it.field, it.messageKey)) }.orEmpty()
            error = failure.code.takeIf { mapped == null }
            errorRevision++
            dismissedServerError = false
            return
        }
        onSave(patch, finalize)
    }
    Column(Modifier.fillMaxWidth().testTag("cash.draft.editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CashRecordFieldInputs(values, record.editableFields, accounts, categories, options, sizeClass, busy, "cash.draft",
            fieldErrors = fieldErrorMessages, errorRevision = errorRevision,
            bookingContent = if ("components" in record.editableFields) ({
            CashDraftAllocationEditor(split, effectiveAllocations, accounts, busy,
                singleNeedsRepair = !allocationInput.singleChosen && !cashDraftSingleAllocationMatches(initial, initialAllocations),
                onChange = { allocationInput = allocationInput.changeParts(it); clearFieldError("components") },
                onUseSingle = { allocationInput = allocationInput.chooseSingle(); clearFieldError("components") })
        }) else null, onChange = ::change)
        if ((effectiveErrors.isEmpty() || effectiveErrors.any { it.field == "components" } && "components" !in record.editableFields) && effectiveError != null) effectiveError?.let { Text(when (it) { "invalid_datetime" -> localizedText("cash_invalid_local_time"); "invalid_amount" -> localizedText("copy_d9ea7affdf"); "invalid_currency" -> localizedText("cash_invalid_currency"); "invalid_allocation" -> localizedText("cash_import_invalid_allocation"); else -> ledgerErrorText(it) }, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cash.draft.error")) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { save(false) }, enabled = connection.canSubmit && !busy && dirty, border = financeInteractiveBorder(connection.canSubmit && !busy && dirty),
                modifier = Modifier.testTag("cash.draft.save")) { Text(localizedText("cash_save_pending")) }
            PlatformControlAccessibility("cash.draft.finalize", ControlAccessibilityState.Button(connection.canSubmit && !busy))
            Button(onClick = { save(true) }, enabled = connection.canSubmit && !busy, modifier = Modifier.testTag("cash.draft.finalize")) { Text(localizedText("cash_finalize_record")) }
        }
    }
}
