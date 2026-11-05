package com.finance.tracker.backend

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * 原 `TestAlipayCategory` 的 7 个退款匹配优先级定义。原 CSV 工厂字节被真实解码，
 * 经当前归一、资金同账户选择与关系规划器核对配对对象、订单锁、候选数、强度及差异。
 */
@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class AlipayRefundMatchingOriginalTest {
    private val cases: JsonArray by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/original-alipay-refund-matching.json"))
            .bufferedReader().use { it.readText() }).jsonObject.getValue("cases").jsonArray
    }

    @Test fun originalAlipayRefundMatchingDefinitionsUseCurrentPlanner() {
        val definitions = cases.map { it.jsonObject }
        assertEquals(7, definitions.size)
        val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_ALIPAY_REFUND_DIR")))
        for (item in definitions) {
            val source = item.getValue("source").jsonPrimitive.content
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
            val proposal = plan.proposals.filter { it.kind == "refund_offset" }.single()
            val pairs = plan.proposals.filter { it.kind == "refund_offset" && !it.secondaryFactId.isNullOrEmpty() }
            val primary = facts.first { it.id == proposal.primaryFactId }
            val secondary = facts.firstOrNull { it.id == proposal.secondaryFactId }
            val current = item["current"]?.jsonObject
            val actualStrength = if (proposal.status == "accepted" && proposal.confidence == "strong") "strong" else "weak"
            if (current?.get("signal")?.jsonPrimitive?.content == "open_leg_zero_candidate") {
                assertTrue(pairs.isEmpty(), source)
                assertTrue(proposal.secondaryFactId.isNullOrEmpty(), source)
            }

            for (check in item.getValue("checks").jsonArray) {
                val node = check.jsonObject
                when (node.getValue("kind").jsonPrimitive.content) {
                    "record_count" -> assertEquals(checkValue(node), facts.size, source)
                    "pair_count" -> assertEquals(checkValue(node), pairs.size, source)
                    "expense_field" -> {
                        val field = node.getValue("field").jsonPrimitive.content
                        assertEquals(stringValue(node), primary.rawPayload?.get(field)?.toString(), "$source $field")
                    }
                    "rule_hint" -> {
                        val rule = current?.get("rule")?.jsonPrimitive?.content
                        val signal = current?.get("signal")?.jsonPrimitive?.content
                        when {
                            rule != null -> assertEquals(rule, proposal.ruleId, source)
                            signal != null -> assertTrue(signal in proposal.evidence.signals, "$source $signal")
                            else -> error("No current rule or signal mapping for $source")
                        }
                    }
                    "candidate_count" -> {
                        val expected = current?.get("candidate_count")?.jsonPrimitive?.content?.toInt()
                            ?: checkValue(node)
                        if (current?.containsKey("candidate_count") == true)
                            assertNotEquals(checkValue(node), expected, source)
                        assertEquals(expected, proposal.evidence.candidateCount, source)
                    }
                    "strength" -> {
                        val expected = current?.get("strength")?.jsonPrimitive?.content ?: stringValue(node)
                        if (current?.containsKey("strength") == true)
                            assertNotEquals(stringValue(node), expected, source)
                        assertEquals(expected, actualStrength, source)
                    }
                    "source_signal" -> {
                        assertEquals(stringValue(node), secondary?.rawPayload?.get("_refund_signal"), source)
                    }
                    else -> error("Unexpected Alipay refund matching check")
                }
            }
        }
    }

    private fun checkValue(check: JsonObject): Int =
        check.getValue("value").jsonObject.getValue("value").jsonPrimitive.content.toInt()

    private fun stringValue(check: JsonObject): String =
        check.getValue("value").jsonObject.getValue("value").jsonPrimitive.content
}
