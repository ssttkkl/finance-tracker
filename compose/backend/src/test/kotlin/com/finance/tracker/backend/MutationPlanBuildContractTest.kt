package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class MutationPlanBuildContractTest {
    @Test fun saveDraftBuildMatchesPythonViewAndItemDetails() {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_PLAN_BUILD_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val cases = Json.parseToJsonElement(root.resolve("builds.json").readText()).jsonArray
        assertTrue(cases.size >= 5)
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject
            val patch = case.getValue("patch").jsonObject
            val transaction = case.getValue("transaction_id").jsonPrimitive.content
            val request = try { MutationPlanRequest.parse(buildJsonObject {
                put("intent", case.getValue("intent").jsonPrimitive.content)
                put("transaction_id", transaction)
                put("expected_revision", case.getValue("expected_revision").jsonPrimitive.long)
                put("patch", patch)
                case["entry_id"]?.let { put("entry_id", it) }
                case["expected_entry_revision"]?.let { put("expected_entry_revision", it) }
            }) } catch (failure: MutationPlanFailure) { throw AssertionError("parse case $index ${case}", failure) }
            val result = runCatching {
                db.read { connection ->
                    MutationPlanBuilder(CashSqlContext(connection, "plan-a", db.sqlite)).build(transaction.toLong(), request)
                }
            }
            if (!case.getValue("ok").jsonPrimitive.boolean) {
                val failure = result.exceptionOrNull() ?: error("case $index expected failure")
                val code = when (failure) {
                    is CashPolicyError -> failure.code
                    is MutationPlanFailure -> failure.code
                    else -> failure.message
                }
                assertEquals(case.getValue("error").jsonPrimitive.content, code, "case $index $patch")
                continue
            }
            val plan = wrap(result.getOrElse { throw AssertionError("case $index ${case}", it) })
            assertEquals(case.getValue("view"), MutationPlanView.view(plan), "case $index $patch")
            val items = case.getValue("items").jsonObject
            for ((identifier, expected) in items) {
                assertEquals(expected, MutationPlanView.item(plan, identifier), "case $index $identifier")
            }
        }
    }

    private fun wrap(result: JsonObject): JsonObject {
        val items = result.getValue("items").jsonArray
        val counts = buildJsonObject {
            for (status in listOf("confirmed", "pending"))
                put(status, items.count { it.jsonObject.getValue("confirmation_status").jsonPrimitive.content == status })
        }
        return JsonObject(result + mapOf(
            "plan_id" to JsonPrimitive("mp_" + "c".repeat(43) + "." + "t".repeat(16)),
            "edit_context_id" to JsonPrimitive("c".repeat(43)),
            "plan_revision" to JsonPrimitive(1), "context_revision" to JsonPrimitive(1),
            "contract_version" to JsonPrimitive(1),
            "expires_at" to JsonPrimitive("2026-10-29T12:30:00+00:00"), "item_counts" to counts,
        ))
    }
}
