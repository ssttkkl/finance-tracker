package com.finance.tracker.backend

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * 原 `TestAlipayCategory` 的 5 个基础退款定义。原 CSV 工厂字节被真实解码，
 * 经归一路径、资金选择和当前关系规划器核对事实、金额、信号、强度与全额／部分关系。
 */
class AlipayRefundBasicOriginalTest {
    private val cases: JsonArray by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/original-alipay-refund-basics.json"))
            .bufferedReader().use { it.readText() }).jsonObject.getValue("cases").jsonArray
    }

    @Test fun originalBasicAlipayRefundDefinitionsUseCurrentDecode() {
        val definitions = cases.map { it.jsonObject }
        assertEquals(5, definitions.size)
        for (item in definitions) {
            val source = item.getValue("source").jsonPrimitive.content
            val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_ALIPAY_REFUND_DIR")))
            val bytes = Files.readAllBytes(directory.resolve(item.getValue("file").jsonPrimitive.content))
            val document = CashStatementDocuments.readDocument(bytes, "alipay")
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
                    currency = fields.getValue("currency") as String,
                    accountId = account.getValue("file_a:r${parsed.rows[index].position}"),
                    accountName = "现金", occurredAt = fields.getValue("occurred_at"),
                    counterparty = assertIs<String>(record["counterparty"]), note = record["note"] as? String ?: "",
                    recordType = fields.getValue("record_type") as String,
                    recordSubtype = fields.getValue("record_subtype") as String,
                    billSource = "alipay", source = "alipay", recordId = (record["txn_id"] as? String).orEmpty(),
                    rawPayload = record, relationMetadata = record)
            }
            val plan = CashRelationPlanning.planRelationProposals(facts, seedIds = facts.map { it.id }, workspaceId = "w")
            val pairs = plan.proposals.filter { it.kind == "refund_offset" && !it.secondaryFactId.isNullOrEmpty() }

            val byId = facts.associateBy { it.id }
            for (check in item.getValue("checks").jsonArray) {
                val node = check.jsonObject
                when (node.getValue("kind").jsonPrimitive.content) {
                    "record_count" -> assertEquals(checkValue(node), facts.size, source)
                    "pair_count" -> assertEquals(checkValue(node), pairs.size, source)
                    "categories" -> assertEquals(node.getValue("values").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                        captured.map { assertIs<String>(it["category"], source) }.toSet(), source)
                    "amount_of" -> {
                        val category = node.getValue("category").jsonPrimitive.content
                        val record = captured.first { it["category"] == category }
                        assertEquals(0, BigDecimal(stringValue(node)).compareTo(assertIs<BigDecimal>(record["amount"])), source)
                    }
                    "source_signal" -> {
                        val refund = facts.single { it.amount.signum() > 0 }
                        assertEquals(stringValue(node), refund.rawPayload?.get("_refund_signal"), source)
                    }
                    "strength" -> {
                        val proposal = plan.proposals.filter { it.kind == "refund_offset" }.single()
                        val actual = if (proposal.status == "accepted" && proposal.confidence == "strong") "strong" else "weak"
                        val expected = item["current_strength"]?.jsonPrimitive?.content ?: stringValue(node)
                        if (item.containsKey("current_strength")) {
                            assertNotEquals(stringValue(node), expected, source)
                            assertTrue(pairs.isEmpty(), source)
                            assertTrue(proposal.secondaryFactId.isNullOrEmpty(), source)
                        }
                        assertEquals(expected, actual, source)
                    }
                    "match_type" -> {
                        val proposal = pairs.single()
                        val refundAmount = BigDecimal(proposal.evidence.extras.getValue("refund_amount").toString())
                        val expenseAmount = BigDecimal(proposal.evidence.extras.getValue("expense_amount").toString())
                        assertEquals(stringValue(node), if (refundAmount.compareTo(expenseAmount) == 0) "full" else "partial", source)
                        assertTrue(byId.containsKey(proposal.primaryFactId), source)
                    }
                    else -> error("Unexpected Alipay refund check")
                }
            }
        }
    }

    private fun checkValue(check: JsonObject): Int =
        check.getValue("value").jsonObject.getValue("value").jsonPrimitive.content.toInt()

    private fun stringValue(check: JsonObject): String =
        check.getValue("value").jsonObject.getValue("value").jsonPrimitive.content
}
