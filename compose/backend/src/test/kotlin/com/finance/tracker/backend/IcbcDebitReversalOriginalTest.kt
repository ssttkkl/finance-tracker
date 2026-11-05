package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * 原 `TestIcbcDebitReversal` 的 3 个撤销定义与 4 个借记卡退款定义。
 *
 * 旧 `_pair_reversals` 包装与 `offset_role`／`proposed_action` 元数据退役；撤销定义
 * 用当前事实类型核对“不产生撤销关系且双方保留”，退款定义按列位置映射十三列借记卡
 * 表格，走真实归一、资金同账户选择与当前关系规划器。
 */
class IcbcDebitReversalOriginalTest {
    private val resource: JsonObject by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/original-icbc-debit-reversal.json"))
            .bufferedReader().use { it.readText() }).jsonObject
    }

    @Test fun originalReversalDefinitionsKeepFactsWithoutReversalRelation() {
        val cases = resource.getValue("reversals").jsonArray.map { it.jsonObject }
        assertEquals(3, cases.size)
        for (item in cases) {
            val source = item.getValue("source").jsonPrimitive.content
            val records = item.getValue("records").jsonArray.map { it.jsonObject }
            val facts = records.mapIndexed { index, record ->
                val amount = BigDecimal(record.getValue("amount").jsonPrimitive.content)
                val note = record.getValue("note").jsonPrimitive.content
                val reversal = note.contains("撤销")
                RelationFact(id = "f${index + 1}", amount = amount, currency = "CNY", accountId = "a",
                    accountName = "工商银行借记卡", occurredAt = record.getValue("occurred_at").jsonPrimitive.content,
                    counterparty = record.getValue("counterparty").jsonPrimitive.content, note = note,
                    recordType = if (reversal) "reversal" else if (amount.signum() > 0) "refund" else "consumption",
                    recordSubtype = if (reversal) "reversal" else "not_applicable",
                    billSource = "icbc_debit", source = "icbc_debit", recordId = "r${index + 1}")
            }
            val plan = CashRelationPlanning.planRelationProposals(facts, seedIds = facts.map { it.id }, workspaceId = "w")
            if (source.endsWith("test_购汇还款撤销配对") || source.endsWith("test_不同对方不配对"))
                assertTrue(plan.proposals.isEmpty(), source)
            if (source.endsWith("test_非撤销收入不配对")) {
                assertEquals(0, item.getValue("checks").jsonArray.map { it.jsonObject }
                    .first { it.getValue("kind").jsonPrimitive.content == "pair_count" }.let(::checkValue), source)
                assertTrue(plan.proposals.any { it.kind == "refund_offset" }, source)
            }
            val reversalRelations = plan.proposals.filter {
                it.kind == "reversal" || listOfNotNull(it.primaryFactId, it.secondaryFactId).any { id ->
                    facts.first { it.id == id }.recordType == "reversal"
                }
            }
            for (check in item.getValue("checks").jsonArray) {
                val node = check.jsonObject
                when (node.getValue("kind").jsonPrimitive.content) {
                    "record_count" -> assertEquals(facts.size, checkValue(node), source)
                    "pair_count" -> {
                        val expected = if (source.endsWith("test_购汇还款撤销配对")) 1 else 0
                        assertEquals(expected, checkValue(node), source)
                        assertEquals(0, reversalRelations.size, source)
                    }
                    "match_type" -> {
                        assertEquals("full", node.getValue("value").jsonObject
                            .getValue("value").jsonPrimitive.content, source)
                        assertTrue(reversalRelations.isEmpty(), source)
                    }
                    else -> error("Unexpected reversal check")
                }
            }
        }
    }

    @Test fun originalDebitRefundDefinitionsUseTheCurrentPipeline() {
        val cases = resource.getValue("refunds").jsonArray.map { it.jsonObject }
        assertEquals(4, cases.size)
        for (item in cases) {
            val source = item.getValue("source").jsonPrimitive.content
            val cells = item.getValue("cells").jsonArray.map { row -> row.jsonObject.mapValues { it.value.jsonPrimitive.content } }
            val lines = item.getValue("lines").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(cells, debitCells(lines), source)
            assertEquals(item.getValue("adapted_summary_rows").jsonArray.map { it.jsonPrimitive.int },
                debitCells(lines).mapIndexedNotNull { index, cell -> (index + 1).takeIf { cell.getValue("摘要") == "消费" } }, source)
            val owner = item.getValue("owner").jsonPrimitive.content
            val document = CashStatementDocument("icbc_debit", "pdf:0", "icbc-debit-refund-v1",
                cells.mapIndexed { index, cell -> RawCashRow(index + 1, cell, documentPosition = "p1:t0:r${index + 1}") },
                owner, "工商银行借记卡", CashSourceCoverage(null, null, false, "", emptyMap(), "icbc-pdf-coverage-v1"),
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
                    currency = fields.getValue("currency") as String,
                    accountId = account.getValue("file_a:r${parsed.rows[index].position}"),
                    accountName = "工商银行借记卡", occurredAt = fields.getValue("occurred_at"),
                    counterparty = assertIs<String>(record["counterparty"]), note = record["note"] as? String ?: "",
                    recordType = fields.getValue("record_type") as String,
                    recordSubtype = fields.getValue("record_subtype") as String,
                    billSource = "icbc_debit", source = "icbc_debit", recordId = "r${index + 1}",
                    rawPayload = record, relationMetadata = record)
            }
            val plan = CashRelationPlanning.planRelationProposals(facts, seedIds = facts.map { it.id }, workspaceId = "w")
            val proposals = plan.proposals.filter { it.kind == "refund_offset" }
            val byId = facts.associateBy { it.id }
            for (check in item.getValue("checks").jsonArray) {
                val node = check.jsonObject
                when (node.getValue("kind").jsonPrimitive.content) {
                    "fact_count" -> assertEquals(facts.size, checkValue(node), source)
                    "pair_count" -> assertEquals(checkValue(node), proposals.count { !it.secondaryFactId.isNullOrEmpty() }, source)
                    "strength" -> assertEquals(stringValue(node),
                        proposals.filter { !it.secondaryFactId.isNullOrEmpty() }.single().let {
                            if (it.status == "accepted" && it.confidence == "strong") "strong" else "weak"
                        }, source)
                    "candidate_count" -> assertEquals(checkValue(node),
                        proposals.filter { !it.secondaryFactId.isNullOrEmpty() }.single().evidence.candidateCount, source)
                    "expense_amount" -> {
                        val proposal = proposals.filter { !it.secondaryFactId.isNullOrEmpty() }.single()
                        assertEquals(0, BigDecimal(stringValue(node))
                            .compareTo(byId.getValue(proposal.primaryFactId).amount), source)
                    }
                    "expense_role" -> {
                        val proposal = proposals.filter { !it.secondaryFactId.isNullOrEmpty() }.single()
                        assertTrue(byId.getValue(proposal.primaryFactId).amount.signum() < 0, source)
                        assertEquals(stringValue(node), "expense", source)
                    }
                    "refund_role" -> {
                        val proposal = proposals.filter { !it.secondaryFactId.isNullOrEmpty() }.single()
                        assertTrue(byId.getValue(assertNotNull(proposal.secondaryFactId)).amount.signum() > 0, source)
                        assertEquals(stringValue(node), "refund", source)
                    }
                    "proposed_action" -> {
                        val proposal = proposals.filter { !it.secondaryFactId.isNullOrEmpty() }.single()
                        assertTrue("merge_refund_into" in stringValue(node), source)
                        assertEquals("refund_offset", proposal.kind, source)
                        assertTrue(byId.getValue(proposal.primaryFactId).amount.signum() < 0, source)
                        assertTrue(byId.getValue(assertNotNull(proposal.secondaryFactId)).amount.signum() > 0, source)
                    }
                    "offset_strength" -> {
                        val proposal = proposals.filter { !it.secondaryFactId.isNullOrEmpty() }.single()
                        assertEquals(stringValue(node), proposal.confidence, source)
                    }
                    "income_count" -> assertEquals(observed.count { (it["amount"] as BigDecimal).signum() > 0 }, checkValue(node), source)
                    else -> error("Unexpected debit refund check")
                }
            }
        }
    }

    private fun debitCells(lines: List<String>): List<Map<String, String>> {
        val rows = mutableListOf<MutableList<String>>()
        val current = mutableListOf<String>()
        for (line in lines) {
            if (line.length == 10 && line[4] == '-' && line[7] == '-' && line.replace("-", "").all { it.isDigit() }) {
                if (current.isNotEmpty()) rows += current.toMutableList()
                current.clear(); current += line
            } else current += line
        }
        if (current.isNotEmpty()) rows += current
        val channels = setOf("快捷支付", "网上银行", "手机银行", "其他", "批量业务")
        return rows.map { row ->
            val date = row[0]; val time = row[1]; val card = row[2]; val amount = row[3]
            val rest = row.drop(4).filter { it.isNotEmpty() }.toMutableList()
            val summary = if (rest.isNotEmpty() && rest[0] in setOf("退款", "退货", "撤销交易")) rest.removeAt(0) else ""
            var channel = ""; var counterparty = ""
            if (rest.isNotEmpty() && rest[0] in channels) {
                channel = rest.removeAt(0)
                if (rest.isNotEmpty()) counterparty = rest.removeAt(0)
            } else {
                if (rest.isNotEmpty()) counterparty = rest.removeAt(0)
                if (rest.isNotEmpty()) channel = rest.removeAt(0)
            }
            val adapted = if (summary.isEmpty() && amount.startsWith("-")) "消费" else summary
            linkedMapOf("交易日期" to "$date $time", "账号" to card, "储种" to "", "序号" to "",
                "币种" to "人民币", "钞汇" to "", "摘要" to adapted, "地区" to "",
                "收入/支出金额" to amount, "余额" to "", "对方户名" to counterparty,
                "对方账号" to "", "渠道" to channel)
        }
    }

    private fun checkValue(check: JsonObject): Int =
        check.getValue("value").jsonObject.getValue("value").jsonPrimitive.content.toInt()

    private fun stringValue(check: JsonObject): String =
        check.getValue("value").jsonObject.getValue("value").jsonPrimitive.content
}
