package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlin.test.*

class ImportTaskResultTest {
    @Test fun predictedProblemsDoNotBecomeUnexpectedErrorsAndPublicEditsDoNotDoubleCountRows() {
        val receipt = ImportReceipt("receipt", "plan", "import", true, "now", "failed",
            mapOf("created_posted" to 2L, "updated_posted" to 1L, "saved_draft" to 3L, "finalized_draft" to 1L,
                "duplicate" to 4L, "skipped" to 2L, "updated_entries" to 8L, "relations_confirmed" to 4L), emptyMap(),
            3, 3, 1, null, null, null, null, ImportCommitOutcome(false, false, emptyList()))
        assertFalse(importTaskHasUnexpectedFailure(receipt))
        assertEquals(13L, importTaskProcessedRows(receipt))
        assertTrue(importTaskHasUnexpectedFailure(receipt.copy(status = "partial", commitOutcome = ImportCommitOutcome(true, true, listOf("record_changed")))))
    }

    @Test fun missingOrMismatchedCandidateMembersCannotBeAccepted() {
        val member = ImportMemberDetail("row", null, "create_posted", StructuredObject(emptyMap()), emptyList(), emptyList(), null)
        assertNull(importRelationMembers(emptyList(), listOf(member)))
        assertNull(importRelationMembers(listOf(ImportMemberRef("row", null), ImportMemberRef(null, "stored")), listOf(member)))
        assertNull(importRelationMembers(listOf(ImportMemberRef(null, null)), listOf(member)))
        assertEquals(listOf(member), importRelationMembers(listOf(ImportMemberRef("row", null)), listOf(member)))
    }
}
