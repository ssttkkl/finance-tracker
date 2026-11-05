package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * 原 `TestIcbcRefundPlatform`（1 定义）与 `TestCcbRefundClassification`（2 定义）。
 *
 * 旧 `_parse_icbc_lines`／`_classify_refund_match` 包装退役。ICBC 定义走真实归一路径、
 * 资金分组与关系规划；CCB 定义只提供旧过滤输入，原生测试用显式同账户、同币种、同额
 * 商户事实核对当前关系规划器的强度，并保留原强度字面量。
 */
class RefundClassificationOriginalTest {
    private val resource: JsonObject by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/original-refund-classification.json"))
            .bufferedReader().use { it.readText() }).jsonObject
    }

    @Test fun originalIcbcRefundPlatformDefinitionUsesTheCurrentPipeline() {
        val item = resource.getValue("platform").jsonObject
        val source = item.getValue("source").jsonPrimitive.content
        val cells = item.getValue("cells").jsonArray.map { row -> row.jsonObject.mapValues { it.value.jsonPrimitive.content } }
        val document = CashStatementDocument("icbc_credit", "pdf:0", "icbc-refund-platform-v1",
            cells.mapIndexed { index, cell -> RawCashRow(index + 1, cell, documentPosition = "p1:t0:r${index + 1}") },
            null, "工商银行信用卡", CashSourceCoverage(null, null, false, "", emptyMap(), "icbc-pdf-coverage-v1"),
            cells.size.toLong(), cells.size)
        val observed = mutableListOf<Map<String, Any?>>()
        val parsed = CashStatementRowNormalization.normalizeIcbcDocument(document) { _, record -> observed += record }
        assertEquals(cells.size, observed.size, source)
        val file = ParsedImportFile("file_a", "a".repeat(64), "parsed", parsed, clientFileRef = "client_a")
        val funding = CashImportFunding.scan(listOf(file))
        val selection = funding.groups.associate { it.sourceAccountRef to "101" }
        val resolved = CashImportFunding.resolve(funding, selection.mapValues {
            mapOf("account_id" to it.value, "expected_account_revision" to 1)
        }, mapOf("101" to mapOf("id" to "101", "active" to true, "type" to "cash",
            "currencies" to listOf("CNY"))))
        val account = resolved.associate { it.rowRef to it.fields.getValue("account_id").toString() }
        val facts = observed.mapIndexed { index, record ->
            val fields = assertNotNull(parsed.rows[index].fields, source)
            RelationFact(id = "f${index + 1}", amount = assertIs<BigDecimal>(record["amount"]),
                currency = assertIs<String>(record["currency"]), accountId = account.getValue("file_a:r${index + 1}"),
                accountName = "工商银行信用卡", occurredAt = fields.getValue("occurred_at"),
                counterparty = assertIs<String>(record["counterparty"]), note = record["note"] as? String ?: "",
                recordType = fields.getValue("record_type") as String,
                recordSubtype = fields.getValue("record_subtype") as String,
                billSource = "icbc_credit", source = "icbc_credit", recordId = "r${index + 1}",
                rawPayload = record, relationMetadata = record)
        }
        val proposals = CashRelationPlanning.planRelationProposals(facts, seedIds = facts.map { it.id }, workspaceId = "w").proposals
        val pairs = proposals.filter { it.kind == "refund_offset" && !it.secondaryFactId.isNullOrEmpty() }
        val byId = facts.associateBy { it.id }
        for (check in item.getValue("checks").jsonArray) {
            val node = check.jsonObject
            when (node.getValue("kind").jsonPrimitive.content) {
                "record_count" -> assertEquals(checkValue(node), observed.size, source)
                "pair_count" -> assertEquals(checkValue(node), pairs.size, source)
                "refund_counterparty" -> {
                    val pair = pairs.single()
                    val expense = byId.getValue(pair.primaryFactId)
                    val refund = byId.getValue(assertNotNull(pair.secondaryFactId, source))
                    assertTrue(expense.amount.signum() < 0, source)
                    assertTrue(refund.amount.signum() > 0, source)
                    assertEquals(node.getValue("value").jsonObject.getValue("value").jsonPrimitive.content,
                        refund.counterparty, source)
                }
                else -> error("Unexpected platform check")
            }
        }
    }

    private fun checkValue(check: JsonObject): Int =
        check.getValue("value").jsonObject.getValue("value").jsonPrimitive.content.toInt()

    @Test fun originalCcbRefundStrengthDefinitionsUseTheCurrentPlanner() {
        val cases = resource.getValue("ccb").jsonArray.map { it.jsonObject }
        assertEquals(2, cases.size)
        for (case in cases) {
            val source = case.getValue("source").jsonPrimitive.content
            assertEquals("ccb_debit_refund", case.getValue("signal").jsonPrimitive.content)
            assertTrue(case.getValue("same_cluster").jsonPrimitive.boolean)
            assertTrue(case.getValue("exact_amt").jsonPrimitive.boolean)
            assertEquals("refund_cp_match", case.getValue("rule_hint").jsonPrimitive.content)
            val count = case.getValue("candidate_count").jsonPrimitive.int
            val occurred = case.getValue("occurred_at").jsonPrimitive.content
            val expenseOccurred = case.getValue("expense_occurred_at").jsonPrimitive.content
            val refund = RelationshipFacts.refund("r1", "100.00", "商户", "${occurred}T00:00:00+00:00", "ccb_debit")
            val expenses = (1..count).map { index ->
                RelationshipFacts.expense("e$index", "-100.00", "商户", "${expenseOccurred}T00:00:00+00:00", "ccb_debit")
            }
            val plan = CashRelationPlanning.planRelationProposals(listOf(refund) + expenses,
                seedIds = (listOf(refund) + expenses).map { it.id }, workspaceId = "w")
            val proposals = plan.proposals.filter { it.kind == "refund_offset" }
            val proposal = proposals.singleOrNull()
            assertNotNull(proposal, source)
            val original = case.getValue("original_strength").jsonPrimitive.content
            val current = if (proposal.status == "accepted" && proposal.confidence == "strong") "strong"
                else if (proposal.status == "pending_review" && proposal.confidence == "weak") "weak"
                else error("unexpected current strength: ${proposal.status}/${proposal.confidence}")
            assertEquals(original, current, source)
            assertEquals(count, proposal.evidence.candidateCount, source)
        }
    }
}

private object RelationshipFacts {
    fun refund(id: String, amount: String, counterparty: String, occurredAt: String, source: String) = RelationFact(
        id = id, amount = BigDecimal(amount), currency = "CNY", accountId = "a", accountName = "现金",
        occurredAt = occurredAt, counterparty = counterparty, recordType = "refund",
        recordSubtype = "not_applicable", billSource = source, source = source, recordId = id)
    fun expense(id: String, amount: String, counterparty: String, occurredAt: String, source: String) = RelationFact(
        id = id, amount = BigDecimal(amount), currency = "CNY", accountId = "a", accountName = "现金",
        occurredAt = occurredAt, counterparty = counterparty, recordType = "consumption",
        recordSubtype = "not_applicable", billSource = source, source = source, recordId = id)
}
