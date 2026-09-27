package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceFlowLogicTest {
    @Test
    fun invitationFailuresPreserveExpiredVersusRetryableMeaning() {
        val expired = DomainFailure("invitation_expired", 410, FailureCategory.RECOVERABLE)
        val temporary = DomainFailure("api_request_failed", 503, FailureCategory.RECOVERABLE)

        assertEquals("copy_40d123e2b5", invitationErrorResourceKey(expired))
        assertTrue(isTerminalInvitationFailure(expired))
        assertEquals("copy_e6a62f3e45", invitationErrorResourceKey(temporary))
        assertFalse(isTerminalInvitationFailure(temporary))
    }
}
