package com.finance.tracker.data

internal sealed interface BrowserCashStatementCapture {
    class Prepared(val file: LocalCashImportFile) : BrowserCashStatementCapture
    class Remote(val bytes: ByteArray) : BrowserCashStatementCapture
}

internal expect suspend fun captureBrowserCashStatement(input: CashImportUploadInput, retainKnownFailure: Boolean): BrowserCashStatementCapture?
