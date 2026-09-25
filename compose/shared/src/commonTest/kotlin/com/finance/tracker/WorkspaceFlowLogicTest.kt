package com.finance.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceFlowLogicTest {
    @Test
    fun invitationFailuresPreserveExpiredVersusRetryableMeaning() {
        val expired = ApiFailure("invitation_expired", 410)
        val temporary = ApiFailure("api_request_failed", 503)

        assertEquals("此邀请无效、已被使用或已过期。", invitationErrorMessage(expired))
        assertTrue(isTerminalInvitationFailure(expired))
        assertEquals("暂时无法完成操作，请稍后重试。", invitationErrorMessage(temporary))
        assertFalse(isTerminalInvitationFailure(temporary))
    }
}
