package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.cashIssueRequest
import com.finance.tracker.presentation.cashAccountAttributeLabelKey
import com.finance.tracker.presentation.cashDraftEditingIssues
import kotlin.test.*

class CashIssueActionTest {
    @Test fun draftEditProblemsUseCurrentActionAndPreferSpecificReasons() {
        val base = record().issues.single()
        val specific = base.copy(issueId = "32", code = "account_missing", nextAction = "edit", allowedResolutions = emptyList())
        val incomplete = base.copy(issueId = "33", code = "incomplete_record", nextAction = "edit", allowedResolutions = emptyList())
        val current = record().copy(issues = listOf(base, specific, incomplete))
        assertEquals(listOf(specific), cashDraftEditingIssues(current))
        assertEquals(listOf(incomplete), cashDraftEditingIssues(current.copy(issues = listOf(incomplete))))
        assertTrue(cashDraftEditingIssues(current.copy(state = "posted")).isEmpty())
    }
    @Test fun accountAttributesHaveBusinessLabelsAndUnknownValuesAreHidden() {
        assertEquals("cash_account_full", cashAccountAttributeLabelKey("full"))
        assertEquals("cash_account_tail", cashAccountAttributeLabelKey("tail"))
        assertEquals("cash_account_masked", cashAccountAttributeLabelKey("masked"))
        assertEquals("cash_account_reconstructed", cashAccountAttributeLabelKey("reconstructed"))
        assertNull(cashAccountAttributeLabelKey("new_protocol_value"))
    }
    @Test fun sourceChoiceUsesCurrentIssueAndRowRevision() {
        val request = cashIssueRequest(record(), "31", "keep_existing")
        assertEquals("resolve_issue", request.intent)
        assertEquals(7L, request.expectedRevision)
        assertEquals(listOf(CashIssueResolution("31", 4, "keep_existing")), request.issueResolutions)
    }
    @Test fun rejectsUnadvertisedChoiceAndStaleIssue() {
        assertFailsWith<DomainFailure> { cashIssueRequest(record(), "31", "create_independent") }
        assertFailsWith<DomainFailure> { cashIssueRequest(record(), "old", "keep_existing") }
        assertFailsWith<DomainFailure> { cashIssueRequest(record(), "31", "keep_existing", "12") }
    }
    @Test fun mergeUsesAdvertisedTargetRevisionWithoutNumericConversion() {
        val candidate = CashIdentityCandidate("9007199254740993", 9, "posted", ImportDisplay(null, null, null, null, null, emptyList()))
        val issue = record().issues.single().copy(code = "source_identity_ambiguous", allowedResolutions = listOf("merge_into"), candidates = listOf(candidate))
        val current = record().copy(issues = listOf(issue))
        assertEquals(CashIssueResolution("31", 4, "merge_into", candidate.transactionId, 9),
            cashIssueRequest(current, "31", "merge_into", candidate.transactionId).issueResolutions!!.single())
        assertFailsWith<DomainFailure> { cashIssueRequest(current, "31", "merge_into", "12") }
        assertFailsWith<DomainFailure> { cashIssueRequest(current, "31", "merge_into") }
    }
    private fun record() = CashCurrentRecord("import", null, "11", "draft", 7, "pending", StructuredObject(emptyMap()), emptyList(),
        listOf(CashRecordIssue("31", 4, "source_update_pending", "", null, "posting", "resolve_issue", listOf("apply_source", "keep_existing"), StructuredObject(emptyMap()), null, emptyList())), emptyList(), null)
}
