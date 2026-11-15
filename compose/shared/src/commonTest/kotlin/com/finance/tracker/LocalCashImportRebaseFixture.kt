package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.BoundedFileSource
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Real source, operation, preview and complete write builders; this isolated consumption has no relation candidates. */
internal class LocalCashImportRebaseFixture {
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
        val doc = ParsedCashDocument("icbc_debit", "csv", "v1", listOf(ParsedCashRow(1,
            mapOf("amount" to "-12.30", "currency" to "CNY", "occurred_at" to "2026-11-14T00:00:00Z",
                "record_type" to "consumption", "record_subtype" to "not_applicable", "note" to "Original S"),
            providerId = "ROW-1", sourceExplanation = mapOf("source_display_name" to "Synthetic bank"))),
            "owner", "Account", CashSourceCoverage(null, null, false, "", emptyMap(), "v1"), 1, 1)
        LocalCashImportFile.capture(CashImportUploadInput("stable", input), bytes, doc)
    })
    val input = buildJsonObject {
        put("account_selections", buildJsonArray {
            for (group in scanLocalCashFunding(files.map { it.parsedFile() }).groups) add(buildJsonObject {
                put("source_account_ref", group.sourceAccountRef); put("account_id", "10"); put("expected_account_revision", 1)
            })
        })
        for (key in listOf("row_choices", "relation_choices", "entry_choices")) put(key, JsonArray(emptyList()))
    }
    val calculate: LocalCashImportCalculator = { files, source, input, previous, ids ->
        val choices = prepareLocalCashImportInput(input["account_selections"], input["row_choices"], input["relation_choices"], input["entry_choices"])
        val operations = prepareLocalCashImportOperations(files, source, input.getValue("account_selections"), input.getValue("row_choices"), ids, false)
        @Suppress("UNCHECKED_CAST") val edits = choices["entry_choices"] as List<Map<String, Any?>>
        val prepared = LocalCashImportPreviewPreparation(source) {}.prepare(operations.operations, operations.source.rows.rows, edits, previous)
        val snapshot = buildLocalCashImportPreviewSnapshot(source, prepared.context, prepared.operations, operations.rows) { Instant.parse("2026-11-15T00:00:00Z") }
        val plan = completeLocalCashImportPreviewPlan(source, prepared, snapshot, emptyList(), files, choices, previous, operations.candidateSets)
        plan to buildLocalCashImportWriteSet(source, files, operations.source, freezeLocalCashImportPreviewPlan(plan), input.getValue("row_choices").jsonArray, input.getValue("relation_choices").jsonArray)
    }
    val original = calculate(files.map { it.parsedFile() }, source, input, emptyMap(), emptyMap())
    val review = LocalCashImportReview.freeze(input, original.first, source, original.second)
    val request = CashWriteSetRequestDto(source.ledgerSeq, CASH_RULES_VERSION, original.second)
}
