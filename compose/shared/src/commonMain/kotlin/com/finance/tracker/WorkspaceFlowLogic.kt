package com.finance.tracker

internal fun isTerminalInvitationFailure(cause: Throwable): Boolean {
    val failure = cause as? ApiFailure ?: return false
    return failure.status == 404 || failure.status == 410 || failure.code in setOf(
        "invitation_invalid",
        "invitation_expired",
        "invitation_already_used",
    )
}

internal fun invitationErrorMessage(cause: Throwable): String =
    if (isTerminalInvitationFailure(cause)) "此邀请无效、已被使用或已过期。" else userError(cause)
