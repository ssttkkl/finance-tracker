package com.finance.tracker.presentation

import com.finance.tracker.core.DiagnosticAction
import com.finance.tracker.core.DiagnosticFeature
import com.finance.tracker.core.DiagnosticLogger
import com.finance.tracker.domain.DomainFailure

internal fun Throwable.recordDiagnosticFailure(
    logger: DiagnosticLogger,
    feature: DiagnosticFeature,
    action: DiagnosticAction,
) {
    val failure = this as? DomainFailure
    logger.record(
        feature = feature,
        action = action,
        errorCode = failure?.code,
        status = failure?.status,
        exceptionType = this::class.simpleName,
    )
}

internal fun DiagnosticLogger.recordInputValidationFailure(
    feature: DiagnosticFeature,
    errorCode: String,
) {
    record(
        feature = feature,
        action = DiagnosticAction.VALIDATE_INPUT,
        errorCode = errorCode,
        status = null,
        exceptionType = "InputValidationFailure",
    )
}
