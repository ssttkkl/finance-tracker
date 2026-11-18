package com.finance.tracker.business.domain

/** The existing byte/source dispatch, independent of local or remote decoding. */
enum class CashStatementFormat { PDF, XLS, ICBC_ASIA, XLSX, CSV }

fun cashStatementFormat(content: ByteArray, source: String? = null): CashStatementFormat {
    if (content.isEmpty()) throw StatementDocumentFailure("statement_input_invalid")
    val normalized = source?.replace('-', '_')
    if (content.startsWithBytes(0x25, 0x50, 0x44, 0x46, 0x2d) || normalized in setOf("icbc", "icbc_credit", "icbc_debit"))
        return CashStatementFormat.PDF
    val ole = content.startsWithBytes(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)
    val utf16 = content.startsWithBytes(0xff, 0xfe) || content.startsWithBytes(0xfe, 0xff)
    if (normalized in setOf("ccb_debit", "icbc_asia") || normalized == null && (ole || utf16)) {
        val detected = when { ole -> "ccb_debit"; utf16 -> "icbc_asia"; else -> null }
        if (detected == null || normalized != null && normalized != detected)
            throw StatementDocumentFailure("statement_source_unsupported")
        return if (ole) CashStatementFormat.XLS else CashStatementFormat.ICBC_ASIA
    }
    return if (content.startsWithBytes(0x50, 0x4b)) CashStatementFormat.XLSX else CashStatementFormat.CSV
}

private fun ByteArray.startsWithBytes(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
