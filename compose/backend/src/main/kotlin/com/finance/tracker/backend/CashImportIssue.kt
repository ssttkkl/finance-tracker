package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashImportIssueDto

internal fun cashImportIssue(code: Any?, fieldPath: Any? = null, blockingScope: Any? = "row", nextAction: Any? = null,
    afterImportAction: Any? = null): CashImportIssueDto =
    com.finance.tracker.business.domain.cashImportIssue(code, fieldPath, blockingScope, nextAction, afterImportAction)
