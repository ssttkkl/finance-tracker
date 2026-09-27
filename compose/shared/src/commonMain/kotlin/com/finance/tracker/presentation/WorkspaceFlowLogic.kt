package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

internal fun isTerminalInvitationFailure(cause: Throwable): Boolean {
    val code: String
    val status: Int
    when (cause) {
        is DomainFailure -> { code = cause.code; status = cause.status }
        else -> return false
    }
    return status == 404 || status == 410 || code in setOf(
        "invitation_invalid",
        "invitation_expired",
        "invitation_already_used",
    )
}

internal fun invitationErrorResourceKey(cause: Throwable): String =
    if (isTerminalInvitationFailure(cause)) "copy_40d123e2b5" else userErrorResourceKey(cause)

internal fun invitationErrorResourceKey(code: String): String =
    if (code in setOf("invitation_invalid", "invitation_expired", "invitation_already_used")) {
        "copy_40d123e2b5"
    } else {
        userErrorResourceKey(DomainFailure(code, 0, FailureCategory.RECOVERABLE))
    }
