package com.finance.tracker.backend

import java.io.ByteArrayInputStream
import java.io.IOException
import org.apache.poi.hssf.eventusermodel.AbortableHSSFListener
import org.apache.poi.hssf.eventusermodel.HSSFEventFactory
import org.apache.poi.hssf.eventusermodel.HSSFRequest
import org.apache.poi.hssf.record.BOFRecord
import org.apache.poi.hssf.record.LabelRecord
import org.apache.poi.hssf.record.LabelSSTRecord
import org.apache.poi.hssf.record.SSTRecord
import org.apache.poi.hssf.record.Record as HssfRecord
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.util.RecordFormatException

internal data class CashStatementCandidate(
    val matches: () -> Boolean,
    val read: () -> CashStatementDocument,
)

internal fun readUniqueCashStatement(candidates: List<CashStatementCandidate>): CashStatementDocument {
    val matching = candidates.filter { it.matches() }
    if (matching.size != 1) throw StatementDocumentFailure("import_channel_unrecognized")
    return matching.single().read()
}

/** A per-upload cache of format evidence; no rows, account mappings or storage are loaded here. */
internal class CashStatementDetection(private val content: ByteArray, private val password: String?) {
    private val pdf = content.size >= 5 && content.copyOfRange(0, 5).contentEquals("%PDF-".encodeToByteArray())
    private val ole = content.size >= 8 && content.take(8).map { it.toInt() and 255 } ==
        listOf(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)
    private val utf16 = content.size >= 2 && ((content[0] == 0xff.toByte() && content[1] == 0xfe.toByte()) ||
        (content[0] == 0xfe.toByte() && content[1] == 0xff.toByte()))
    private val platform by lazy {
        if (pdf || ole || utf16) emptySet() else CashStatementDocumentReader.probeSources(content)
    }
    private val bank by lazy {
        when {
            ole -> if (probeCcb(content)) setOf("ccb_debit") else emptySet()
            utf16 -> if (BankStatementDocumentReader.probeAsia(content)) setOf("icbc_asia") else emptySet()
            else -> emptySet()
        }
    }
    private val icbc by lazy {
        if (!pdf) emptySet() else {
            val text = PdfProcesses.client.extractFirstPageText(content, password).replace(Regex("\\s+"), "")
            if (!text.contains("中国工商银行")) emptySet() else buildSet {
                if (listOf("入账日期", "交易卡号", "收支", "交易币种", "交易金额", "入账币种", "入账金额",
                        "账户余额", "对方户名", "对方账号", "摘要", "交易场所").all(text::contains)) add("icbc_credit")
                if (listOf("交易日期", "账号", "储种", "序号", "币种", "钞汇", "摘要", "地区", "收入/支出金额",
                        "余额", "对方户名", "对方账号", "渠道").all(text::contains)) add("icbc_debit")
            }
        }
    }

    fun matches(source: String): Boolean = when (source) {
        "alipay", "wechat" -> source in platform
        "ccb_debit", "icbc_asia" -> source in bank
        "icbc_credit", "icbc_debit" -> source in icbc
        else -> error("unknown built-in cash source")
    }

    private fun probeCcb(bytes: ByteArray): Boolean {
        var strings: SSTRecord? = null
        var worksheet = false
        var title = false
        val rows = mutableMapOf<Int, MutableSet<String>>()
        var matched = false
        val required = setOf("序号", "交易日期", "交易金额", "账户余额")
        val listener = object : AbortableHSSFListener() {
            override fun abortableProcessRecord(record: HssfRecord): Short {
                when (record) {
                    is SSTRecord -> strings = record
                    is BOFRecord -> if (record.type == BOFRecord.TYPE_WORKSHEET) {
                        if (worksheet) return 1
                        worksheet = true
                    }
                }
                if (!worksheet) return 0
                val row: Int
                val value: String
                when (record) {
                    is LabelSSTRecord -> { row = record.row; value = strings?.getString(record.sstIndex)?.string.orEmpty() }
                    is LabelRecord -> { row = record.row; value = record.value }
                    else -> return 0
                }
                if (row >= 50) return 1
                if (value.replace(Regex("\\s+"), "").matches(Regex("中国建设银行.*交易明细"))) title = true
                val cells = rows.getOrPut(row) { mutableSetOf() }
                if (cells.size >= 128) throw StatementDocumentFailure("statement_rows_too_large")
                cells += value.take(512)
                matched = title && rows.values.any { it.containsAll(required) }
                return if (matched) 1 else 0
            }
        }
        try {
            POIFSFileSystem(ByteArrayInputStream(bytes)).use { fs ->
                val request = HSSFRequest().also { it.addListenerForAllRecords(listener) }
                HSSFEventFactory().abortableProcessWorkbookEvents(request, fs)
            }
        } catch (_: IOException) { throw StatementDocumentFailure("statement_unreadable") }
        catch (_: RecordFormatException) { throw StatementDocumentFailure("statement_unreadable") }
        return matched
    }
}
