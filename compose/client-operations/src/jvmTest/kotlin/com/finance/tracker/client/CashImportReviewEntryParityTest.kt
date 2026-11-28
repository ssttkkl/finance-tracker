package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashImportPreviewInput
import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.ParsedImportFile
import com.finance.tracker.business.domain.cashWriteSetJson
import com.finance.tracker.business.domain.parseLocalCashCsvStatement
import com.finance.tracker.business.domain.sha256Hex
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CashImportReviewEntryParityTest {
    @Test
    fun guaranteedJsonStringBytesCatchHighEscapesAndDoNotOvercountOtherText() {
        fun exceeds(values: List<String>, limit: Int): Boolean = values.any(RequiredJsonStringBudget(limit)::exceededBy)
        val ascii = "\u0001\u0000\\\"\b\t\n\u000C\r"
        val encoded = Json.encodeToString(ascii).encodeToByteArray().size
        assertFalse(exceeds(listOf(ascii), encoded))
        assertTrue(exceeds(listOf(ascii), encoded - 1))
        val mixed = listOf("ASCII", "界¢𐐷\uD800", "\u0001")
        val actual = mixed.sumOf { Json.encodeToString(it).encodeToByteArray().size }
        assertFalse(exceeds(mixed, actual))
        assertTrue(exceeds(listOf("\u0001", "\u0002"), 15))
    }

    @Test
    fun requiredStringsInRealWriteSetArePresentWithOrWithoutDefaults() {
        val json = cashWriteSetJson
        val number = "900719925474099312345"
        val key = "\u0001\u0002"
        val leaf = "\u0003"
        val note = "\u0004\u0005"
        val stringBytes = listOf(key, leaf, note).sumOf { json.encodeToString(it).encodeToByteArray().size }
        val request = json.decodeFromString<CashWriteSetRequestDto>(
            """{"base_ledger_seq":"1","rules_version":"test","changes":{"transactions":[{
                "target":{"reference":{"type":"new","ref":"row"},"expected_revision":null},
                "values":{"account_id":null,"state":"posted","record_origin":"source",
                "manual_transfer_id":null,"cash_granularity":"transaction","source_type":"fixture",
                "record_id":"row","source_payload":{${json.encodeToString(key)}:[{"":${json.encodeToString(leaf)}},$number]},
                "relation_metadata":null,"source_fingerprint":null,"manual_overrides":null,"occurred_at":null,
                "amount":null,"currency":null,"counterparty":"","counterparty_account":"",
                "counterparty_account_attrs":null,"note":${json.encodeToString(note)},"category_id":null,
                "record_type":null,"record_subtype":null},"components":[]}]}}""",
        )
        val payloadKeyBytes = 2
        val minimum = stringBytes + payloadKeyBytes
        assertFalse(requiredCashImportStringsExceed(request, minimum))
        assertTrue(requiredCashImportStringsExceed(request, minimum - 1))
        for (encoding in listOf(json, Json(json) { encodeDefaults = false })) {
            val body = encoding.encodeToString(request)
            assertTrue(body.encodeToByteArray().size >= minimum)
            for (token in listOf(key, leaf, note)) assertTrue(json.encodeToString(token) in body)
            assertTrue(number in body)
        }
    }

    @Test
    fun reviewUsesTheSameStatementAndCommittedFactsAsTheInstalledCli() {
        val snapshot = Json.decodeFromString<LedgerSnapshotDto>(resource("cash-operation-snapshot.json").decodeToString())
        val bytes = resource("alipay-01.csv")
        val document = parseLocalCashCsvStatement(bytes)
        val file = ParsedImportFile("fixture-file", sha256Hex(bytes), "parsed", document, filename = "alipay-01.csv", size = bytes.size.toLong())
        val choices = CashImportPreviewInput(2, null, emptyList(), emptyList(), emptyList())
        val calendar = CashLedgerCalendar({ it == "UTC" }, { _, _ -> 0 }, { _, _ -> error("No date limits") })
        val review = CashImportReviewOperations(snapshot.workspaceId, calendar)
            .review(snapshot, "fixture-batch", "fixture-plan", 2, listOf(file), choices)

        assertEquals(2L, review.plan.sessionRevision)
        assertEquals(snapshot.workspaceId, review.workspaceId)
        assertEquals(snapshot.ledgerSeq, review.calculation.request.baseLedgerSeq)
        assertEquals("fixture-batch", review.plan.sessionId)
        assertTrue(checkNotNull(review.plan.fileResults.single().recognizedRowCount) > 0)
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }
}
