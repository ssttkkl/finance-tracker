package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashPlanFinalizeContractTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_PLAN_FINALIZE_CONTRACT_DIR")))
    private fun database() = Json.parseToJsonElement(root().resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST") private fun strings(value: Any?) = value as Map<String, String>
    private fun cases() = Json.parseToJsonElement(root().resolve("cases.json").readText()).jsonArray.map { it.jsonObject }
    private fun wire(value: Any?): JsonElement = when (value) {
        is JsonElement -> value
        is CashStoredDecimal -> JsonPrimitive(value.text.toBigDecimal().stripTrailingZeros().let { if (it.signum() == 0) "0" else it.toPlainString() })
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to wire(it.value) })
        is Iterable<*> -> JsonArray(value.map(::wire))
        else -> sourceJson(value)
    }
    private fun changes(raw: JsonArray) = raw.map { pair -> pair.jsonArray.let { values ->
        val old = values[0].takeUnless { it == JsonNull }?.let { map(cashSourceValue(it)) }
        val new = values[1].takeUnless { it == JsonNull }?.let { map(cashSourceValue(it)) }
        old to new
    } }

    @Test fun rowResultsKeepPerRowOutcomeAndFrozenInputs() {
        val raw = Json.parseToJsonElement(root().resolve("row-results.json").readText()).jsonObject
        val plan = map(cashSourceValue(raw.getValue("plan")))
        val actual = map(cashSourceValue(raw.getValue("actual"))).mapValues { map(it.value) }
        val original = CashRecordPolicy.copyValue(actual)
        assertEquals(raw.getValue("expected"), wire(CashPlanFinalizer.rowResults(plan, actual)))
        assertEquals(original, actual)
        assertEquals("created_posted", actual.getValue("op")["outcome"])
    }
}
