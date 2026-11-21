package com.finance.tracker.data

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

internal actual fun encodeCashImportRequest(bytes: ByteArray): CashImportRequestEncoding = try {
    val output = ByteArrayOutputStream(bytes.size / 4)
    GZIPOutputStream(output).use { it.write(bytes) }
    CashImportRequestEncoding.Native(output.toByteArray())
} catch (_: java.io.IOException) {
    CashImportRequestEncoding.Identity
}
