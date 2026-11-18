package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashSourceSnapshotTest {
    private val time = "2026-08-15T02:00:00Z"
    private fun fields() = mapOf<String, Any?>("amount" to "-20", "currency" to "CNY", "occurred_at" to time,
        "record_type" to "consumption", "record_subtype" to "not_applicable")
    private fun row(source: String = "ccb_debit") = SourceOccurrence(source, "6222", "f".repeat(64), "p", "t", 1,
        fields(), providerId = if (source == "wechat") "WX-1" else null)
    private fun tx(id: String, source: String = "ccb_debit", origin: String = "import", deleted: String? = null,
        occurred: String = time): LedgerCashTransactionFactDto = LedgerCashTransactionFactDto(
        id, "10", "posted", origin, null, "1", "atomic", source, "legacy-$id", JsonObject(emptyMap()),
        JsonNull, null, JsonArray(emptyList()), occurred, "-20", "CNY", "商户", "", JsonArray(emptyList()), "",
        null, "consumption", "not_applicable", time, deleted, "", "")
    private fun facts(transactions: List<LedgerCashTransactionFactDto>, bindings: List<LedgerSourceBindingFactDto> = emptyList(),
        pages: List<LedgerSourceEmptyPageFactDto> = emptyList()) = LedgerFactsDto(
        emptyList(), emptyList(), emptyList(), emptyList(), transactions, emptyList(), bindings, pages,
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
    private fun binding(id: String, tx: String, key: SourceBindingKey, coverage: JsonElement = JsonNull,
        version: String = "p") = LedgerSourceBindingFactDto(id, tx, key.sourceType, key.scopeKind, key.scopeKey,
        key.identityKind, key.identityKey, "strong", null, version, coverage, "1", time, time)

    @Test fun sourceReadsKeepEverySupported64BitRevision() {
        for (revision in listOf("2147483647","2147483648","9007199254740993","9223372036854775807")) {
            val data=facts(listOf(tx("1").copy(rowRevision=revision)))
            val record=LocalCashSourceRead(data).recordsForFile(listOf(row())).single()
            assertEquals(revision,record.rowRevision.toString())
            assertEquals(Json.parseToJsonElement(revision),encodeLocalCashValue(record.rowRevision))
        }
    }

    @Test fun selectionUsesBindingsAndNonWechatTimeWithoutInferringLegacyIdentity() {
        val bank = row(); val wechat = row("wechat")
        val binding = binding("1", "6", wechat.bindingSpecs.first { it.identityKind == "provider_id" })
        val facts = facts(listOf(tx("1"), tx("2", origin = "manual"), tx("3", deleted = time), tx("4", "other"),
            tx("5", "wechat", "legacy"), tx("6", "wechat", occurred = "2026-08-10T00:00Z")), listOf(binding))
        val records = LocalCashSourceRead(facts).recordsForFile(listOf(bank, wechat))
        assertEquals(listOf("1", "6"), records.map { it.transactionId })
        assertNull(records.last().providerId)
        assertEquals(wechat.providerBindingKey, records.last().providerBindingKey)
        assertEquals("6222", records.last().sourceAccountKey)
        assertTrue(LocalCashSourceRead(facts).recordsForFile(emptyList()).isEmpty())
        assertTrue(LocalCashSourceRead(facts).recordsForFile(listOf(wechat.copy(providerId = "WX-2"))).isEmpty())
    }

    @Test fun preparationReadsOneSnapshotAndDoesNotMutateTheInput() {
        val bank = row()
        val full = CashSourceCoverage("2026-08-01T00:00Z", "2026-09-01T00:00Z", true, "e", emptyMap(), "v")
        val file = ParsedImportFile("f", bank.fileDigest, "parsed", ParsedCashDocument("ccb_debit", "csv", "p",
            listOf(ParsedCashRow(1, fields())), "6222", null, full, 1, 1))
        val snapshot = LedgerSnapshotDto("w", "9007199254740993", CASH_RULES_VERSION, facts(listOf(tx("1"))))
        val result = prepareLocalCashImportSources(listOf(file), snapshot)
        assertEquals(listOf("1"), result.records.map { it.transactionId })
        assertEquals("pending", result.plan.rows.single().action) // An unproved existing row requires identity review.
        assertEquals("source_identity_ambiguous", result.plan.rows.single().reason)
        assertEquals("9007199254740993", snapshot.ledgerSeq)
        assertEquals("-20", snapshot.facts.cashTransactions.single().amount)
    }

    @Test fun corruptSelectedCoverageAndAmbiguousAccountBindingsStopReading() {
        val row = row()
        val provider = SourceBindingKey(row.sourceType, "account", "6222", "provider_id", "p")
        val bad = binding("1", "1", provider, buildJsonObject { put("unknown", true) })
        val direct = binding("2", "1", row.bindingSpecs.first())
        assertFailsWith<IllegalArgumentException> {
            LocalCashSourceRead(facts(listOf(tx("1")), listOf(bad, direct))).recordsForFile(listOf(row))
        }
        val other = binding("3", "1", provider.copy(scopeKey = "other"))
        assertEquals("source_binding_ambiguous", assertFailsWith<IllegalStateException> {
            LocalCashSourceRead(facts(listOf(tx("1")), listOf(direct, other))).recordsForFile(listOf(row))
        }.message)
    }

    @Test fun storedCoverageDefaultsAndMalformedStructuresKeepTheOriginalBoundary() {
        val empty = decodeStoredCashSourceCoverage(JsonNull)
        assertFalse(empty.complete)
        assertEquals("source-coverage-v1", empty.strategyVersion)
        assertTrue(empty.startInclusive)
        assertFalse(empty.endInclusive)
        for (text in listOf("{\"unknown\":0}", "{\"complete\":1}", "{\"filters\":[]}",
            "{\"filters\":{\"account\":1}}", "{\"page_total\":1.0}", "{\"page_fingerprints\":{\"1\":1}}")) {
            assertFailsWith<IllegalArgumentException>(text) { decodeStoredCashSourceCoverage(Json.parseToJsonElement(text)) }
        }
        assertEquals(empty, decodeStoredCashSourceCoverage(JsonPrimitive("not-an-object")))
        val doc = Json.parseToJsonElement("""{"document_key":"${"d".repeat(64)}","page_total":2,"page_fingerprints":{"1":"${"e".repeat(64)}"}}""")
        assertEquals(2, decodeStoredCashSourceCoverage(doc).pageTotal)
    }

    @Test fun candidateTimeCrossProductAndChunkOrderMatchTheRepository() {
        val start = kotlin.time.Instant.parse(time)
        val rows = (0..250).map { index -> row().copy(sourceType = if (index == 250) "alipay" else "ccb_debit",
            providerId = if (index == 250) "ALI-1" else null,
            position = index, fields = fields() + ("occurred_at" to kotlin.time.Instant.fromEpochSeconds(start.epochSeconds + index).toString())) }
        val one = tx("1", occurred = kotlin.time.Instant.fromEpochSeconds(start.epochSeconds + 250).toString())
        val two = tx("2", source = "alipay")
        assertEquals(listOf("2", "1"), LocalCashSourceRead(facts(listOf(one, two))).recordsForFile(rows).map { it.transactionId })
    }

    @Test fun proofsUseLiveParentNamespacesAndIncludeMatchingAuthenticatedEmptyPages() {
        val document = "d".repeat(64)
        val fingerprint = "e".repeat(64)
        fun proof(page: String): JsonObject = buildJsonObject {
            put("document_key", document); put("page_total", 2)
            put("page_fingerprints", buildJsonObject { put(page, fingerprint) })
        }
        val first = decodeStoredCashSourceCoverage(proof("1"))
        val file = ParsedImportFile("f", "f".repeat(64), "parsed", ParsedCashDocument("icbc_credit", "pdf", "p",
            listOf(ParsedCashRow(1, fields(), documentPosition = "p1:t1:r1")), "6222", null, first, 1, 1))
        val key = SourceBindingKey("icbc_credit", "file_local", localCashSourceDigest(listOf("source-document-v1", "icbc_credit", "p", document)),
            "file_occurrence", "row")
        val alive = binding("1", "1", key, proof("1"))
        val dead = binding("2", "2", key, proof("2"))
        val pages = listOf(LedgerSourceEmptyPageFactDto("icbc_credit", "p", document, 2, proof("2")),
            LedgerSourceEmptyPageFactDto("icbc_credit", "other-parser", document, 2, proof("2")))
        val read = LocalCashSourceRead(facts(listOf(tx("1", origin = "manual"), tx("2", deleted = time)), listOf(alive, dead), pages))
        assertEquals(listOf(SourceDocumentEvidence("icbc_credit", "p", first),
            SourceDocumentEvidence("icbc_credit", "p", decodeStoredCashSourceCoverage(proof("2")))), read.documents(listOf(file), emptyList()))
        val badUnverified = binding("3", "1", key, buildJsonObject { put("unknown", true) })
        val direct = binding("4", "1", row().bindingSpecs.first())
        assertFailsWith<IllegalArgumentException> {
            LocalCashSourceRead(facts(listOf(tx("1")), listOf(direct, badUnverified))).recordsForFile(listOf(row()))
        }
        assertTrue(LocalCashSourceRead(facts(listOf(tx("1", source = "other")), listOf(badUnverified)))
            .recordsForFile(listOf(row())).isEmpty())
    }
}
