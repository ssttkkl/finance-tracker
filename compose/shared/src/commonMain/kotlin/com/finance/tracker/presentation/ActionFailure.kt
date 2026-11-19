package com.finance.tracker.presentation

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.FailureCategory

enum class ActionFailurePresentation { SHORT, PERSISTENT, UNKNOWN }

data class ActionFailure(val code: String, val presentation: ActionFailurePresentation) {
    val short: Boolean get() = presentation == ActionFailurePresentation.SHORT
    val unknown: Boolean get() = presentation == ActionFailurePresentation.UNKNOWN
    val needsLedgerReview: Boolean get() =
        unknown || code in setOf("projection.version_conflict", "projection_version_conflict", "category.not_found")
}

internal fun ActionFailure?.afterRefreshingFacts(): ActionFailure? =
    if (this?.unknown == true) ActionFailure("action_facts_refreshed", ActionFailurePresentation.SHORT) else null

private val failuresRequiringReview = setOf(
    "authentication_required", "workspace_forbidden", "workspace_name_mismatch",
    "category.has_children", "category.not_found", "projection.unavailable",
)

internal fun Throwable.readActionFailure(): ActionFailure {
    val failure = this as? DomainFailure
    val code = failure?.takeIf { it.category == FailureCategory.RECOVERABLE }?.code ?: "unknown_error"
    val presentation = if (code in setOf("network_unavailable", "service_unavailable", "storage.busy", "storage.connect")) {
        ActionFailurePresentation.SHORT
    } else ActionFailurePresentation.PERSISTENT
    return ActionFailure(code, presentation)
}

internal fun Throwable.writeActionFailure(): ActionFailure {
    val failure = this as? DomainFailure
    val code = failure?.takeIf { it.category == FailureCategory.RECOVERABLE }?.code ?: "unknown_error"
    val presentation = when {
        failure == null || failure.commitState == "unknown" -> ActionFailurePresentation.UNKNOWN
        failure.commitState != "not_committed" && (failure.status !in 400..499 || failure.status == 408) ->
            ActionFailurePresentation.UNKNOWN
        failure.category != FailureCategory.RECOVERABLE || code in failuresRequiringReview ||
            failure.status in setOf(401, 403, 404, 409, 410, 422) -> ActionFailurePresentation.PERSISTENT
        failure.commitState == "not_committed" -> ActionFailurePresentation.SHORT
        else -> ActionFailurePresentation.PERSISTENT
    }
    return ActionFailure(code, presentation)
}

@Composable
internal fun ActionFailureStatus(
    failure: ActionFailure,
    message: String,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
    refreshError: String? = null,
) {
    StateMessage(if (failure.unknown) localizedText("action_result_check") else message, isError = true) {
        InlineError(refreshError)
        if (failure.code != "unknown_error" || failure.unknown) {
            TextButton(onClick = onRefresh, enabled = canRefresh) {
                Text(localizedText("ledger_refresh"))
            }
        }
    }
}
