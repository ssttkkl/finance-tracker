package com.finance.tracker.data

internal actual suspend fun captureBrowserCashStatement(
    input: CashImportUploadInput,
    retainKnownFailure: Boolean,
): BrowserCashStatementCapture? = null
