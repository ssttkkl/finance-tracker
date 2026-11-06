package com.finance.tracker.backend

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class PlatformFactOriginalTest {
    @Test fun originalCsvAndXlsxFactsUseActualDecodeAndNormalization() {
        val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_PLATFORM_FACT_ORIGINAL_DIR")))
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/original-platform-fact-assertions.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        assertEquals(23, cases.size)
        assertEquals(23, cases.map { it.jsonObject.getValue("source") }.toSet().size)
        assertEquals(74, cases.sumOf { it.jsonObject.getValue("checks").jsonArray.size })
        assertEquals(25, cases.sumOf { it.jsonObject.getValue("rows").jsonArray.size })
        for (case in cases) {
            val item = case.jsonObject
            val source = item.getValue("source").jsonPrimitive.content
            val type = item.getValue("source_type").jsonPrimitive.content
            val file = directory.resolve(item.getValue("file").jsonPrimitive.content)
            val bytes = Files.readAllBytes(file)
            val header = item.getValue("header").jsonArray.map { it.jsonPrimitive.content }
            val expectedRows = item.getValue("rows").jsonArray.map { row -> header.zip(row.jsonArray.map { it.jsonPrimitive.content }).toMap() }
            val document = CashStatementDocuments.readDocument(bytes, type)
            assertEquals(type, document.sourceType, source)
            assertEquals(expectedRows, document.rows.map { it.payload }, source)
            assertEquals(expectedRows.size, document.observedCount, source)
            val captured = mutableListOf<Pair<Int, Map<String, Any?>>>()
            val parsed = CashStatementRowNormalization.normalizePlatformDocument(document) { position, record ->
                captured += position to record
            }
            assertEquals(expectedRows.size, captured.size, source)
            assertEquals(document.rows.map { it.position }, captured.map { it.first }, source)
            val records = captured.map { it.second }.filter { it["_skipped"] != true }
            for (check in item.getValue("checks").jsonArray) assertEquals(true, evaluate(check, records), "$source $check")
            assertEquals(records.size, parsed.rows.count { it.disposition == "candidate" }, source)
            for ((index, row) in parsed.rows.withIndex()) {
                val native = captured[index].second
                val payload = expectedRows[index]
                assertEquals(emptyList(), row.issueCodes, source)
                if (native["_skipped"] == true) {
                    assertEquals("skipped", row.disposition, source)
                    assertEquals(native["_skip_reason"], row.errorCode, source)
                    assertNull(row.fields, source)
                } else {
                    assertNull(row.errorCode, source)
                    val fields = assertNotNull(row.fields, source)
                    assertEquals(0, assertIs<BigDecimal>(native["amount"]).compareTo(BigDecimal(fields.getValue("amount").toString())), source)
                    assertEquals("CNY", fields["currency"], source)
                    assertEquals(payload, fields["source_payload"], source)
                    assertEquals(payload[if (type == "alipay") "商品说明" else "商品"], fields["note"], source)
                    assertEquals(payload["收/支"], (fields["source_payload"] as Map<*, *>)["收/支"], source)
                    assertNotSame(document.rows[index].payload, native["_source_payload"], source)
                    @Suppress("UNCHECKED_CAST")
                    val raw = native["_source_payload"] as MutableMap<String, String>
                    assertFailsWith<UnsupportedOperationException> { raw["收/支"] = "changed" }
                }
                @Suppress("UNCHECKED_CAST")
                val snapshot = native as MutableMap<String, Any?>
                assertFailsWith<UnsupportedOperationException> { snapshot["category"] = "changed" }
            }
            assertEquals(expectedRows, document.rows.map { it.payload }, source)
            assertContentEquals(bytes, Files.readAllBytes(file), source)
        }
    }

    private fun truth(value: Any?): Boolean = when (value) {
        null -> false
        is String -> value.isNotEmpty()
        is Boolean -> value
        is List<*> -> value.isNotEmpty()
        else -> error("Unexpected original truth value")
    }

    @Suppress("UNCHECKED_CAST")
    private fun evaluate(expression: JsonElement, records: List<Map<String, Any?>>): Any? {
        val node = expression.jsonObject
        fun value(name: String) = evaluate(node.getValue(name), records)
        return when (node.getValue("kind").jsonPrimitive.content) {
            "records" -> records
            "select_category" -> records.first { it["category"] == node.getValue("category").jsonPrimitive.content }
            "item" -> {
                val container = value("value")
                val key = node.getValue("key").jsonPrimitive
                if (key.isString) (container as Map<String, Any?>).getValue(key.content) else (container as List<*>)[key.int]
            }
            "get" -> (value("value") as Map<*, *>)[node.getValue("key").jsonPrimitive.content]
            "decimal" -> BigDecimal(node.getValue("value").jsonPrimitive.content)
            "literal" -> node.getValue("value").jsonPrimitive.content
            "list" -> node.getValue("values").jsonArray.map { evaluate(it, records) }
            "len" -> BigDecimal((value("value") as List<*>).size)
            "str" -> value("value").toString()
            "classify" -> CashRecordClassification.classify(node.getValue("source").jsonPrimitive.content,
                value("value") as Map<String, Any?>).toList()
            "or" -> node.getValue("values").jsonArray.map { evaluate(it, records) }.let { values ->
                values.firstOrNull { truth(it) } ?: values.last()
            }
            "compare" -> {
                val left = value("left"); val right = value("right")
                when (node.getValue("operator").jsonPrimitive.content) {
                    "Eq" -> if (left is BigDecimal && right is BigDecimal) left.compareTo(right) == 0 else left == right
                    "In" -> (left as String) in (right as String)
                    else -> error("Unexpected original comparison")
                }
            }
            else -> error("Unexpected original expression")
        }
    }
}
