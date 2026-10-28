package com.finance.tracker.presentation

import com.finance.tracker.domain.*

internal fun cashDraftEditingIssues(record: CashCurrentRecord): List<CashRecordIssue> {
    if (record.state != "draft") return emptyList()
    val issues = record.issues.filter { it.nextAction == "edit" }
    val specific = issues.filter { it.code !in setOf("incomplete_record", "posting_incomplete") }
    return specific.ifEmpty { issues }.distinctBy { it.message }
}

internal fun cashIssueRequest(record: CashCurrentRecord, issueId: String, resolution: String, targetId: String? = null,
    patch: StructuredObject = StructuredObject(emptyMap())): CashMaintenanceRequest {
    fun invalid(): Nothing = throw DomainFailure("choice_invalid", 0, FailureCategory.RECOVERABLE)
    val issue = record.issues.singleOrNull { it.issueId == issueId } ?: invalid()
    if (patch.fields.keys.any { it !in record.editableFields }) invalid()
    if (resolution !in issue.allowedResolutions) invalid()
    val target = if (resolution == "merge_into") issue.candidates.singleOrNull { it.transactionId == targetId } ?: invalid()
        else null.also { if (targetId != null) invalid() }
    return CashMaintenanceRequest("resolve_issue", record.transactionId, record.rowRevision, patch,
        issueResolutions = listOf(CashIssueResolution(issue.issueId, issue.revision, resolution, target?.transactionId, target?.rowRevision)))
}
