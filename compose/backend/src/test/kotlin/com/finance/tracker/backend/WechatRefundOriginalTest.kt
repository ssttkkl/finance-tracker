package com.finance.tracker.backend

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * 原 `TestWechatCategory` 的 9 个退款定义。原 XLSX 工厂字节被真实解码，
 * 经当前归一、资金同账户选择与关系规划器核对配对消费、规则、候选数、强度及转账退回分类。
 */
class WechatRefundOriginalTest {
    private val cases: JsonArray by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/original-wechat-refund.json"))
            .bufferedReader().use { it.readText() }).jsonObject.getValue("cases").jsonArray
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun originalWechatRefundDefinitionsUseCurrentPipeline() {
        val definitions = cases.map { it.jsonObject }
        assertEquals(9, definitions.size)
        val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_WECHAT_REFUND_DIR")))
        for (item in definitions) {
            val source = item.getValue("source").jsonPrimitive.content
            val bytes = Files.readAllBytes(directory.resolve(item.getValue("file").jsonPrimitive.content))
            val document = CashStatementDocuments.readDocument(bytes, "wechat")
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
                val amount = assertIs<BigDecimal>(record["amount"])
                // 与 CashImportRelationDiscovery.matchingFacts 一致：微信原始消费行注入 offset_role=expense。
                val metadata = LinkedHashMap<String, Any?>(record)
                if (fields.getValue("record_type") == "refund" && amount.signum() < 0 &&
                    CashPlatformRefund.wechatIsRefundOriginExpense(
                        record["_wechat_direction"] as? String, record["platform_status"] as? String)) {
                    metadata["offset_role"] = "expense"
                }
                RelationFact(id = "f${index + 1}", amount = amount,
                    currency = fields.getValue("currency") as String,
                    accountId = account.getValue("file_a:r${parsed.rows[index].position}"),
                    accountName = "现金", occurredAt = fields.getValue("occurred_at"),
                    counterparty = assertIs<String>(record["counterparty"]), note = record["note"] as? String ?: "",
                    recordType = fields.getValue("record_type") as String,
                    recordSubtype = fields.getValue("record_subtype") as String,
                    billSource = "wechat", source = "wechat", recordId = (record["txn_id"] as? String).orEmpty(),
                    rawPayload = metadata, relationMetadata = metadata)
            }
            val plan = CashRelationPlanning.planRelationProposals(facts, seedIds = facts.map { it.id }, workspaceId = "w")
            // 原 `_pairs_only` 只统计消费退款关系；微信红包／转账退回的 transfer_return 不计入。
            val pairs = plan.proposals.filter {
                it.kind == "refund_offset" && !it.secondaryFactId.isNullOrEmpty() &&
                    it.ruleId != "scan.wechat.transfer_return.v1"
            }
            val byId = facts.associateBy { it.id }
            val current = item["current"]?.jsonObject
            if (current?.containsKey("transfer_return") == true) {
                val transferReturn = plan.proposals.count {
                    it.kind == "refund_offset" && it.ruleId == "scan.wechat.transfer_return.v1"
                }
                assertEquals(current.getValue("transfer_return").jsonPrimitive.content.toInt(), transferReturn, source)
            }

            for (check in item.getValue("checks").jsonArray) {
                val node = check.jsonObject
                when (node.getValue("kind").jsonPrimitive.content) {
                    "record_count" -> assertEquals(checkValue(node), facts.size, source)
                    "pair_count" -> assertEquals(checkValue(node), pairs.size, source)
                    "empty_pairs" -> {
                        assertTrue(pairs.isEmpty(), source)
                        // 原读取器不生成转账退回关系；若规划器产生退款类关系，只允许 transfer_return。
                        assertTrue(plan.proposals.filter { it.kind == "refund_offset" }
                            .all { it.ruleId == "scan.wechat.transfer_return.v1" }, "$source ${plan.proposals.map { it.ruleId }}")
                    }
                    "all_pairs_strong" -> assertTrue(pairs.isNotEmpty() &&
                        pairs.all { it.status == "accepted" && it.confidence == "strong" }, source)
                    "amount_of" -> {
                        val category = node.getValue("category").jsonPrimitive.content
                        val record = captured.first { it["category"] == category }
                        assertEquals(0, BigDecimal(stringValue(node)).compareTo(assertIs<BigDecimal>(record["amount"])), source)
                    }
                    "record_amount" -> {
                        val key = node.keys.first { it in setOf("date", "category") }
                        val value = node.getValue(key).jsonPrimitive.content
                        val record = captured.first { it[key]?.toString() == value }
                        assertEquals(0, BigDecimal(stringValue(node)).compareTo(assertIs<BigDecimal>(record["amount"])), source)
                    }
                    "category_count" -> {
                        val category = node.getValue("category").jsonPrimitive.content
                        assertEquals(checkValue(node), captured.count { it["category"] == category }, source)
                    }
                    "record_types" -> assertEquals(node.getValue("values").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                        facts.map { it.recordType }.toSet(), source)
                    "rule_contains" -> {
                        val keywords = node.getValue("values").jsonArray.map { it.jsonPrimitive.content }
                        assertTrue(pairs.isNotEmpty() &&
                            pairs.all { proposal -> keywords.any { it in proposal.ruleId } }, "$source ${pairs.map { it.ruleId }}")
                    }
                    "strength" -> assertTrue(pairs.isNotEmpty() &&
                        pairs.all { it.status == "accepted" && it.confidence == "strong" }, "$source ${stringValue(node)}")
                    "expense_field" -> {
                        val field = node.getValue("field").jsonPrimitive.content
                        val primary = byId.getValue(pairs.single().primaryFactId)
                        assertEquals(stringValue(node), primary.rawPayload?.get(field)?.toString(), "$source $field")
                    }
                    "candidate_count" -> assertEquals(checkValue(node), pairs.single().evidence.candidateCount, source)
                    else -> error("Unexpected WeChat refund check")
                }
            }
        }
    }

    @Test fun negativeRefundNeedsPersistedOriginalRole() {
        val fact = RelationFact(id = "f1", amount = BigDecimal("-1.00"), currency = "CNY", accountId = "101",
            counterparty = "商家", recordType = "refund", recordSubtype = "not_applicable",
            billSource = "wechat", source = "wechat", rawPayload = emptyMap(), relationMetadata = emptyMap())
        assertFalse(CashRelationCore.isRefundExpenseCandidate(fact))
        assertTrue(CashRelationCore.isRefundExpenseCandidate(
            fact.copy(relationMetadata = mapOf("offset_role" to "expense"))))
    }

    private fun checkValue(check: JsonObject): Int =
        check.getValue("value").jsonObject.getValue("value").jsonPrimitive.content.toInt()

    private fun stringValue(check: JsonObject): String =
        check.getValue("value").jsonObject.getValue("value").jsonPrimitive.content
}
