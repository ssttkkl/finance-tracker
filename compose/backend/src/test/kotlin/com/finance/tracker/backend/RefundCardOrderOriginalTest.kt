package com.finance.tracker.backend


import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * 原 `TestCardNumber` 两个卡号保留定义与 `TestAlipayOrderPrefix007` 一个订单前缀定义。
 *
 * 信用卡行按列位置映射后走真实归一、资金同账户选择与关系规划；支付宝定义读取原
 * UTF-8 BOM CSV，经实际解码、归一路径、资金选择和当前关系规划器核对订单前缀退款。
 */
class RefundCardOrderOriginalTest {
    private val resource: JsonObject by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/original-refund-card-order.json"))
            .bufferedReader().use { it.readText() }).jsonObject
    }

    @Test fun originalCardNumberRetentionDefinitionsUseTheCurrentPipeline() {
        val cases = resource.getValue("cards").jsonArray.map { it.jsonObject }
        assertEquals(2, cases.size)
        assertEquals(2, cases.map { it.getValue("source").jsonPrimitive.content }.toSet().size)
        for (item in cases) {
            val source = item.getValue("source").jsonPrimitive.content
            val cells = item.getValue("cells").jsonArray.map { row -> row.jsonObject.mapValues { it.value.jsonPrimitive.content } }
            val document = CashStatementDocument("icbc_credit", "pdf:0", "icbc-refund-card-v1",
                cells.mapIndexed { index, cell -> RawCashRow(index + 1, cell, documentPosition = "p1:t0:r${index + 1}") },
                null, "工商银行信用卡", CashSourceCoverage(null, null, false, "", emptyMap(), "icbc-pdf-coverage-v1"),
                cells.size.toLong(), cells.size)
            val observed = mutableListOf<Map<String, Any?>>()
            val parsed = CashStatementRowNormalization.normalizeIcbcDocument(document) { _, record -> observed += record }
            val file = ParsedImportFile("file_a", "a".repeat(64), "parsed", parsed, clientFileRef = "client_a")
            val funding = CashImportFunding.scan(listOf(file))
            val selection = funding.groups.associate { it.sourceAccountRef to "101" }
            val resolved = CashImportFunding.resolve(funding, selection.mapValues {
                mapOf("account_id" to it.value, "expected_account_revision" to 1)
            }, mapOf("101" to mapOf("id" to "101", "active" to true, "type" to "cash", "currencies" to listOf("CNY"))))
            val account = resolved.associate { it.rowRef to it.fields.getValue("account_id").toString() }
            val facts = observed.mapIndexed { index, record ->
                val fields = assertNotNull(parsed.rows[index].fields, source)
                RelationFact(id = "f${index + 1}", amount = assertIs<BigDecimal>(record["amount"]),
                    currency = assertIs<String>(record["currency"]), accountId = account.getValue("file_a:r${parsed.rows[index].position}"),
                    accountName = "工商银行信用卡", occurredAt = fields.getValue("occurred_at"),
                    counterparty = assertIs<String>(record["counterparty"]), note = record["note"] as? String ?: "",
                    recordType = fields.getValue("record_type") as String,
                    recordSubtype = fields.getValue("record_subtype") as String,
                    billSource = "icbc_credit", source = "icbc_credit", recordId = "r${index + 1}",
                    rawPayload = record, relationMetadata = record)
            }
            val proposals = CashRelationPlanning.planRelationProposals(facts, seedIds = facts.map { it.id }, workspaceId = "w").proposals
            val pairs = proposals.filter { it.kind == "refund_offset" && !it.secondaryFactId.isNullOrEmpty() }
            for (check in item.getValue("checks").jsonArray) {
                val node = check.jsonObject
                when (node.getValue("kind").jsonPrimitive.content) {
                    "record_count" -> assertEquals(checkValue(node), observed.size, source)
                    "pair_count" -> assertEquals(checkValue(node), pairs.size, source)
                    "card_number" -> parsed.rows.forEachIndexed { index, row ->
                        val expected = node.getValue("value").jsonPrimitive.content
                        assertEquals(expected, observed[index]["card_number"], source)
                        assertEquals(expected, row.sourceExplanation["card_number"], source)
                    }
                    else -> error("Unexpected card check")
                }
            }
        }
    }

    private fun checkValue(check: JsonObject): Int =
        check.getValue("value").jsonPrimitive.content.toInt()

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun originalAlipayClosedRefundOrderPrefixDefinitionUsesCurrentDecode() {
        val item = resource.getValue("alipay").jsonObject
        val source = item.getValue("source").jsonPrimitive.content
        val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_REFUND_CARD_ORDER_DIR")))
        val bytes = Files.readAllBytes(directory.resolve(item.getValue("file").jsonPrimitive.content))
        val document = CashStatementDocuments.readDocument(bytes, "alipay")
        assertEquals(2, document.rows.size, source)
        val captured = mutableListOf<Map<String, Any?>>()
        val parsed = CashStatementRowNormalization.normalizePlatformDocument(document) { _, record -> captured += record }
        val file = ParsedImportFile("file_a", "a".repeat(64), "parsed", parsed, clientFileRef = "client_a")
        val funding = CashImportFunding.scan(listOf(file))
        val selection = funding.groups.associate { it.sourceAccountRef to "101" }
        val resolved = CashImportFunding.resolve(funding, selection.mapValues {
            mapOf("account_id" to it.value, "expected_account_revision" to 1)
        }, mapOf("101" to mapOf("id" to "101", "active" to true, "type" to "cash", "currencies" to listOf("CNY"))))
        val account = resolved.associate { it.rowRef to it.fields.getValue("account_id").toString() }
        val facts = captured.mapIndexed { index, record ->
            val fields = assertNotNull(parsed.rows[index].fields, source)
            RelationFact(id = "f${index + 1}", amount = assertIs<BigDecimal>(record["amount"]),
                currency = fields.getValue("currency") as String, accountId = account.getValue("file_a:r${parsed.rows[index].position}"),
                accountName = "现金", occurredAt = fields.getValue("occurred_at"),
                counterparty = assertIs<String>(record["counterparty"]), note = record["note"] as? String ?: "",
                recordType = fields.getValue("record_type") as String,
                recordSubtype = fields.getValue("record_subtype") as String,
                billSource = "alipay", source = "alipay", recordId = (record["txn_id"] as? String).orEmpty(),
                rawPayload = record, relationMetadata = record)
        }
        val rows = facts.mapIndexed { index, fact ->
            val record = captured[index]
            linkedMapOf<String, Any?>("id" to fact.id, "txn_id" to (record["txn_id"] ?: ""),
                "amount" to fact.amount.toPlainString(), "raw_payload" to record, "bill_source" to "alipay",
                "source" to "alipay", "date" to fact.occurredAt, "record_type" to fact.recordType,
                "account_id" to fact.accountId, "currency" to fact.currency,
                "merchant_order_id" to (record["merchant_order_id"] ?: ""),
                "platform_status" to (record["platform_status"] ?: ""),
                "txn_type" to (record["txn_type"] ?: ""))
        }
        val plan = CashRelationPlanning.planRelationProposals(facts, detailedRows = rows,
            seedIds = facts.map { it.id }, workspaceId = "w")
        val proposals = plan.proposals.filter { it.kind == "refund_offset" }
        assertTrue(proposals.any { it.ruleId == "scan.alipay.order_prefix.v1" }, source)
        for (check in item.getValue("checks").jsonArray) {
            val node = check.jsonObject
            when (node.getValue("kind").jsonPrimitive.content) {
                "record_count" -> assertEquals(checkValue(node), facts.size, source)
                "amounts" -> {
                    val expected = node.getValue("values").jsonArray.map { BigDecimal(it.jsonPrimitive.content) }
                    val actual = facts.map { it.amount }.sorted()
                    assertEquals(expected.size, actual.size, source)
                    expected.zip(actual).forEach { (left, right) -> assertEquals(0, left.compareTo(right), source) }
                }
                "order_rule_contains" -> assertTrue(proposals.any {
                    node.getValue("value").jsonPrimitive.content in it.ruleId
                }, "$source ${proposals.map { it.ruleId }}")
                else -> error("Unexpected Alipay check")
            }
        }
    }
}
