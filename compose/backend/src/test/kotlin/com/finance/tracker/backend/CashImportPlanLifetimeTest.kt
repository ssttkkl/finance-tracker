package com.finance.tracker.backend

import java.lang.ref.WeakReference
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportPlanLifetimeTest {
    private fun collected(references: List<WeakReference<*>>, stage: String) {
        assertTrue(references.isNotEmpty(), "$stage must have observed the real objects")
        repeat(20) {
            System.gc()
            if (references.all { it.get() == null }) return
            Thread.sleep(10)
        }
        assertTrue(references.all { it.get() == null }, "$stage still retains an earlier graph")
    }
    private inner class ObservedBuilder(context: CashSqlContext, private val preparationCheck: Boolean) : CashImportBusinessPlanBuilder(context) {
        private val preparation = mutableListOf<WeakReference<*>>()
        private var graph: WeakReference<CashImportPreviewSnapshot>? = null
        var checked = false
        override fun prepareOperations(files: List<ParsedImportFile>, input: Map<String, Any?>,
            recordIds: Map<String, String>, sourcePlan: PreparedCashSourcePlan?): PreparedCashImportOperations =
            super.prepareOperations(files, input, recordIds, sourcePlan).also {
                preparation += listOf(WeakReference(it), WeakReference(it.source), WeakReference(it.scan))
            }
        override fun snapshot(prepared: PreparedCashImportPreview, rows: List<Map<String, Any?>>): CashImportPreviewSnapshot {
            if (preparationCheck) { collected(preparation, "before snapshot"); checked = true }
            return super.snapshot(prepared, rows).also { graph = WeakReference(it) }
        }
        override fun applyEntryChoices(result: MutableMap<String, Any?>, choices: List<Map<String, Any?>>, previous: Map<String, Any?>?) {
            if (!preparationCheck) { collected(listOf(assertNotNull(graph)), "before entry choices"); checked = true }
            super.applyEntryChoices(result, choices, previous)
        }
    }

    private fun verify(preparation: Boolean) {
        val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_ORIGINAL_COMMIT_CONTRACT_DIR"))).resolve("lifetime")
        val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        val input = Json.parseToJsonElement(directory.resolve("input.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val original = ParsedImportCodec().decode(input.getValue("session_id").jsonPrimitive.content,
            input.getValue("session").toString().encodeToByteArray())
        val before = detailDatabaseState(db)
        val store = InMemoryParsedImportStore(clock = { Instant.parse("2026-10-01T09:00:00Z") })
        val created = store.create(original.workspaceId, original.userId, original.files)
        var checked = false
        val service = CashImportHttpService(db, store, CashImportPlanBuilder { context, files, choices, previous ->
            val builder = ObservedBuilder(context, preparation)
            builder.build(files, choices, previous).also { checked = builder.checked }
        })
        @Suppress("UNCHECKED_CAST") val choices = cashSourceValue(input.getValue("input")) as Map<String, Any?>
        val plan = service.preview("original-import-owner", created.sessionId, 1, null, choices)
        assertTrue(checked)
        assertEquals(JsonPrimitive(1), sourceJson(plan["summary"]).jsonObject["create_posted"])
        val saved = store.get(created.sessionId, created.workspaceId, created.userId).plan!!
        for (raw in saved["operations"] as List<*>) {
            val operation = raw as Map<*, *>
            for (field in listOf("manual_fields", "field_diffs", "issues", "expected_accounts", "used_account_selections", "retained_issue_ids", "entry_snapshot")) {
                assertTrue(operation[field] is List<*>, field)
            }
        }
        for (raw in (saved["entry_targets"] as Map<*, *>).values) {
            val target = raw as Map<*, *>
            for (field in listOf("rows", "entries", "relations", "relation_dependencies", "final_edges")) assertTrue(target[field] is List<*>, field)
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun preparationSourceAndFundingWrappersAreReleasedBeforeSnapshot() = verify(true)
    @Test fun snapshotIsReleasedBeforeEntryChoicesAndFrozenCollectionsRemainLists() = verify(false)
}
