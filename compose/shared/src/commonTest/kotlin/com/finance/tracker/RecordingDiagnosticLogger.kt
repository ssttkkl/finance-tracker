package com.finance.tracker

import com.finance.tracker.core.DiagnosticAction
import com.finance.tracker.core.DiagnosticFeature
import com.finance.tracker.core.DiagnosticLogger

internal data class RecordedDiagnostic(
    val feature: DiagnosticFeature,
    val action: DiagnosticAction,
    val errorCode: String?,
    val status: Int?,
    val exceptionType: String?,
)

internal class RecordingDiagnosticLogger : DiagnosticLogger {
    val entries = mutableListOf<RecordedDiagnostic>()

    override fun record(
        feature: DiagnosticFeature,
        action: DiagnosticAction,
        errorCode: String?,
        status: Int?,
        exceptionType: String?,
    ) {
        entries += RecordedDiagnostic(feature, action, errorCode, status, exceptionType)
    }
}
