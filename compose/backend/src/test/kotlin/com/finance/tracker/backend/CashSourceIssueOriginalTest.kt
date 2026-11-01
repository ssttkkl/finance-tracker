package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.nio.file.Files
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashSourceIssueOriginalTest {
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private fun root(readOnly: Boolean = false) = Path.of(requireNotNull(System.getenv(
        if (readOnly) "FT_KOTLIN_SOURCE_ISSUE_GOLDEN_DIR" else "FT_KOTLIN_SOURCE_ISSUE_CONTRACT_DIR")))
    private fun names(method: String? = null) = Json.parseToJsonElement(root().resolve("scenarios.json").readText()).jsonObject
        .filterValues { method == null || it.jsonObject["supplement"] == null && it.jsonObject.getValue("method") == JsonPrimitive("test_$method") }.keys
        .also { assertTrue(it.isNotEmpty(), method) }
    private class Store(at: Instant) : ParsedImportStore(clock = { at }) {
        val entries = linkedMapOf<String, ParsedImportObject>()
        var writes = 0
        override fun readRaw(id: String) = entries[id] ?: throw ParsedImportNotFound()
        override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) {
            if (entries[id]?.etag != expected) throw ParsedImportRevisionConflict()
            entries[id] = ParsedImportObject(content.copyOf(), digest(content)); writes++
        }
        override fun deleteRaw(id: String) { entries.remove(id) }
        override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean) = ParsedImportSweep(0, 0, null)
    }
    private inner class Fixture(val name: String, readOnly: Boolean = false) {
        private val directory = root(readOnly).resolve(name)
        val case = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject
        val metadata = case.getValue("metadata").jsonObject
        val params = metadata.getValue("params").jsonObject
        val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val user = config.getValue("user").jsonPrimitive.content
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val store = Store(at)
        val service = MutationPlanService(database, now = { at }, staging = store)
        val token = "original-import-owner"
        val input get() = case.getValue("input").jsonObject
        val id get() = case.getValue("transaction_id").jsonPrimitive.content
        val choices get() = input.getValue("issue_resolutions").jsonArray
        val selectedId get() = choices.first().jsonObject.getValue("issue_id").jsonPrimitive.long
        fun saveComparison(value: JsonObject) { Files.writeString(directory.resolve("kotlin-plan.json"), value.toString()) }
        fun detail(identifier: String = id) = CashRecordDetailService(database, now = { at }).detail(token, identifier)
        fun fields(identifier: String = id) = detail(identifier).getValue("fields").jsonObject
        fun entry(identifier: String = id) = detail(identifier).getValue("entry").jsonObject
        fun issues() = detail().getValue("issues").jsonArray
        fun plan(value: JsonObject = input) = service.preview(token, value)
        fun frozen(plan: JsonObject) = sourceJson(store.get(MutationPlanStore.contextId(plan.getValue("plan_id").jsonPrimitive.content), workspace, user).plan!!).jsonObject
        fun rawCommit(plan: JsonObject, key: String = "source-confirm", target: MutationPlanService = service) = target.commit(token,
            plan.getValue("plan_id").jsonPrimitive.content, plan.getValue("plan_revision").jsonPrimitive.long, key)
        fun commit(plan: JsonObject, key: String = "source-confirm") = try { rawCommit(plan, key) }
            catch (failure: CashImportCommitFailure) { throw AssertionError(name, failure.failure) }
        fun balances() = database.read { connection -> CashSqlContext(connection, workspace, database.sqlite, now = { at }).snapshot.load().value }
        fun raw() = database.read { connection ->
            val row = connection.jooq(database.sqlite).selectFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.ID.eq(id.toLong())).fetchSingle()
            buildJsonObject {
                put("manual_overrides", row[CASH_TRANSACTIONS.MANUAL_OVERRIDES] ?: JsonNull)
                put("relation_metadata", row[CASH_TRANSACTIONS.RELATION_METADATA] ?: JsonNull)
            }
        }
        fun issue(identifier: Long) = database.read { connection ->
            val row = connection.jooq(database.sqlite).select(CASH_TRANSACTION_ISSUES.REVISION, CASH_TRANSACTION_ISSUES.PENDING_FIELDS,
                CASH_TRANSACTION_ISSUES.PENDING_SOURCE_PAYLOAD, CASH_TRANSACTION_ISSUES.ORIGIN).from(CASH_TRANSACTION_ISSUES)
                .where(CASH_TRANSACTION_ISSUES.ID.eq(identifier)).fetchOne()
            row?.let { JsonArray(listOf(sourceJson(it.value1()), it.value2() ?: JsonNull, it.value3() ?: JsonNull, it.value4() ?: JsonNull)) }
        }
        fun state() = database.read { connection ->
            val tables = DefaultSchema.DEFAULT_SCHEMA.tables; assertEquals(31, tables.size)
            tables.associate { table -> table.name to connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                    buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
                }
            } }
        }
        fun files() = ParsedImportCodec().decode(case.getValue("session_id").jsonPrimitive.content,
            case.getValue("session").toString().encodeToByteArray()).files
        fun importPlan(): JsonObject {
            val staged = store.create(workspace, user, files())
            @Suppress("UNCHECKED_CAST") val value = cashSourceValue(input) as Map<String, Any?>
            return sourceJson(CashImportHttpService(database, store).preview(token, staged.sessionId, 1, null, value)).jsonObject
        }
        fun importCommit(plan: JsonObject, key: String) = CashImportCommandService(database, store, now = { at }).commit(token,
            plan.getValue("plan_id").jsonPrimitive.content, plan.getValue("session_revision").jsonPrimitive.long, key)
        fun resultRows(receipt: JsonObject) = ReceiptReadService(database, now = { at }).results(token,
            receipt.getValue("receipt_id").jsonPrimitive.content, "row", ReceiptPage(0, 50)).getValue("items").jsonArray
    }
    private fun code(failure: RuntimeException) = when (failure) {
        is MutationPlanFailure -> failure.code
        is MutationPlanFieldFailure -> failure.code
        is CashPolicyError -> failure.code
        is ReceiptConflict -> failure.code
        is CashImportPlanError -> failure.code
        else -> throw failure
    }
    private fun summary(receipt: JsonObject, key: String) = receipt.getValue("summary").jsonObject.getValue(key).jsonPrimitive.long
    private fun queryOnly(connection: Connection, observed: () -> Unit): Connection = Proxy.newProxyInstance(
        Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
        if (method.name == "prepareStatement") {
            observed()
            val query = (args!![0] as String).replace(Regex("""'(?:''|[^'])*'|"(?:""|[^"])*"|`(?:``|[^`])*`"""), " ")
            val words = Regex("[A-Za-z_][A-Za-z_0-9]*").findAll(query).map { it.value.lowercase() }.toList()
            assertTrue(words.first() in setOf("select", "with") && words.none {
                it in setOf("insert", "update", "delete", "merge", "create", "drop", "alter", "truncate", "call", "copy")
            } && !Regex("(?i)\\breplace\\s+into\\b").containsMatchIn(query), "Preview attempted a mutation")
        }
        check(method.name != "createStatement" && method.name != "prepareCall") { "Unobserved SQL execution path" }
        try { method.invoke(connection, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
    } as Connection
    private fun reject(f: Fixture, expected: String? = f.case["error"]?.jsonPrimitive?.content, input: JsonObject = f.input) {
        val before = f.state(); val detail = f.detail()
        val failure = assertFailsWith<RuntimeException>(f.name) { f.plan(input) }
        assertEquals(expected, code(failure), f.name); assertEquals(before, f.state()); assertEquals(detail, f.detail())
        assertEquals(0, f.store.writes); assertTrue(f.store.entries.isEmpty())
    }

    @Test fun allOriginalSourcePlansMatchTheCompleteReadOnlyBusinessGraph() {
        for (name in names()) {
            val f = Fixture(name, readOnly = true); val before = f.state()
            when (f.metadata.getValue("kind").jsonPrimitive.content) {
                "detail" -> assertEquals(f.case.getValue("detail").jsonObject.getValue("issues"), f.detail().getValue("issues"), name)
                else -> if (f.metadata["method"] != JsonPrimitive("test_viewer_gets_no_resolution_actions_and_cannot_publish_source_plan")) {
                    f.database.read { connection ->
                        var observed = 0
                        val context = CashSqlContext(queryOnly(connection) { observed++ }, f.workspace, f.database.sqlite, now = { at })
                        fun build(): JsonObject = if (f.metadata["kind"] == JsonPrimitive("import")) {
                            @Suppress("UNCHECKED_CAST") val input = cashSourceValue(f.input) as Map<String, Any?>
                            sourceJson(CashImportBusinessPlanBuilder(context).build(f.files(), input, null)).jsonObject
                        } else {
                            val request = MutationPlanRequest.parse(f.input)
                            MutationPlanBuilder(context).build(request.transactionId.toLong(), request)
                        }
                        if (f.case["error"] != null) assertEquals(f.case.getValue("error").jsonPrimitive.content,
                            code(assertFailsWith<RuntimeException>(name) { build() }), name)
                        else {
                            val actual = build(); f.saveComparison(actual)
                            assertEquals(f.case.getValue("expected"), actual, name)
                        }
                        assertTrue(observed > 0, "No preview SQL observed: $name")
                    }
                }
            }
            assertEquals(before, f.state(), name)
        }
    }

    @Test fun newImportAliasKeepsEveryExistingPendingBodyRevisionAndCandidate() {
        for (name in names("import_keep_existing_with_new_alias_preserves_all_prior_pending_content")) {
            val f = Fixture(name); val initial = f.state(); val before = f.detail()
            val receipt = f.importCommit(f.importPlan(), "keep-new-input-preserve-prior-pending")
            assertEquals(JsonPrimitive(f.id), f.resultRows(receipt).single().jsonObject["transaction_id"])
            val after = f.state(); assertEquals(initial["cash_transaction_issues"], after["cash_transaction_issues"])
            assertEquals(initial["cash_issue_candidates"], after["cash_issue_candidates"]); assertEquals(before["row_revision"], f.detail()["row_revision"])
            f.database.read { connection ->
                val context = CashSqlContext(connection, f.workspace, f.database.sqlite, now = { at })
                val row = CashSourceRows.sourceBatchRows(f.files()).rows.values.single()
                val matches = CashSourceReadRepository(context).forKeys(row.bindingSpecs, listOfNotNull(row.stableEvidenceBindingKey))
                assertTrue(matches.isNotEmpty()); assertEquals(setOf(f.id.toLong()), matches.map { it.transactionId }.toSet())
            }
        }
    }
    @Test fun adoptionStaysReadOnlyUntilCommitAndKeepsPublicManualNoteAndTrustedOwner() {
        val captures = mutableListOf<JsonObject>()
        for (name in names("source_adoption_is_read_only_until_commit_and_preserves_manual_note")) testApplication {
            val f = Fixture(name); val before = f.detail(); val initial = f.state()
            application { financeModule(f.database, mutationPlans = f.service) }
            suspend fun current(): JsonObject {
                val response = client.get("/api/v1/cash-records/${f.id}") { header(HttpHeaders.Authorization, "Bearer ${f.token}") }
                val body = response.bodyAsText(); assertEquals(200, response.status.value, body)
                return Json.parseToJsonElement(body).jsonObject
            }
            val publicBefore = current(); assertEquals(before, publicBefore)
            val preview = client.post("/api/v1/cash-mutations/plans") {
                header(HttpHeaders.Authorization, "Bearer ${f.token}"); contentType(ContentType.Application.Json); setBody(f.input.toString())
            }
            val previewBody = preview.bodyAsText(); assertEquals(200, preview.status.value, previewBody)
            val plan = Json.parseToJsonElement(previewBody).jsonObject
            assertEquals(1, plan.getValue("summary").jsonObject.getValue("resolve_issue").jsonPrimitive.int); assertEquals(JsonPrimitive(true), plan["can_commit"])
            val item = plan.getValue("items").jsonArray.single().jsonObject.getValue("preview_item_id").jsonPrimitive.content
            val itemResponse = client.get("/api/v1/cash-mutations/plans/${plan.getValue("plan_id").jsonPrimitive.content}/items/$item") {
                header(HttpHeaders.Authorization, "Bearer ${f.token}")
            }
            val itemBody = itemResponse.bodyAsText(); assertEquals(200, itemResponse.status.value, itemBody)
            val detail = Json.parseToJsonElement(itemBody).jsonObject
            assertEquals(f.params["expected"], detail.getValue("entry").jsonObject.getValue("fields").jsonObject["note"])
            val member = detail.getValue("members").jsonArray.single { it.jsonObject["transaction_id"] == JsonPrimitive(f.id) }.jsonObject
            assertEquals(JsonPrimitive("来源新原文"), member.getValue("fields").jsonObject["note"])
            assertEquals(before, f.detail()); assertEquals(initial, f.state())
            val committed = client.post("/api/v1/cash-mutations/plans/${plan.getValue("plan_id").jsonPrimitive.content}/commit") {
                header(HttpHeaders.Authorization, "Bearer ${f.token}"); header("Idempotency-Key", "source-confirm"); contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("expected_plan_revision", plan.getValue("plan_revision")) }.toString())
            }
            val receiptBody = committed.bodyAsText(); assertEquals(200, committed.status.value, receiptBody)
            val receipt = Json.parseToJsonElement(receiptBody).jsonObject
            assertEquals(JsonPrimitive("completed"), receipt["status"]); assertEquals(1, summary(receipt, "resolved_issues"))
            assertEquals(f.params["expected"], f.entry().getValue("fields").jsonObject["note"])
            assertEquals(JsonPrimitive("来源新对方"), f.fields()["counterparty"]); assertEquals(JsonPrimitive("来源新原文"), f.fields()["note"])
            assertEquals(JsonArray(listOf(JsonPrimitive("source-attribute"))), f.fields()["counterparty_account_attrs"]); assertTrue(f.issues().isEmpty())
            assertEquals(f.params["protected"] != JsonNull || f.params["explicit"] != JsonNull, JsonPrimitive("note") in f.entry().getValue("manual_fields").jsonArray)
            val raw = f.raw(); val overrides = raw.getValue("manual_overrides").jsonObject
            assertFalse(overrides["note"]?.jsonPrimitive?.booleanOrNull ?: false); assertFalse(overrides["category_id"]?.jsonPrimitive?.booleanOrNull ?: false)
            val metadata = raw.getValue("relation_metadata").jsonObject
            assertEquals(JsonPrimitive("trusted-current-owner"), metadata["_source_account_identifier"]); assertFalse("_refund_signal" in metadata)
            assertEquals(receipt, f.commit(plan))
            captures += buildJsonObject {
                put("request", f.input); put("before", publicBefore); put("plan", plan); put("item", detail)
                put("receipt", receipt); put("after", current()); put("expected_note", f.params.getValue("expected")); put("key", "source-confirm")
            }
        }
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { output ->
            val path = Path.of(output); Files.createDirectories(path)
            Files.writeString(path.resolve("source-issue-response.json"), JsonArray(captures).toString())
        }
    }
    @Test fun keepingExistingClearsOnlyTheChosenInstanceWithoutChangingParentOrBalance() {
        val f = Fixture(names("keep_existing_clears_only_selected_instance_without_faking_parent_update").single())
        val before = f.detail(); val balance = f.balances(); val second = f.issues().single { it.jsonObject["issue_id"] != JsonPrimitive(f.selectedId.toString()) }.jsonObject
        val secondId = second.getValue("issue_id").jsonPrimitive.long; val retained = f.issue(secondId)
        val receipt = f.commit(f.plan()); val after = f.detail()
        assertEquals(JsonPrimitive("completed"), receipt["status"]); assertEquals(1, summary(receipt, "resolved_issues"))
        assertEquals(0, summary(receipt, "updated_posted")); assertEquals(1, summary(receipt, "duplicate"))
        assertEquals(before["row_revision"], after["row_revision"]); assertEquals(before["fields"], after["fields"])
        assertEquals(listOf(JsonPrimitive(secondId.toString())), f.issues().map { it.jsonObject["issue_id"] })
        assertEquals(retained, f.issue(secondId)); assertNull(f.issue(f.selectedId)); assertEquals(JsonPrimitive(1), receipt["needs_attention_count"])
        assertEquals(balance, f.balances())
        val input = JsonObject(f.input + ("issue_resolutions" to JsonArray(listOf(buildJsonObject {
            put("issue_id", secondId.toString()); put("expected_issue_revision", second.getValue("revision")); put("resolution", "keep_existing")
        }))))
        val last = f.commit(f.plan(input), "keep-second"); assertEquals(JsonPrimitive("completed"), last["status"])
        assertEquals(JsonPrimitive(0), last["needs_attention_count"]); assertEquals(before["row_revision"], f.detail()["row_revision"])
    }
    @Test fun resolutionAndCompletionShareOneDraftPlanAndPreserveIdentity() {
        for (name in names("source_resolution_and_completion_share_one_draft_plan")) {
            val f = Fixture(name); val before = f.detail(); val finalize = f.params.getValue("finalize").jsonPrimitive.boolean
            val receipt = f.commit(f.plan()); assertEquals(before["transaction_id"], f.detail()["transaction_id"])
            assertEquals(JsonPrimitive(if (finalize) "posted" else "draft"), f.detail()["state"]); assertTrue(f.issues().isEmpty())
            assertEquals(1, summary(receipt, "resolved_issues")); assertEquals(if (finalize) 1L else 0L, summary(receipt, "finalized_draft"))
        }
    }
    @Test fun anUnselectedIdentityProblemStillBlocksFinalization() {
        for (name in names("unselected_identity_problem_still_blocks_same_plan_finalization")) reject(Fixture(name), "source_identity_ambiguous")
    }
    @Test fun sourceTypeConflictCannotBeHiddenByAResetAndNeverRemovesTheGroup() {
        for (name in names("source_type_conflict_cannot_be_hidden_by_a_reset_patch")) {
            val f = Fixture(name); val relations = f.state()["transaction_relations"]; reject(f, "fixed_field_conflict")
            assertEquals(relations, f.state()["transaction_relations"])
        }
    }
    @Test fun fixedSourceConflictCannotBeHiddenByAnExplicitFinalPatch() {
        for (name in names("source_fixed_conflict_cannot_be_hidden_by_explicit_patch")) {
            val f = Fixture(name); assertEquals(JsonArray(listOf(JsonPrimitive("keep_existing"))), f.issues().single().jsonObject["allowed_resolutions"])
            reject(f, "fixed_field_conflict")
        }
    }
    @Test fun missingBodyIsNotClearAndExplicitNullCanBeAdopted() {
        for (name in names("missing_legacy_body_is_not_clear_but_explicit_null_can_be_adopted")) {
            val f = Fixture(name); val present = f.params["present"] == JsonPrimitive(true); val issue = f.issues().single().jsonObject
            assertEquals(if (present) JsonPrimitive(true) else JsonNull, issue["pending_source_payload_present"])
            assertEquals(JsonArray((if (present) listOf("apply_source", "keep_existing") else listOf("keep_existing")).map(::JsonPrimitive)), issue["allowed_resolutions"])
            if (present) {
                f.commit(f.plan()); assertEquals(JsonNull, f.fields()["source_payload"]); assertEquals(JsonPrimitive(""), f.fields()["note"])
            } else {
                reject(f)
                val choices = JsonArray(f.choices.map { JsonObject(it.jsonObject + ("resolution" to JsonPrimitive("keep_existing"))) })
                assertEquals(JsonPrimitive("completed"), f.commit(f.plan(JsonObject(f.input + ("issue_resolutions" to choices))))["status"])
            }
        }
    }
    @Test fun deferredImportRetainsExplicitSourceClearPresence() {
        for (name in names("imported_pending_update_preserves_explicit_source_clear_presence")) {
            val f = Fixture(name); val receipt = f.importCommit(f.importPlan(), "defer-source-null")
            assertEquals(JsonPrimitive(f.id), f.resultRows(receipt).single().jsonObject["transaction_id"])
            val issue = f.issues().single { it.jsonObject["code"] == JsonPrimitive("source_update_pending") }.jsonObject
            assertEquals(JsonNull, issue["pending_source_payload"]); assertEquals(JsonPrimitive(true), issue["pending_source_payload_present"])
            f.database.read { connection ->
                val row = connection.jooq(f.database.sqlite).select(CASH_TRANSACTION_ISSUES.PENDING_FIELDS, CASH_TRANSACTION_ISSUES.PENDING_SOURCE_PAYLOAD)
                    .from(CASH_TRANSACTION_ISSUES).where(CASH_TRANSACTION_ISSUES.TRANSACTION_ID.eq(f.id.toLong()))
                    .and(CASH_TRANSACTION_ISSUES.CODE.eq("source_update_pending")).fetchSingle()
                assertEquals(JsonPrimitive(true), row.value1()!!.jsonObject["_source_payload_present"]); assertEquals(JsonNull, row.value2() ?: JsonNull)
            }
        }
    }
    @Test fun issueRevisionOrBodyChangeRejectsFrozenPlanEvenWithTheSameParentRevision() {
        for (name in names("source_issue_changes_invalidate_frozen_plan_even_without_parent_change")) {
            val f = Fixture(name); val plan = f.plan(); val revision = f.detail()["row_revision"]
            val version = f.params["change"] == JsonPrimitive("revision")
            f.database.write { connection ->
                val context = CashSqlContext(connection, f.workspace, f.database.sqlite, now = { at })
                if (version) CashSourceWriteRepository(context).saveIssue(f.id, "pending:source", "source_update_pending",
                    pendingFields = mapOf("counterparty" to "后来待采用"))
                else context.sql.update(CASH_TRANSACTION_ISSUES).set(CASH_TRANSACTION_ISSUES.PENDING_FIELDS,
                    buildJsonObject { put("counterparty", "直接变化也不能偷换") }).where(CASH_TRANSACTION_ISSUES.ID.eq(f.selectedId)).execute()
            }
            val current = f.detail(); val before = f.state(); assertEquals(revision, current["row_revision"])
            val failure = assertFailsWith<RuntimeException> { f.rawCommit(plan) }
            assertEquals(if (version) "issue_changed" else "record_changed", code(failure))
            assertEquals(current, f.detail()); assertEquals(before, f.state())
            assertEquals(plan, f.service.plan(f.token, plan.getValue("plan_id").jsonPrimitive.content))
        }
    }
    private fun publicationFailure(method: String, finalization: Boolean = false) {
        for (name in names(method)) {
            val f = Fixture(name); val before = f.detail(); val balance = f.balances(); val saved = f.issue(f.selectedId)
            val plan = f.plan(); val state = f.state(); var attempts = 0
            val stage = if (finalization || f.params["failure"] == JsonPrimitive("projection")) "projection" else "record"
            val target = MutationPlanService(f.database, now = { at }, staging = f.store, afterCommitStep = {
                if (it == stage) { attempts++; error("synthetic private failure after clearing issue") }
            })
            val failure = assertFailsWith<CashImportCommitFailure> { f.rawCommit(plan, target = target) }
            assertEquals("not_committed", failure.commitState); assertEquals(1, attempts)
            assertEquals(state, f.state()); assertEquals(before, f.detail()); assertEquals(saved, f.issue(f.selectedId)); assertEquals(balance, f.balances())
            assertEquals(plan, f.service.plan(f.token, plan.getValue("plan_id").jsonPrimitive.content))
        }
    }
    @Test fun rowOrProjectionFailureRestoresIssueBodyAndTheOriginalPlan() =
        publicationFailure("issue_body_is_restored_after_record_unit_or_publication_failure")
    @Test fun conflictingSourcesRequireAnExplicitFinalValueAndThenResolveBoth() {
        for (name in names("conflicting_source_choices_require_an_explicit_final_value")) {
            val f = Fixture(name)
            if (f.params["explicit"] == JsonPrimitive(false)) reject(f, "source_resolution_conflict")
            else {
                val receipt = f.commit(f.plan()); assertEquals(2, summary(receipt, "resolved_issues"))
                assertEquals(JsonPrimitive("本次明确统一"), f.entry().getValue("fields").jsonObject["counterparty"])
            }
        }
    }
    @Test fun sourceCategoryNeverCreatesManualProtectionOrOverridesAManualClear() {
        for (name in names("source_category_does_not_create_manual_protection_or_override_manual_clear")) {
            val f = Fixture(name); val protected = f.params.getValue("protected").jsonPrimitive.boolean
            val sourceCategory = f.issue(f.selectedId)!![1].jsonObject.getValue("category_id")
            val receipt = f.commit(f.plan()); assertEquals(sourceCategory, f.fields()["category_id"])
            assertFalse(f.raw().getValue("manual_overrides").jsonObject["category_id"]?.jsonPrimitive?.booleanOrNull ?: false)
            assertEquals(if (protected) JsonNull else sourceCategory, f.entry().getValue("fields").jsonObject["category_id"])
            assertEquals(protected, JsonPrimitive("category_id") in f.entry().getValue("manual_fields").jsonArray)
            assertEquals(JsonPrimitive("completed"), receipt["status"]); assertEquals(1, summary(receipt, "resolved_issues"))
        }
    }
    @Test fun issueCountSurvivesWholeRecordCategoryEditingWithoutChangingSourceCategories() {
        for (name in names("issue_resolution_count_survives_whole_record_category_members")) {
            val f = Fixture(name); val category = f.input.getValue("patch").jsonObject.getValue("category_id"); val plan = f.plan()
            assertEquals(JsonPrimitive(1), plan.getValue("summary").jsonObject["resolve_issue"])
            assertEquals(JsonPrimitive(1), plan.getValue("summary").jsonObject["update_posted"])
            val receipt = f.commit(plan); assertEquals(1, summary(receipt, "resolved_issues"))
            assertEquals(category, f.entry("1003").getValue("fields").jsonObject["category_id"])
            assertTrue(JsonPrimitive("category_id") in f.entry("1003").getValue("manual_fields").jsonArray)
            for (identifier in listOf("1001", "1003")) {
                assertNotEquals(category, f.fields(identifier)["category_id"])
                f.database.read { connection ->
                    val overrides = connection.jooq(f.database.sqlite).select(CASH_TRANSACTIONS.MANUAL_OVERRIDES).from(CASH_TRANSACTIONS)
                        .where(CASH_TRANSACTIONS.ID.eq(identifier.toLong())).fetchSingle().value1()!!.jsonObject
                    assertFalse(overrides["category_id"]?.jsonPrimitive?.booleanOrNull ?: false)
                }
            }
        }
    }
    @Test fun legacyNonemptyBodyWithoutPresenceMetadataIsAdoptable() {
        for (name in names("legacy_nonempty_body_is_adoptable_without_new_presence_metadata")) {
            val f = Fixture(name); val issue = f.issues().single().jsonObject
            assertEquals(JsonArray(listOf("apply_source", "keep_existing").map(::JsonPrimitive)), issue["allowed_resolutions"])
            assertEquals(JsonPrimitive(true), issue["pending_source_payload_present"])
            f.commit(f.plan()); assertEquals(JsonPrimitive("旧事项仍有原文"), f.fields()["note"])
        }
    }
    @Test fun validFieldOnlyUpdateExplicitlyHasNoBodyUpdate() {
        for (name in names("valid_field_only_update_explicitly_has_no_body_update")) {
            val f = Fixture(name); val before = f.state(); val issue = f.issues().single().jsonObject
            assertEquals(JsonPrimitive(false), issue["pending_source_payload_present"])
            assertEquals(JsonNull, issue["pending_source_payload"]); assertEquals(before, f.state())
        }
    }
    @Test fun inlineLegacyBodyHasTheSamePresenceAndContentAsAdoption() {
        for (name in names("inline_legacy_body_has_the_same_presence_and_content_as_adoption")) {
            val f = Fixture(name); val before = f.state(); val issue = f.issues().single().jsonObject
            assertEquals(JsonPrimitive(true), issue["pending_source_payload_present"])
            assertEquals(f.params["body"], issue["pending_source_payload"]); assertEquals(before, f.state())
        }
    }
    @Test fun contradictoryPresenceIsUnknownAndCannotBeAdopted() {
        for (name in names("contradictory_body_presence_is_unknown_and_not_adoptable")) {
            val f = Fixture(name); val before = f.state(); val issue = f.issues().single().jsonObject
            assertEquals(JsonNull, issue["pending_source_payload_present"])
            assertEquals(JsonArray(listOf(JsonPrimitive("keep_existing"))), issue["allowed_resolutions"]); assertEquals(before, f.state())
        }
    }
    @Test fun onlyCurrentApplicableOwnedIssuesCanBeResolved() {
        for (name in names("only_applicable_current_owned_issue_can_be_resolved")) reject(Fixture(name))
    }
    @Test fun viewerHasNoResolutionActionsAndCannotPublishASourcePlan() {
        for (name in names("viewer_gets_no_resolution_actions_and_cannot_publish_source_plan")) {
            val f = Fixture(name); val before = f.state()
            assertEquals(JsonArray(emptyList()), f.issues().single().jsonObject["allowed_resolutions"])
            assertTrue(assertFailsWith<AccessFailure> { f.plan() }.forbidden)
            assertEquals(before, f.state()); assertEquals(0, f.store.writes); assertTrue(f.store.entries.isEmpty())
        }
    }
    @Test fun finalizationPublicationFailureRestoresBodyDraftBalancesAndPlan() =
        publicationFailure("finalization_publication_failure_restores_source_body_draft_and_balances", finalization = true)
    @Test fun invalidSourceComponentsCannotBeHiddenByAValidPatch() {
        for (name in names("source_component_structure_cannot_be_hidden_by_a_valid_patch")) {
            val f = Fixture(name); val selected = f.issues().single { it.jsonObject["issue_id"] == JsonPrimitive(f.selectedId.toString()) }.jsonObject
            assertEquals(JsonArray(listOf(JsonPrimitive("keep_existing"))), selected["allowed_resolutions"])
            reject(f)
        }
    }
    @Test fun legacyProtectedSourceBodyRemainsVisibleAsProtectedAndIsNotOverwritten() {
        val f = Fixture("legacy_protected_source_body"); val before = f.fields(); val plan = f.plan()
        val frozen = f.frozen(plan); val operation = frozen.getValue("operations").jsonArray.single().jsonObject
        val diff = operation.getValue("field_diffs").jsonArray.single { it.jsonObject["field"] == JsonPrimitive("source_payload") }.jsonObject
        assertEquals(JsonPrimitive("protected"), diff["resolution"]); assertEquals(before["source_payload"], diff["current"])
        assertEquals(before["source_payload"], diff["effective"]); assertNotEquals(diff["effective"], diff["incoming"])
        assertEquals(f.case.getValue("expected"), JsonObject(frozen.filterKeys { it in f.case.getValue("expected").jsonObject.keys }))
        val receipt = f.commit(plan); assertEquals(1, summary(receipt, "resolved_issues"))
        assertEquals(before["source_payload"], f.fields()["source_payload"]); assertEquals(before["note"], f.fields()["note"])
        assertEquals(JsonPrimitive(true), f.raw().getValue("manual_overrides").jsonObject["source_payload"])
    }
    @Test fun selectedIssueIdentifiersKeepTheOriginalLexicalOrderAndClearBothInstances() {
        val f = Fixture("legacy_issue_id_order"); val before = f.detail(); val balance = f.balances(); val plan = f.plan()
        val frozen = f.frozen(plan); val operation = frozen.getValue("operations").jsonArray.single().jsonObject
        assertEquals(JsonArray(listOf("10", "2").map(::JsonPrimitive)), operation["resolved_issue_ids"])
        assertEquals(f.case.getValue("expected"), JsonObject(frozen.filterKeys { it in f.case.getValue("expected").jsonObject.keys }))
        val receipt = f.commit(plan); assertEquals(2, summary(receipt, "resolved_issues")); assertTrue(f.issues().isEmpty())
        assertEquals(0, summary(receipt, "updated_posted")); assertEquals(before["row_revision"], f.detail()["row_revision"])
        assertEquals(balance, f.balances())
    }
}
