package com.finance.tracker.backend

import java.math.BigInteger
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashChoiceRecoveryOriginalTest {
    private class Store(clock: () -> Instant) : ParsedImportStore(clock = clock) {
        private val entries = mutableMapOf<String, ParsedImportObject>()
        var failPublish = false
        var failures = 0
        fun raw(id: String) = entries.getValue(id).content.copyOf()
        override fun readRaw(id: String) = entries[id] ?: throw ParsedImportNotFound()
        override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) {
            if (failPublish) { failures++; throw ParsedImportRevisionConflict() }
            if (entries[id]?.etag != expected) throw ParsedImportRevisionConflict()
            entries[id] = ParsedImportObject(content.copyOf(), digest(content))
        }
        override fun deleteRaw(id: String) { entries.remove(id) }
        override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean) = ParsedImportSweep(0, 0, null)
    }
    private class Fixture(name: String, action: String) {
        val base = CashImportOriginalApiFixture(name, "FT_KOTLIN_CHOICE_RECOVERY_DIR")
        private val upload = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CHOICE_RECOVERY_DIR")), name, "api-input.json").readText()).jsonObject.getValue("upload").jsonObject
        val store = Store { base.now }
        val service = CashImportHttpService(base.database, store) { _, _ -> base.files("extra").single().document!! }
        val commands = CashImportCommandService(base.database, store, now = { base.now })
        val id = store.create(base.workspace, base.user, base.files("default")).sessionId
        fun current() = store.get(id, base.workspace, base.user)
        private fun selections(files: List<ParsedImportFile>) = CashImportFunding.scan(files).groups.map {
            mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to "101", "expected_account_revision" to 1)
        }
        fun preview(session: ParsedImportSession, previous: String? = null, relations: Any? = emptyList<Any>(),
            rows: Any? = emptyList<Any>(), target: CashImportHttpService = service) =
            target.preview(base.token, id, session.revision.toLong(), previous, mapOf("account_selections" to selections(session.files),
                "row_choices" to rows, "relation_choices" to relations))
        val first = preview(current())
        val group = (current().plan!!.getValue("relation_groups") as List<*>).single() as Map<*, *>
        val choice = mapOf("suggestion_id" to group["suggestion_id"], "action" to action)
        val oldPlan = preview(current(), first["plan_id"] as String, listOf(choice))
        val selected = current()
        val recordIds = operations(selected).filter { ((it["row_refs"] as List<*>).first() as String).let { row -> row.startsWith("file_a:") || row.startsWith("file_b:") } }
            .associate { it["occurrence_key"] to (it["values"] as Map<*, *>)["record_id"] }
        fun operations(session: ParsedImportSession) = (session.plan!!.getValue("operations") as List<*>).map { it as Map<*, *> }
        fun upload(ref: String) = CashImportUpload(ref, upload.apiText("filename"), upload.apiText("content").encodeToByteArray(),
            upload["password"]?.jsonPrimitive?.contentOrNull, upload["source_hint"]?.jsonPrimitive?.contentOrNull)
        fun change(action: String, affected: Boolean = false): ParsedImportSession {
            base.now = base.now.plusSeconds(5)
            val current = current(); val revision = current.revision.toLong(); val target = if (affected) "file_a" else "file_c"
            when (action) {
                "add" -> service.addFiles(base.token, id, listOf(upload("added-one")), revision)
                "replace" -> service.replaceFile(base.token, id, target, upload("replacement"), revision)
                "remove" -> service.removeFile(base.token, id, target, revision)
                else -> {
                    service.addFiles(base.token, id, listOf(upload("added-two")), revision)
                    service.removeFile(base.token, id, "file_c", current().revision.toLong())
                }
            }
            return current()
        }
        fun oldReadFails() = assertEquals("plan_changed", assertFailsWith<CashImportPlanError> { service.plan(base.token, oldPlan["plan_id"] as String) }.code)
    }

    @Test fun unaffectedChoiceAndUnidentifiedRecordIdsSurviveFileChanges() {
        for (action in listOf("add", "replace", "remove", "continuous")) for (choice in listOf("defer", "ignore")) {
            val f = Fixture("$action-$choice", choice); val changed = f.change(action)
            assertNull(changed.plan); assertEquals(listOf(f.choice), changed.previewInput["relation_choices"])
            assertEquals(f.selected.expiresAt, changed.expiresAt); f.oldReadFails()
            assertEquals("plan_changed", assertFailsWith<CashImportPlanError> {
                f.commands.commit(f.base.token, f.oldPlan["plan_id"] as String, (f.oldPlan["session_revision"] as Number).toLong(), "old-cannot-save")
            }.code)
            val preview = f.preview(changed, relations = changed.previewInput["relation_choices"], rows = changed.previewInput["row_choices"])
            val latest = f.current(); val group = (latest.plan!!.getValue("relation_groups") as List<*>).single() as Map<*, *>
            assertEquals(choice, group["action"]); assertEquals(f.choice["suggestion_id"], group["suggestion_id"])
            assertEquals(f.recordIds, f.operations(latest).filter { it["occurrence_key"] in f.recordIds }.associate { it["occurrence_key"] to (it["values"] as Map<*, *>)["record_id"] })
            assertFalse("relation_choice_context" in latest.contextData)
            assertEquals(changed.revision + BigInteger.ONE, preview["session_revision"])
            assertFalse("relation_choice_context" in f.service.view(f.base.token, f.id))
        }
    }

    @Test fun removingAMemberInvalidatesItsChoiceWithoutBlockingRemainingRows() {
        val f = Fixture("member", "accept"); val changed = f.change("remove", true)
        val preview = f.preview(changed, relations = listOf(f.choice))
        assertEquals(true, preview["can_commit"])
        assertEquals(listOf(mapOf("kind" to "relation", "reference" to f.choice["suggestion_id"], "code" to "relation_context_changed")), preview["invalidated_choices"])
        assertTrue((f.current().plan!!.getValue("relation_groups") as List<*>).all { (it as Map<*, *>)["action"] != "accept" })
        val receipt = f.commands.commit(f.base.token, preview["plan_id"] as String, (preview["session_revision"] as Number).toLong(), "remaining-can-save")
        assertEquals(JsonPrimitive(true), receipt["committed"]); assertEquals(0, receipt.getValue("summary").jsonObject.apiNumber("relations_confirmed"))
    }

    @Test fun failedRepreviewPreservesSinglePrivateProofAndCurrentInput() {
        for (stage in listOf("build", "publish")) {
            val f = Fixture("failure-$stage", "defer"); val changed = f.change("add"); val original = f.store.raw(f.id)
            assertTrue((changed.contextData["relation_choice_context"] as Map<*, *>).isNotEmpty())
            var builds = 0
            val service = CashImportHttpService(f.base.database, f.store, CashImportPlanBuilder { context, files, input, previous ->
                val result = CashImportBusinessPlanBuilder(context).build(files, input, previous); builds++
                if (stage == "build") error("synthetic build failure")
                result
            })
            f.store.failPublish = stage == "publish"
            try {
                val invoke = { f.preview(changed, relations = changed.previewInput["relation_choices"], target = service) }
                if (stage == "publish") assertEquals("plan_changed", assertFailsWith<CashImportPlanError> { invoke() }.code)
                else assertEquals("synthetic build failure", assertFailsWith<IllegalStateException> { invoke() }.message)
            } finally { f.store.failPublish = false }
            assertEquals(1, builds); assertEquals(if (stage == "publish") 1 else 0, f.store.failures)
            assertContentEquals(original, f.store.raw(f.id))
            val restored = f.current(); assertEquals(changed.revision, restored.revision); assertNull(restored.plan)
            assertEquals(changed.contextData, restored.contextData); assertEquals(changed.previewInput, restored.previewInput)
        }
    }

    @Test fun cancelClearsPrivateChoiceContextWithoutReactivatingOldPlan() {
        val f = Fixture("cancel", "ignore"); val changed = f.change("add")
        assertTrue((changed.contextData["relation_choice_context"] as Map<*, *>).isNotEmpty())
        f.service.cancel(f.base.token, f.id)
        val cancelled = f.current(); assertTrue(cancelled.contextData.isEmpty()); assertTrue(cancelled.previewInput.isEmpty()); assertTrue(cancelled.files.isEmpty())
        assertNull(cancelled.plan); assertTrue(cancelled.cancelled); f.oldReadFails()
    }
}
