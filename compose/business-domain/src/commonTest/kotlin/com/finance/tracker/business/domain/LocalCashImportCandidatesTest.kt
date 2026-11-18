package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportCandidatesTest {
    private val base get() = LocalCashRelationContextTest().snapshot()
    private val far = "2027-06-01T10:00:00Z"
    private fun keys(source: String, metadata: Any?, original: Any?) =
        readCashPlatformTransactionKeys(source, metadata) { original }
    private fun json(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
    private fun hard(source: String = "alipay", scope: String? = null, accounts: List<Long> = listOf(10),
        transaction: Set<String> = setOf("key"), merchant: Set<String> = emptySet()) =
        CashImportCandidateQuery<Instant>(emptySet(), emptyList(), false,
            listOf(CashImportCandidateHardKeys(source, scope, accounts, transaction, merchant)))
    private fun input(vararg rows: LedgerCashTransactionFactDto, bindings: List<LedgerSourceBindingFactDto> = emptyList()): LedgerSnapshotDto {
        val source = base
        val part = source.facts.cashTransactionComponents.first()
        return source.copy(facts = source.facts.copy(cashTransactions = rows.toList(), cashSourceBindings = bindings,
            cashTransactionComponents = rows.map { part.copy(id = it.id + "0", cashTransactionId = it.id, accountId = it.accountId) }))
    }
    private fun row(id: String = "1", source: String = "alipay", metadata: JsonElement = json("txn_id" to JsonPrimitive("key"))) =
        base.facts.cashTransactions.first().copy(id = id, sourceType = source, relationMetadata = metadata, occurredAt = far)
    private fun binding(id: String, source: String, scope: String, kind: String = "file_occurrence", key: String = "evidence") =
        LedgerSourceBindingFactDto("binding-$id-$scope", id, source, "account", scope, kind, key, "file_local", null,
            "fixture", JsonNull, "1", far, far)

    @Test fun platformAdapterUsesNormalizedTextBeforeMappedOriginalFieldsAndNeverProbesOtherSources() {
        val raw = mapOf("txn_id" to "raw-key", "交易订单号" to "alipay", "交易单号" to "wechat", "商户单号" to "merchant")
        assertEquals(CashPlatformTransactionKeys("normalized", "confirmed"), keys("alipay",
            mapOf("txn_id" to "normalized", "merchant_order_id" to "confirmed"), raw))
        assertEquals("alipay", keys("alipay", emptyMap<String, Any?>(), mapOf("交易订单号" to "alipay"))?.transactionId)
        assertEquals("wechat", keys("wechat", null, mapOf("交易单号" to "wechat"))?.transactionId)
        assertEquals("old", keys("alipay", null, mapOf("交易号" to "old"))?.transactionId)
        assertEquals("", keys("alipay", mapOf("txn_id" to ""), raw)?.transactionId)
        assertEquals("raw-key", keys("alipay", mapOf("txn_id" to null), raw)?.transactionId)
        for (value in listOf(123, true, mapOf("nested" to "key"))) {
            assertNull(keys("alipay", mapOf("txn_id" to value), raw)?.transactionId)
            assertNull(keys("alipay", null, mapOf("txn_id" to value, "交易订单号" to "later"))?.transactionId)
        }
        val forbidden = object : AbstractMap<String, Any?>() {
            override val entries: Set<Map.Entry<String, Any?>> get() = error("source payload must not be inspected")
        }
        assertNull(keys("ccb_debit", forbidden, forbidden))
    }

    @Test fun hardKeysKeepEligibilityAccountScopePrefixAndProviderBindingRules() {
        val row = row()
        val ordinary = input(row, row("2").copy(state = "draft"), row("3").copy(deletedAt = far),
            row("4").copy(recordOrigin = "manual"), row("5").copy(accountId = "20"))
        assertEquals(setOf(1L), readLocalCashImportCandidateIds(ordinary, hard()))
        assertEquals(emptySet(), readLocalCashImportCandidateIds(ordinary, hard(source = "ccb_debit")))
        assertEquals(setOf(1L), readLocalCashImportCandidateIds(input(row.copy(relationMetadata = json("txn_id" to JsonPrimitive("key_suffix")))), hard()))
        assertEquals(setOf(1L), readLocalCashImportCandidateIds(input(row.copy(relationMetadata = json("merchant_order_id" to JsonPrimitive("key")))), hard()))
        assertEquals(emptySet(), readLocalCashImportCandidateIds(input(row, bindings = listOf(binding("1", "alipay", "other"))), hard(scope = "owner")))
        assertEquals(setOf(1L), readLocalCashImportCandidateIds(input(row, bindings = listOf(binding("1", "alipay", "owner", "provider_id", providerIdentityKey("key")))),
            hard(scope = "owner", accounts = emptyList())))
        val wechat = input(row(source = "wechat"))
        assertEquals(emptySet(), readLocalCashImportCandidateIds(wechat, hard(source = "wechat", scope = "non-default")))
        assertEquals(setOf(1L), readLocalCashImportCandidateIds(wechat, hard(source = "wechat", scope = CashSourceScope.WECHAT_DEFAULT_SCOPE)))
        assertEquals(setOf(1L), readLocalCashImportCandidateIds(input(row(source = "wechat"), bindings = listOf(binding("1", "wechat", "owner"))),
            hard(source = "wechat", scope = "owner")))
    }

    @Test fun candidateReadsUseThePlatformMappingAndNeverReplaceAnExistingNormalizedKey() {
        val payload = json("交易订单号" to JsonPrimitive("key"), "商家订单号" to JsonPrimitive("merchant"))
        val normalized = row(metadata = json("txn_id" to JsonPrimitive("normalized"), "merchant_order_id" to JsonPrimitive("confirmed")))
            .copy(sourcePayload = payload)
        assertEquals(emptySet(), readLocalCashImportCandidateIds(input(normalized), hard()))
        assertEquals(setOf(1L), readLocalCashImportCandidateIds(input(normalized), hard(transaction = setOf("normalized"))))
        assertEquals(setOf(1L), readLocalCashImportCandidateIds(input(normalized.copy(relationMetadata = JsonNull)), hard()))
        assertEquals(setOf(1L), readLocalCashImportCandidateIds(input(normalized.copy(relationMetadata = JsonNull)),
            hard(transaction = emptySet(), merchant = setOf("merchant"))))
        val wechat = row(source = "wechat", metadata = JsonNull).copy(sourcePayload = json("交易单号" to JsonPrimitive("key")))
        assertEquals(setOf(1L), readLocalCashImportCandidateIds(input(wechat), hard(source = "wechat")))
        val numeric = row(metadata = json("txn_id" to JsonPrimitive(123))).copy(sourcePayload = payload)
        assertEquals(emptySet(), readLocalCashImportCandidateIds(input(numeric), hard(transaction = setOf("123", "key"))))
    }

    @Test fun timeWindowsAreInclusiveAndDoNotDecodeUnrelatedJsonOrUnknownSourceAmounts() {
        val start = Instant.parse("2027-06-01T10:00:00Z")
        val end = Instant.parse("2027-06-02T10:00:00Z")
        val source = input(row(), row("2").copy(occurredAt = end.toString()), row("3", "unrelated").copy(amount = "invalid amount"),
            row("4").copy(occurredAt = "2027-06-02T10:00:01Z"))
        val before = source.facts
        val query = CashImportCandidateQuery(setOf("consumption"), listOf(start to end), true, emptyList())
        assertEquals(setOf(1L, 2L), readLocalCashImportCandidateIds(source, query))
        assertEquals(setOf(1L, 2L, 3L), readLocalCashImportCandidateIds(source, query.copy(expenseOnly = false)))
        assertEquals(before, source.facts)
        assertEquals(emptySet(), readLocalCashImportCandidateIds(source, query.copy(windows = emptyList())))
    }

    @Test fun completeNormalizedKeysDoNotReadTheOriginalJson() {
        val failure = IllegalStateException("original JSON must remain unread")
        val unread = JsonObject(object : AbstractMap<String, JsonElement>() {
            override val entries: Set<Map.Entry<String, JsonElement>> get() = throw failure
        })
        val complete = row(metadata = json("txn_id" to JsonPrimitive("key"), "merchant_order_id" to JsonPrimitive("merchant")))
            .copy(sourcePayload = unread)
        assertEquals(setOf(1L), readLocalCashImportCandidateIds(input(complete), hard()))
        assertSame(failure, assertFailsWith<IllegalStateException> {
            readLocalCashImportCandidateIds(input(complete.copy(relationMetadata = JsonNull)), hard())
        })
    }
}
