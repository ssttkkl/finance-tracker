package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.BoundedFileSource
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Real source, operation, preview and complete write builders; the optional refund uses actual discovery. */
internal class LocalCashImportRebaseFixture(private val includeRefund: Boolean = false) {
    val source = localCashLedgerOwnerSnapshot("7").let { it.copy(facts = it.facts.copy(
        accounts = it.facts.accounts.map { account -> account.copy(currencies = buildJsonArray { add("CNY") }) },
        cashTransactions = emptyList(), cashEntries = emptyList(), cashEntryMembers = emptyList())) }
    val files = listOf(run {
        val bytes = "synthetic rebase statement".encodeToByteArray()
        val input = object : BoundedFileSource {
            override val name = "synthetic.csv"
            override val mediaType = "text/csv"
            override val size = bytes.size.toLong()
            override val lastModifiedAtMillis: Long? = null
            override suspend fun read() = bytes.copyOf()
            override suspend fun readBounded(maxBytes: Int) = read()
        }
        val doc = if (includeRefund) refundDocument() else ParsedCashDocument("icbc_debit", "csv", "v1", listOf(ParsedCashRow(1,
            mapOf("amount" to "-12.30", "currency" to "CNY", "occurred_at" to "2026-11-14T00:00:00Z",
                "record_type" to "consumption", "record_subtype" to "not_applicable", "note" to "Original S"),
            providerId = "ROW-1", sourceExplanation = mapOf("source_display_name" to "Synthetic bank"))),
            "owner", "Account", CashSourceCoverage(null, null, false, "", emptyMap(), "v1"), 1, 1)
        LocalCashImportFile.capture(CashImportUploadInput("stable", input), bytes, doc)
    })
    private fun refundDocument(): ParsedCashDocument {
        val rows = listOf(false, true).mapIndexed { index, refund ->
            val id = if (refund) "ORDER_1" else "ORDER"
            ParsedCashRow(index + 1, mapOf("amount" to if (refund) "3.00" else "-10.00", "currency" to "CNY",
                "occurred_at" to "2026-11-${if (refund) "15" else "14"}T00:00:00Z", "record_type" to if (refund) "refund" else "consumption",
                "record_subtype" to "not_applicable", "counterparty" to "Synthetic merchant", "note" to "Source note",
                "source_payload" to mapOf("交易订单号" to id, "商品说明" to "Synthetic item")),
                paymentMethod = "支付宝余额", providerId = id, sourceExplanation = mapOf("txn_id" to id, "payment_method" to "支付宝余额"))
        }
        return ParsedCashDocument("alipay", "csv", "v1", rows, "owner", "Account",
            CashSourceCoverage(null, null, false, "", emptyMap(), "v1"), 2, 2)
    }
    val input = buildJsonObject {
        put("account_selections", buildJsonArray {
            for (group in scanLocalCashFunding(files.map { it.parsedFile() }).groups) add(buildJsonObject {
                put("source_account_ref", group.sourceAccountRef); put("account_id", "10"); put("expected_account_revision", 1)
            })
        })
        for (key in listOf("row_choices", "relation_choices", "entry_choices")) put(key, JsonArray(emptyList()))
    }
    val calculate: LocalCashImportCalculator = { files, source, input, previous, ids ->
        calculateLocalCashImportBusinessPlan(files, source, input, previous, ids, {}) { Instant.parse("2026-11-15T00:00:00Z") }
    }
    val original = calculate(files.map { it.parsedFile() }, source, input, emptyMap(), emptyMap())
    val review = LocalCashImportReview.freeze(input, original.first, source, original.second)
    val request = CashWriteSetRequestDto(source.ledgerSeq, CASH_RULES_VERSION, original.second)
}
