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

class CashDraftMergeOriginalTest {
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private fun root(readOnly: Boolean = false) = Path.of(requireNotNull(System.getenv(
        if (readOnly) "FT_KOTLIN_DRAFT_MERGE_GOLDEN_DIR" else "FT_KOTLIN_DRAFT_MERGE_CONTRACT_DIR")))
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
        fun instrument(database: Database = this.database, step: (String) -> Unit) = MutationPlanService(database,
            now = { at }, staging = store, afterCommitStep = step)
        val token = "original-import-owner"
        val input get() = case.getValue("input").jsonObject
        val id get() = case.getValue("transaction_id").jsonPrimitive.content
        val target get() = case.getValue("target_id").jsonPrimitive.content
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
            val tables = DefaultSchema.DEFAULT_SCHEMA.tables; assertEquals(32, tables.size)
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
        fun bindings() = database.read { connection ->
            val table = CASH_SOURCE_BINDINGS
            connection.jooq(database.sqlite).selectFrom(table).where(table.WORKSPACE_ID.eq(workspace))
                .and(table.TRANSACTION_ID.eq(id.toLong())).orderBy(table.ID).fetch().map { sourceJson(it.intoMap()) }
        }
        fun rows(table: org.jooq.Table<*>, condition: org.jooq.Condition = org.jooq.impl.DSL.trueCondition()) = database.read { connection ->
            sourceJson(cashMergeWire(CashDraftMergeFacts(CashSqlContext(connection, workspace, database.sqlite, now = { at })).rows(table, condition))).jsonArray
        }
        fun observedDatabase(written: (String) -> Unit) = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")) { connection ->
            Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                try {
                    val result = method.invoke(connection, *(args ?: emptyArray()))
                    if (method.name == "prepareStatement") {
                        val query = (args!![0] as String).lowercase().replace("\"", "").replace("`", "")
                        Proxy.newProxyInstance(java.sql.PreparedStatement::class.java.classLoader, arrayOf(java.sql.PreparedStatement::class.java)) { _, operation, values ->
                            try {
                                val output = operation.invoke(result, *(values ?: emptyArray()))
                                if (operation.name.startsWith("execute") && query.trimStart().startsWithAny("insert", "update", "delete")) written(query)
                                output
                            } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
                        }
                    } else result
                } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
            } as Connection
        }
        fun addSourceBinding() = database.write { connection ->
            val data = Json.parseToJsonElement(directory.resolve("input.json").readText()).jsonObject
            val files = ParsedImportCodec().decode(data.getValue("session_id").jsonPrimitive.content, data.getValue("session").toString().encodeToByteArray()).files
            CashSourceWriteRepository(CashSqlContext(connection, workspace, database.sqlite, now = { at })).saveOccurrence(id,
                CashSourceRows.sourceBatchRows(files).rows.values.single().copy(fileDigest = "c".repeat(64)), uncertain = true)
        }
        fun reimport(): JsonObject {
            val session = case.getValue("reimport_session").jsonObject
            val files = ParsedImportCodec().decode(case.getValue("reimport_session_id").jsonPrimitive.content, session.toString().encodeToByteArray()).files
            val staged = store.create(workspace, user, files)
            @Suppress("UNCHECKED_CAST") val request = cashSourceValue(session.getValue("preview_input")) as Map<String, Any?>
            val plan = sourceJson(CashImportHttpService(database, store).preview(token, staged.sessionId, 1, null, request)).jsonObject
            return importCommit(plan, "identity-reimport")
        }
    }
    private fun code(failure: RuntimeException) = when (failure) {
        is MutationPlanFailure -> failure.code
        is MutationPlanFieldFailure -> failure.code
        is CashPolicyError -> failure.code
        is ReceiptConflict -> failure.code
        is CashImportPlanError -> failure.code
        is CashSourceBindingConflict -> failure.code
        is CashRelationFailure -> failure.code
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
        val fields = when (failure) { is CashPolicyError -> failure.fields; is MutationPlanFieldFailure -> failure.fields; else -> emptyList() }
        assertEquals(f.case["error_fields"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), fields, f.name)
        assertEquals(0, f.store.writes); assertTrue(f.store.entries.isEmpty())
    }

    private fun String.startsWithAny(vararg values: String) = values.any { startsWith(it) }
    private fun readOnlyPlan(f: Fixture): JsonObject { val before = f.state(); val plan = f.plan(); assertEquals(before, f.state(), f.name); return plan }
    private fun operation(f: Fixture, plan: JsonObject) = f.frozen(plan).getValue("operations").jsonArray.single().jsonObject
    private fun merge(f: Fixture, plan: JsonObject) = operation(f, plan).getValue("merge_draft").jsonObject
    private fun members(f: Fixture, plan: JsonObject) = plan.getValue("items").jsonArray.flatMap { item ->
        f.service.item(f.token, plan.getValue("plan_id").jsonPrimitive.content,
            item.jsonObject.getValue("preview_item_id").jsonPrimitive.content).getValue("members").jsonArray
    }.map { it.jsonObject }.associateBy { it.getValue("transaction_id").jsonPrimitive.content }

    @Test fun mergePreviewKeepsOnlyTargetStateActionsAndOriginalPlanWithoutWrites() {
        for (name in names("merge_preview_keeps_only_target_without_writing_current_state")) {
            val f = Fixture(name, readOnly = true); val before = f.state()
            val plan = readOnlyPlan(f); val draft = f.params.getValue("draft_target").jsonPrimitive.boolean
            assertEquals(JsonPrimitive(1), plan.getValue("summary").jsonObject["merge_draft"], name)
            val item = plan.getValue("items").jsonArray.single().jsonObject
            assertEquals(buildJsonArray { add(buildJsonObject { put("transaction_id", f.target) }) }, item["member_refs"], name)
            assertEquals(JsonPrimitive(if (draft) "draft" else "posted"), item["anticipated_state"], name)
            val current = members(f, plan); assertEquals(setOf(f.target), current.keys, name)
            assertEquals(JsonArray(listOf(JsonPrimitive(if (draft) "save_draft" else "edit_record"))), current.getValue(f.target)["allowed_actions"], name)
            assertEquals(plan, f.service.plan(f.token, plan.getValue("plan_id").jsonPrimitive.content), name)
            assertEquals(before, f.state(), name)
        }
    }
    @Test fun mergePreviewRejectsOriginalFinancialConflictBeforeExplicitPatch() {
        for (name in names("original_financial_conflict_is_rejected_before_patch")) {
            val f = Fixture(name, readOnly = true); reject(f, "fixed_field_conflict")
            assertTrue("amount" in f.case.getValue("error_fields").jsonArray.map { it.jsonPrimitive.content }, name)
        }
    }
    @Test fun mergePreviewUnknownSourceFinancesKeepEveryKnownTargetField() {
        for (name in names("unknown_source_finances_do_not_clear_known_target")) {
            val f = Fixture(name, readOnly = true); val before = f.state(); val target = f.fields(f.target)
            val plan = readOnlyPlan(f); val fields = members(f, plan).getValue(f.target).getValue("fields").jsonObject
            for (field in listOf("amount", "currency", "occurred_at", "account_id", "components")) assertEquals(target[field], fields[field], "$name $field")
            assertEquals(before, f.state(), name)
        }
    }
    @Test fun mergePreviewHumanNoteConflictRequiresExplicitClear() {
        for (name in names("both_human_notes_including_cleared_source_need_explicit_value")) {
            val f = Fixture(name, readOnly = true)
            if (f.params.getValue("explicit").jsonPrimitive.boolean) {
                val plan = readOnlyPlan(f)
                assertEquals(JsonPrimitive(""), plan.getValue("items").jsonArray.single().jsonObject.getValue("display").jsonObject["note"], name)
            } else {
                reject(f, "source_resolution_conflict")
                assertTrue("note" in f.case.getValue("error_fields").jsonArray.map { it.jsonPrimitive.content }, name)
            }
        }
    }
    @Test fun mergePreviewChangedTargetRejectsAndKeepsContextUnpublished() {
        for (name in names("target_change_rejects_merge_preview_and_keeps_original_context")) reject(Fixture(name, readOnly = true), "record_changed")
    }
    @Test fun mergePreviewUnknownFundingPlaceholderKeepsKnownTargetSplit() {
        for (name in names("unknown_funding_placeholder_does_not_contradict_known_split")) {
            val f = Fixture(name, readOnly = true); val before = f.state(); val target = f.fields(f.target)
            assertEquals(2, target.getValue("components").jsonArray.size, name)
            val fields = members(f, readOnlyPlan(f)).getValue(f.target).getValue("fields").jsonObject
            assertEquals(target["components"], fields["components"], name); assertEquals(before, f.state(), name)
        }
    }
    @Test fun mergePreviewIdentityCandidatesUseCurrentValuesAndKeepStoredProof() {
        for (name in names("identity_candidates_show_current_data_without_updating_stored_proof")) {
            val f = Fixture(name); val before = f.rows(CASH_ISSUE_CANDIDATES)
            fun candidates() = f.issues().single { it.jsonObject["code"] == JsonPrimitive("source_identity_ambiguous") }.jsonObject.getValue("candidates").jsonArray
            val candidate = candidates().single().jsonObject
            assertEquals(JsonPrimitive(f.target), candidate["transaction_id"], name)
            assertEquals(f.case.getValue("locals").jsonObject.getValue("expected_revision"), candidate["row_revision"], name)
            assertEquals(JsonPrimitive("posted"), candidate["state"], name)
            assertEquals(JsonPrimitive("当前候选备注"), candidate.getValue("display").jsonObject["note"], name)
            assertEquals(before, f.rows(CASH_ISSUE_CANDIDATES), name)
            f.database.write { connection ->
                val table = CASH_TRANSACTIONS
                assertEquals(1, connection.jooq(f.database.sqlite).update(table).set(table.DELETED_AT, at)
                    .set(table.ROW_REVISION, table.ROW_REVISION.plus(1L)).where(table.ID.eq(f.target.toLong())).execute())
            }
            assertTrue(candidates().isEmpty(), name); assertEquals(before, f.rows(CASH_ISSUE_CANDIDATES), name)
        }
    }
    @Test fun mergePreviewFreezesReverseCandidatesAndCompleteJointGroup() {
        for (name in names("merge_freezes_reverse_candidates_and_full_joint_groups")) {
            val f = Fixture(name); val before = f.state(); val frozen = merge(f, f.plan())
            val references = frozen.getValue("references").jsonObject
            val group = f.rows(TRANSACTION_RELATIONS, TRANSACTION_RELATIONS.DECISION_GROUP_ID.eq("synthetic:joint"))
            assertEquals(2, group.size, name)
            assertTrue(references.getValue("relations").jsonArray.map { it.jsonObject["id"] }.toSet()
                .containsAll(group.map { it.jsonObject["id"] }), name)
            val candidate = f.case.getValue("locals").jsonObject.getValue("candidate_id")
            assertTrue(candidate in references.getValue("relation_candidates").jsonArray.map { it.jsonObject["id"] }, name)
            assertTrue(JsonPrimitive("synthetic:joint") in frozen.getValue("relations").jsonArray.map { it.jsonObject["decision_group_id"] }, name)
            assertEquals(before, f.state(), name)
            f.database.write { connection ->
                assertEquals(1, connection.jooq(f.database.sqlite).update(CASH_RELATION_CANDIDATES)
                    .set(CASH_RELATION_CANDIDATES.EVIDENCE, buildJsonObject { put("proof", "changed") })
                    .where(CASH_RELATION_CANDIDATES.ID.eq(candidate.jsonPrimitive.long)).execute())
            }
            assertNotEquals(frozen["snapshot"], merge(f, readOnlyPlan(f))["snapshot"], name)
        }
    }
    @Test fun mergePreviewRejectsSelfRelationAndBusinessCollisionWithoutChoosingOne() {
        for (name in names("merge_rejects_self_relation_or_collision_without_picking_one")) {
            val f = Fixture(name, readOnly = true)
            assertTrue(f.case.getValue("error").jsonPrimitive.content in setOf("relation_endpoint_incompatible", "relation_context_changed"), name)
            reject(f)
        }
    }
    @Test fun mergePreviewExplicitPatchUnifiesAllAdoptedNotes() {
        for (name in names("explicit_patch_unifies_multiple_adopted_notes_while_merging")) {
            val f = Fixture(name, readOnly = true); val plan = readOnlyPlan(f)
            assertEquals(JsonPrimitive("明确统一"), plan.getValue("items").jsonArray.single().jsonObject.getValue("display").jsonObject["note"], name)
            assertEquals(JsonPrimitive(3), plan.getValue("summary").jsonObject["resolve_issue"], name)
        }
    }
    @Test fun mergePreviewChecksEachAdoptedFinanceBeforeExplicitUnification() {
        for (name in names("each_adopted_finance_is_checked_before_explicit_unification")) {
            val f = Fixture(name, readOnly = true); reject(f, "fixed_field_conflict")
            assertTrue("amount" in f.case.getValue("error_fields").jsonArray.map { it.jsonPrimitive.content }, name)
        }
    }
    @Test fun mergePreviewAdoptedPartsStayPendingWhenTargetPartsAreUnknown() {
        for (name in names("adopted_new_source_parts_remain_pending_when_target_parts_are_unknown")) {
            val f = Fixture(name, readOnly = true); val plan = readOnlyPlan(f)
            assertEquals(buildJsonArray { add(buildJsonObject {
                put("ordinal", 0); put("account_id", "101"); put("amount", "-12.5"); put("currency", "CNY")
            }) }, merge(f, plan).getValue("pending_fields").jsonObject["components"], name)
            assertTrue(operation(f, plan).getValue("resolved_issue_ids").jsonArray.toSet()
                .containsAll(f.choices.map { it.jsonObject.getValue("issue_id") }), name)
        }
    }
    @Test fun mergePreviewCategoryProtectsTargetHumanClearAndExplicitChoice() {
        for (name in names("category_merge_protects_target_group_human_decisions")) {
            val f = Fixture(name, readOnly = true); val explicit = f.params.getValue("explicit").jsonPrimitive.boolean
            if (f.params.getValue("human_source").jsonPrimitive.boolean && !explicit) {
                reject(f, "source_resolution_conflict")
                assertTrue("category_id" in f.case.getValue("error_fields").jsonArray.map { it.jsonPrimitive.content }, name)
            } else {
                val plan = readOnlyPlan(f)
                val category = if (explicit) f.case.getValue("locals").jsonObject.getValue("category") else JsonNull
                assertEquals(category, plan.getValue("items").jsonArray.single().jsonObject.getValue("display").jsonObject["category_id"], name)
                val operation = operation(f, plan)
                assertEquals(explicit, operation["entry_edit"]?.jsonObject?.get("patch")?.jsonObject?.containsKey("category_id") == true, name)
            }
        }
    }
    @Test fun mergePreviewConfirmedGroupKeepsLegallyInactiveMember() {
        for (name in names("existing_confirmed_group_keeps_legally_deactivated_member")) {
            val f = Fixture(name, readOnly = true); val frozen = merge(f, readOnlyPlan(f))
            val group = frozen.getValue("relations").jsonArray.map { it.jsonObject }
                .filter { it["decision_group_id"] == JsonPrimitive("synthetic:old-confirmed") }
            assertEquals(setOf(JsonPrimitive("confirmed") to JsonPrimitive(false), JsonPrimitive("confirmed") to JsonPrimitive(true)),
                group.map { it["state"] to it["active"] }.toSet(), name)
        }
    }
    private fun assertParentAnchor(f: Fixture, relation: JsonObject) {
        for (field in listOf("primary_transaction_id", "anchor_transaction_id")) assertEquals(JsonPrimitive(f.target.toLong()), relation[field], "${f.name} $field")
        for (field in listOf("primary_component_id", "anchor_component_id", "primary_role")) assertEquals(JsonNull, relation[field], "${f.name} $field")
    }
    @Test fun allRejectedMergePreviewsKeepOriginalPublicErrorAndDoNotWrite() {
        var count = 0
        for (name in names()) {
            val f = Fixture(name, readOnly = true)
            if (f.case["public_error"] == null) continue
            count++
            testApplication {
                application { financeModule(f.database, mutationPlans = f.service) }
                val before = f.state(); val expected = f.case.getValue("public_error").jsonObject
                val response = client.post("/api/v1/cash-mutations/plans") {
                    header(HttpHeaders.Authorization, "Bearer ${f.token}"); contentType(ContentType.Application.Json); setBody(f.input.toString())
                }
                val body = response.bodyAsText(); assertEquals(expected.getValue("status").jsonPrimitive.int, response.status.value, "$name $body")
                assertEquals(expected.getValue("body"), Json.parseToJsonElement(body), name)
                assertEquals(before, f.state(), name); assertTrue(f.store.entries.isEmpty(), name)
            }
        }
        assertTrue(count > 0)
    }
    @Test fun mergePreviewEqualSplitPartsHaveNoArbitraryMapping() {
        for (name in names("equal_split_parts_do_not_get_arbitrary_mapping")) {
            val f = Fixture(name, readOnly = true); val frozen = merge(f, readOnlyPlan(f))
            assertTrue(frozen.getValue("component_map").jsonObject.values.all { it == JsonNull }, name)
            assertEquals(2, frozen.getValue("pending_fields").jsonObject.getValue("components").jsonArray.size, name)
            assertParentAnchor(f, frozen.getValue("relations").jsonArray.single().jsonObject)
        }
    }
    @Test fun mergePreviewRemovedTargetDraftPartsReturnRelationToParentAnchor() {
        for (name in names("target_draft_component_removal_also_returns_its_relations_to_parent_anchor")) {
            val f = Fixture(name, readOnly = true)
            assertParentAnchor(f, merge(f, readOnlyPlan(f)).getValue("relations").jsonArray.single().jsonObject)
        }
    }
    @Test fun allOriginalDraftMergePlansMatchTheCompleteReadOnlyBusinessGraph() {
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

    private fun failAfterWrite(f: Fixture, boundary: String, tables: Set<String>, retry: Boolean = false) {
        val plan = f.plan(); val before = f.state(); val balance = f.balances(); val writes = mutableListOf<String>(); var reached = 0
        val service = f.instrument(f.observedDatabase { writes += it }) { step ->
            if (step == boundary) {
                reached++; assertTrue(writes.any { query -> tables.any { query.contains(it) } }, "${f.name}: no actual $boundary write")
                error("synthetic merge unit failure after write")
            }
        }
        val failure = assertFailsWith<CashImportCommitFailure>(f.name) { f.rawCommit(plan, target = service) }
        assertEquals(1, reached, f.name); assertEquals("not_committed", failure.commitState, f.name)
        assertFalse(failure.message.orEmpty().contains("synthetic"), f.name)
        assertEquals(before, f.state(), f.name); assertEquals(balance, f.balances(), f.name)
        assertEquals(plan, f.service.plan(f.token, plan.getValue("plan_id").jsonPrimitive.content), f.name)
        if (retry) assertEquals(JsonPrimitive(f.target), f.commit(plan)["retained_transaction_id"], f.name)
    }
    @Test fun mergeCommitEveryActualUnitWriteFailureRestoresAllFacts() {
        val boundaries = mapOf("row" to ("record" to setOf("cash_transactions", "cash_entries")),
            "bindings" to ("merge_bindings" to setOf("cash_source_bindings")),
            "issues" to ("merge_issues" to setOf("cash_transaction_issues")),
            "relations" to ("merge_relations" to setOf("transaction_relations")),
            "candidates" to ("merge_candidates" to setOf("cash_issue_candidates", "cash_relation_candidates")),
            "source_exit" to ("merge_source_exit" to setOf("cash_transactions")))
        for (name in names("merge_unit_failure_restores_all_current_facts")) {
            val f = Fixture(name); val (boundary, tables) = boundaries.getValue(f.params.getValue("stage").jsonPrimitive.content)
            failAfterWrite(f, boundary, tables)
        }
    }
    @Test fun mergeCommitProjectionAndReceiptFailuresKeepPlanAndAllowOriginalRetry() {
        for (name in names("merge_publication_failure_rolls_back_everything_and_keeps_plan")) {
            val f = Fixture(name); val boundary = f.params.getValue("stage").jsonPrimitive.content
            failAfterWrite(f, boundary, if (boundary == "projection") setOf("cash_projections") else setOf("cash_mutation_receipts"), retry = true)
        }
    }
    @Test fun mergeCommitCategoryFailureRestoresEarlierTargetAndPreparedCandidates() {
        for (name in names("category_member_failure_rolls_back_earlier_target_and_prepared_candidates")) {
            val f = Fixture(name); failAfterWrite(f, "record", setOf("cash_entries", "cash_transactions"))
        }
    }
    @Test fun mergeCommitBindingAndReverseProofChangesInvalidateWithoutParentRevisionChange() {
        for (name in names("merge_rejects_current_proof_change_without_parent_revision_change")) {
            val f = Fixture(name); val plan = f.plan(); val revision = f.detail()["row_revision"]
            if (f.params.getValue("changed") == JsonPrimitive("binding")) f.addSourceBinding() else {
                val candidate = f.rows(CASH_RELATION_CANDIDATES).map { it.jsonObject }
                    .single { it["evidence"] == buildJsonObject { put("proof", "reverse") } }
                f.database.write { connection ->
                    assertEquals(1, connection.jooq(f.database.sqlite).update(CASH_RELATION_CANDIDATES)
                        .set(CASH_RELATION_CANDIDATES.EVIDENCE, buildJsonObject { put("proof", "current changed evidence") })
                        .where(CASH_RELATION_CANDIDATES.ID.eq(candidate.getValue("id").jsonPrimitive.long)).execute())
                }
            }
            assertEquals(revision, f.detail()["row_revision"], name)
            val before = f.state(); val failure = assertFailsWith<RuntimeException>(name) { f.rawCommit(plan) }
            assertEquals("record_changed", code(failure), name); assertEquals(before, f.state(), name)
            assertEquals(plan, f.service.plan(f.token, plan.getValue("plan_id").jsonPrimitive.content), name)
        }
    }
    @Test fun mergeCommitMovesBothDirectionsAndKeepsCompleteManualGroup() {
        for (name in names("merge_moves_both_directions_and_preserves_complete_manual_group")) {
            val f = Fixture(name)
            val original = f.rows(TRANSACTION_RELATIONS).map { it.jsonObject }
                .single { it["decision_group_id"] == JsonPrimitive("merge:joint") && it["primary_transaction_id"] == JsonPrimitive(f.id.toLong()) }
            val reverse = f.rows(CASH_RELATION_CANDIDATES).map { it.jsonObject }
                .single { it["evidence"] == buildJsonObject { put("proof", "reverse") } }
            assertEquals(JsonPrimitive("completed"), f.commit(f.plan())["status"], name)
            val target = f.detail(f.target); val part = target.getValue("fields").jsonObject.getValue("components").jsonArray.single().jsonObject
                .getValue("component_id").jsonPrimitive.long
            val moved = f.rows(TRANSACTION_RELATIONS, TRANSACTION_RELATIONS.ID.eq(original.getValue("id").jsonPrimitive.long)).single().jsonObject
            for (field in listOf("primary_transaction_id", "anchor_transaction_id")) assertEquals(JsonPrimitive(f.target.toLong()), moved[field], name)
            for (field in listOf("primary_component_id", "anchor_component_id")) assertEquals(JsonPrimitive(part), moved[field], name)
            assertEquals(JsonPrimitive("draft"), moved["state"], name); assertEquals(JsonPrimitive("manual"), moved["decision_source"], name)
            val group = f.rows(TRANSACTION_RELATIONS, TRANSACTION_RELATIONS.DECISION_GROUP_ID.eq("merge:joint"))
            assertEquals(2, group.size, name); assertTrue(group.all { it.jsonObject["decision_group_id"] == JsonPrimitive("merge:joint") }, name)
            val candidate = f.rows(CASH_RELATION_CANDIDATES, CASH_RELATION_CANDIDATES.ID.eq(reverse.getValue("id").jsonPrimitive.long)).single().jsonObject
            assertEquals(JsonPrimitive(f.target.toLong()), candidate["candidate_transaction_id"], name)
            assertEquals(JsonPrimitive(part), candidate["candidate_component_id"], name)
            assertEquals(JsonPrimitive("stale"), candidate["validity"], name); assertEquals(reverse["evidence"], candidate["evidence"], name)
            val issue = f.rows(CASH_ISSUE_CANDIDATES, CASH_ISSUE_CANDIDATES.ISSUE_ID.eq(f.case.getValue("locals").jsonObject.getValue("issue_id").jsonPrimitive.long)).single().jsonObject
            assertEquals(JsonPrimitive(f.target.toLong()), issue["candidate_transaction_id"], name)
            assertNotEquals(target["row_revision"], issue["expected_row_revision"], name)
            assertTrue(f.rows(CASH_ISSUE_CANDIDATES, CASH_ISSUE_CANDIDATES.CANDIDATE_TRANSACTION_ID.eq(f.id.toLong())).isEmpty(), name)
            assertTrue(f.rows(CASH_RELATION_CANDIDATES, CASH_RELATION_CANDIDATES.CANDIDATE_TRANSACTION_ID.eq(f.id.toLong())).isEmpty(), name)
        }
    }
    @Test fun mergeCommitDeletesEquivalentCandidatesBeforeClearingTargetParts() {
        for (name in names("equivalent_candidates_are_removed_before_target_parts_are_cleared")) {
            val f = Fixture(name); val writes = mutableListOf<String>(); val plan = f.plan()
            val receipt = f.rawCommit(plan, target = f.instrument(f.observedDatabase { writes += it }) {})
            assertEquals(JsonPrimitive("completed"), receipt["status"], name)
            val relation = f.case.getValue("locals").jsonObject.getValue("relation_id").jsonPrimitive.long
            val candidate = f.rows(CASH_RELATION_CANDIDATES, CASH_RELATION_CANDIDATES.RELATION_ID.eq(relation)).single().jsonObject
            assertEquals(JsonPrimitive(f.target.toLong()), candidate["candidate_transaction_id"], name)
            assertEquals(JsonNull, candidate["candidate_component_id"], name)
            val duplicate = writes.indexOfFirst { it.trimStart().startsWith("delete") && it.contains("cash_relation_candidates") }
            val parts = writes.indexOfFirst { it.trimStart().startsWith("delete") && it.contains("cash_transaction_components") }
            assertTrue(duplicate >= 0 && parts > duplicate, "$name: equivalent candidates were not removed before target parts")
        }
    }
    @Test fun mergeCommitSourceIssueKeyAvoidsNewTargetIssueInSameConfirmation() {
        for (name in names("source_issue_key_also_avoids_new_target_issue_from_same_confirmation")) {
            val f = Fixture(name)
            val before = f.rows(CASH_TRANSACTION_ISSUES, CASH_TRANSACTION_ISSUES.TRANSACTION_ID.eq(f.id.toLong())).map { it.jsonObject }
                .filter { it["code"] != JsonPrimitive("source_identity_ambiguous") }
            assertTrue(before.isNotEmpty(), name); assertEquals(JsonPrimitive("completed"), f.commit(f.plan())["status"], name)
            val rows = f.rows(CASH_TRANSACTION_ISSUES, CASH_TRANSACTION_ISSUES.TRANSACTION_ID.eq(f.target.toLong())).map { it.jsonObject }
            for (old in before) {
                val moved = rows.single { it["id"] == old["id"] }
                assertNotEquals(old["issue_key"], moved["issue_key"], name)
                for (field in listOf("pending_fields", "pending_source_payload", "origin")) assertEquals(old[field], moved[field], "$name $field")
                assertEquals(2, rows.count { it["code"] == old["code"] && it["field_path"] == old["field_path"] }, name)
            }
        }
    }
    @Test fun mergeCommitMovedPendingComponentBodyCanStillBeAdopted() {
        for (name in names("pending_source_component_reference_remains_maintainable_after_move")) {
            val f = Fixture(name)
            val id = f.issues().map { it.jsonObject }.single { it["pending_fields"]?.jsonObject?.containsKey("components") == true }.getValue("issue_id")
            assertEquals(JsonPrimitive("completed"), f.commit(f.plan())["status"], name)
            val current = f.detail(f.target); val issue = current.getValue("issues").jsonArray.map { it.jsonObject }.single { it["issue_id"] == id }
            assertEquals(current.getValue("fields").jsonObject["components"], issue.getValue("pending_fields").jsonObject["components"], name)
            assertTrue(JsonPrimitive("apply_source") in issue.getValue("allowed_resolutions").jsonArray, name)
            val plan = f.plan(buildJsonObject {
                put("intent", "resolve_issue"); put("transaction_id", f.target); put("expected_revision", current.getValue("row_revision"))
                put("issue_resolutions", buildJsonArray { add(buildJsonObject {
                    put("issue_id", id); put("expected_issue_revision", issue.getValue("revision")); put("resolution", "apply_source")
                }) })
            })
            assertEquals(1, summary(f.commit(plan, "resolve-moved-body"), "resolved_issues"), name)
        }
    }
    @Test fun mergeCommitPreservesIssueInstancesBodiesCandidatesAndKeyCollision() {
        for (name in names("merge_preserves_issue_instances_bodies_candidates_and_same_key_collision")) {
            val f = Fixture(name)
            val before = f.rows(CASH_TRANSACTION_ISSUES).map { it.jsonObject }.filter { it["issue_key"] == JsonPrimitive("same:key") }
            assertEquals(2, before.size, name)
            fun bodies(rows: List<JsonObject>) = rows.associate { row -> row.getValue("id") to
                listOf(row["pending_fields"], row["pending_source_payload"], row["origin"]) }
            val receipt = f.commit(f.plan()); assertEquals(JsonPrimitive("completed"), receipt["status"], name)
            val after = f.rows(CASH_TRANSACTION_ISSUES).map { it.jsonObject }.filter { row -> row["id"] in before.map { it["id"] } }
            assertEquals(bodies(before), bodies(after), name)
            assertEquals(setOf(JsonPrimitive(f.target.toLong())), after.map { it["transaction_id"] }.toSet(), name)
            assertEquals(2, after.map { it["issue_key"] }.toSet().size, name)
            assertTrue(after.all { it.getValue("issue_key").jsonPrimitive.content.length <= 128 }, name)
            val existing = before.single { it["transaction_id"] == JsonPrimitive(f.target.toLong()) }
            assertEquals(JsonPrimitive("same:key"), after.single { it["id"] == existing["id"] }["issue_key"], name)
            assertEquals(before.map { it["id"] }.toSet(), f.rows(CASH_ISSUE_CANDIDATES).map { it.jsonObject["issue_id"] }.toSet(), name)
        }
    }
    @Test fun mergeCommitDuplicateCandidatesKeepExistingTargetOnlyWhenEquivalent() {
        for (name in names("duplicate_candidates_keep_existing_target_only_when_equivalent")) {
            val f = Fixture(name)
            if (!f.params.getValue("same_evidence").jsonPrimitive.boolean) reject(f, "relation_context_changed") else {
                val receipt = f.commit(f.plan()); assertEquals(JsonPrimitive("completed"), receipt["status"], name)
                val locals = f.case.getValue("locals").jsonObject
                val candidate = f.rows(CASH_RELATION_CANDIDATES, CASH_RELATION_CANDIDATES.RELATION_ID.eq(locals.getValue("relation_id").jsonPrimitive.long)).single().jsonObject
                assertEquals(locals["target_candidate_id"], candidate["id"], name)
                assertEquals(JsonPrimitive("stale"), candidate["validity"], name)
                assertNotEquals(f.detail(f.target)["row_revision"], candidate["expected_row_revision"], name)
            }
        }
    }
    @Test fun mergeCommitPostedTargetTypeChangeRejectsAndKeepsGroupAndBalance() {
        for (name in names("merge_rejects_posted_target_type_change_and_preserves_group")) {
            val f = Fixture(name, readOnly = true); val balance = f.balances(); reject(f)
            assertEquals(balance, f.balances(), name)
        }
    }
    @Test fun mergeCommitMultipleSourceDecisionsAndFinalPatchShareOneReceipt() {
        for (name in names("merge_applies_multiple_source_decisions_with_final_patch_in_one_receipt")) {
            val f = Fixture(name); val receipt = f.commit(f.plan())
            assertEquals(JsonPrimitive("completed"), receipt["status"], name); assertEquals(3, summary(receipt, "resolved_issues"), name)
            assertEquals(JsonPrimitive("最后明确值"), f.entry(f.target).getValue("fields").jsonObject["note"], name)
        }
    }
    @Test fun mergeKeepsTargetIdentityBalanceAndPermanentReplay() {
        val captures = mutableListOf<JsonObject>()
        for (name in names("merge_keeps_target_identity_balance_and_permanent_replay")) testApplication {
            val f = Fixture(name); val balance = f.balances(); val draft = f.params.getValue("draft_target").jsonPrimitive.boolean
            application { financeModule(f.database, mutationPlans = f.service) }
            suspend fun record(identifier: String): JsonObject {
                val response = client.get("/api/v1/cash-records/$identifier") { header(HttpHeaders.Authorization, "Bearer ${f.token}") }
                val body = response.bodyAsText(); assertEquals(200, response.status.value, body); return Json.parseToJsonElement(body).jsonObject
            }
            val before = record(f.id)
            assertTrue(JsonPrimitive("merge_into") in f.issues().first { it.jsonObject["code"] == JsonPrimitive("source_identity_ambiguous") }.jsonObject.getValue("allowed_resolutions").jsonArray)
            val response = client.post("/api/v1/cash-mutations/plans") {
                header(HttpHeaders.Authorization, "Bearer ${f.token}"); contentType(ContentType.Application.Json); setBody(f.input.toString())
            }
            val body = response.bodyAsText(); assertEquals(200, response.status.value, body)
            val plan = Json.parseToJsonElement(body).jsonObject; assertEquals(JsonPrimitive(true), plan["can_commit"])
            val planId = plan.getValue("plan_id").jsonPrimitive.content
            val items = plan.getValue("items").jsonArray.map { item ->
                val read = client.get("/api/v1/cash-mutations/plans/$planId/items/${item.jsonObject.getValue("preview_item_id").jsonPrimitive.content}") {
                    header(HttpHeaders.Authorization, "Bearer ${f.token}")
                }
                val detail = read.bodyAsText(); assertEquals(200, read.status.value, detail); Json.parseToJsonElement(detail)
            }
            val saved = client.post("/api/v1/cash-mutations/plans/$planId/commit") {
                header(HttpHeaders.Authorization, "Bearer ${f.token}"); header("Idempotency-Key", "draft-merge-confirm")
                contentType(ContentType.Application.Json); setBody(buildJsonObject { put("expected_plan_revision", plan.getValue("plan_revision")) }.toString())
            }
            val receiptBody = saved.bodyAsText(); assertEquals(200, saved.status.value, receiptBody)
            val receipt = Json.parseToJsonElement(receiptBody).jsonObject
            assertEquals(JsonPrimitive("completed"), receipt["status"]); assertEquals(1, summary(receipt, "merged_draft")); assertEquals(1, summary(receipt, "resolved_issues"))
            assertEquals(JsonPrimitive(f.target), receipt["retained_transaction_id"])
            val current = f.detail(f.target); assertEquals(JsonPrimitive(if (draft) "draft" else "posted"), current["state"])
            assertEquals(JsonPrimitive(""), current.getValue("entry").jsonObject.getValue("fields").jsonObject["note"])
            assertEquals(balance, f.balances())
            assertEquals("record_not_found", assertFailsWith<CashRecordDetailFailure> { f.detail() }.code)
            assertTrue(f.bindings().isEmpty())
            assertTrue(f.rows(CASH_SOURCE_BINDINGS, CASH_SOURCE_BINDINGS.TRANSACTION_ID.eq(f.target.toLong())).isNotEmpty())
            assertNotEquals(JsonNull, f.rows(CASH_TRANSACTIONS, CASH_TRANSACTIONS.ID.eq(f.id.toLong())).single().jsonObject["deleted_at"])
            assertTrue(JsonPrimitive("note") in current.getValue("entry").jsonObject.getValue("manual_fields").jsonArray)
            val result = f.resultRows(receipt).single().jsonObject
            assertEquals(JsonPrimitive(f.id), result["item_ref"]); assertEquals(JsonPrimitive(f.target), result["transaction_id"]); assertEquals(JsonPrimitive("merged_draft"), result["outcome"])
            f.store.entries.clear(); assertEquals(receipt, f.commit(plan, "draft-merge-confirm"))
            assertEquals(receipt, f.service.resolve(f.token, plan.getValue("plan_id").jsonPrimitive.content, "draft-merge-confirm")["receipt"])
            val locations = ReceiptReadService(f.database, now = { at }).results(f.token, receipt.getValue("receipt_id").jsonPrimitive.content, "row", ReceiptPage(0, 50)).getValue("projection_refs").jsonArray
            assertEquals(setOf(JsonPrimitive(f.target)), locations.map { it.jsonObject["transaction_id"] }.toSet())
            captures += buildJsonObject {
                put("request", f.input); put("plan", plan); put("items", JsonArray(items)); put("receipt", receipt)
                put("before", before); put("after", record(f.target)); put("key", "draft-merge-confirm"); put("draft_target", draft)
            }
            if (!draft) { assertEquals(JsonPrimitive(f.target), f.resultRows(f.reimport()).single().jsonObject["transaction_id"]); assertEquals(JsonPrimitive(""), f.detail(f.target).getValue("entry").jsonObject.getValue("fields").jsonObject["note"]) }
        }
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { output ->
            val directory = Path.of(output); Files.createDirectories(directory)
            Files.writeString(directory.resolve("draft-merge-response.json"), JsonArray(captures).toString())
        }
    }
}
