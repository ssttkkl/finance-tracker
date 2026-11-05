package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashEntryServiceOriginalTest {
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private val token = "original-import-owner"
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_ENTRY_SERVICE_CONTRACT_DIR")))
    private inner class Fixture(val name: String) {
        private val directory = root().resolve(name)
        private val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        private val input = Json.parseToJsonElement(directory.resolve("entry.json").readText()).jsonObject
        val params = input.getValue("metadata").jsonObject.getValue("params").jsonObject
        val id = input["transaction_id"]?.jsonPrimitive?.contentOrNull
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val user = config.getValue("user").jsonPrimitive.content
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val store = InMemoryParsedImportStore(clock = { at })
        val service = MutationPlanService(database, now = { at }, staging = store)
        var sequence = 0
        fun detail(identifier: String = id!!) = CashRecordDetailService(database, now = { at }).detail(token, identifier)
        fun fields(identifier: String = id!!) = detail(identifier).getValue("fields").jsonObject
        fun entry(identifier: String = id!!) = detail(identifier).getValue("entry").jsonObject
        fun preview(identifier: String = id!!, patch: JsonObject = buildJsonObject {}, intent: String = "edit_record") = service.preview(token,
            buildJsonObject { put("intent", intent); put("transaction_id", identifier); put("expected_revision", detail(identifier).getValue("row_revision")); put("patch", patch) })
        fun commit(view: JsonObject) = service.commit(token, view.getValue("plan_id").jsonPrimitive.content,
            view.getValue("plan_revision").jsonPrimitive.long, "entry-service-${++sequence}")
        fun edit(identifier: String = id!!, patch: JsonObject): JsonObject = commit(preview(identifier, patch)).also {
            assertEquals(JsonPrimitive(true), it["committed"]); assertEquals(JsonPrimitive("completed"), it["status"])
        }
        fun groups(view: JsonObject) = items(view).flatMap { item -> service.item(token, view.getValue("plan_id").jsonPrimitive.content,
            item.getValue("preview_item_id").jsonPrimitive.content).getValue("relation_choices").jsonArray.map { it.jsonObject } }.distinctBy { it["suggestion_id"] }
        fun choose(view: JsonObject, choice: JsonObject, identifier: String = id!!) = service.preview(token, buildJsonObject {
            put("intent", "maintain_relations"); put("transaction_id", identifier); put("expected_revision", detail(identifier).getValue("row_revision")); put("patch", buildJsonObject {})
            put("edit_context_id", view.getValue("edit_context_id")); put("previous_plan_id", view.getValue("plan_id")); put("expected_plan_revision", view.getValue("plan_revision"))
            put("relation_choices", JsonArray(listOf(choice)))
        })
        fun rebuild() = CashProjectionService(database, workspace, now = { at }).rebuild()
        fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table -> table.name to connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            }
        } } }
        fun rejected(code: String? = null, action: () -> Any?) {
            val before = snapshot(); val error = assertFailsWith<RuntimeException>(name, action)
            val actual = when (error) {
                is MutationPlanFieldFailure -> error.code
                is MutationPlanFailure -> error.code
                is CashImportPlanError -> error.code
                is CashPolicyError -> error.code
                is CashRelationFailure -> error.code
                else -> throw error
            }
            if (code != null) assertEquals(code, actual, name)
            assertEquals(before, snapshot(), name)
        }
        fun <T> read(block: (CashSqlContext) -> T) = database.read { block(CashSqlContext(it, workspace, database.sqlite, now = { at })) }
        fun <T> write(block: (CashSqlContext) -> T) = database.write { block(CashSqlContext(it, workspace, database.sqlite, now = { at })) }
        fun <T> withoutDiscoveryPlanning(block: (CashSqlContext) -> T): T {
            val observed = Database(config.getValue("jdbc_url").jsonPrimitive.content,
                System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")) { connection ->
                Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    if (method.name == "prepareStatement") assertFalse((args!![0] as String).replace("\"", "").replace("`", "").lowercase().contains("from account_aliases"),
                        "Manual-only discovery reached the alias read before relation planning")
                    try { method.invoke(connection, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
                } as Connection
            }
            return observed.read { block(CashSqlContext(it, workspace, observed.sqlite, now = { at })) }
        }
        fun projectionRows() = read { context -> context.sql.select(CASH_PROJECTIONS.ID, CASH_PROJECTIONS.ENTRY_ID, CASH_PROJECTIONS.ROOT_CASH_TRANSACTION_ID,
            CASH_PROJECTIONS.NOTE, CASH_PROJECTIONS.COUNTERPARTY).from(CASH_PROJECTIONS).where(CASH_PROJECTIONS.WORKSPACE_ID.eq(workspace))
            .and(CASH_PROJECTIONS.ROOT_CASH_TRANSACTION_ID.`in`(1001L, 1003L)).fetch() }
        fun files(label: String): List<ParsedImportFile> {
            val variant = input.getValue("files").jsonObject.getValue(label).jsonObject
            return ParsedImportCodec().decode(variant.getValue("session_id").jsonPrimitive.content, variant.getValue("session").toString().encodeToByteArray()).files
        }
        fun importPlan(label: String, rows: List<Map<String, Any?>> = emptyList()): Map<String, Any?> {
            val files = files(label); val session = store.create(workspace, user, files)
            val choices = mapOf("account_selections" to CashImportFunding.scan(files).groups.map {
                mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to "101", "expected_account_revision" to 1)
            }, "row_choices" to rows, "relation_choices" to emptyList<Any?>(), "entry_choices" to emptyList<Any?>())
            return CashImportHttpService(database, store).preview(token, session.sessionId, 1, null, choices)
        }
        fun importCommit(plan: Map<String, Any?>) = CashImportCommandService(database, store, now = { at }).commit(token,
            plan.getValue("plan_id") as String, (plan.getValue("session_revision") as Number).toLong(), "entry-import-${++sequence}")
        fun resultRows(receipt: JsonObject) = ReceiptReadService(database, now = { at }).results(token,
            receipt.getValue("receipt_id").jsonPrimitive.content, "row", ReceiptPage(0, 200)).getValue("items").jsonArray.map { it.jsonObject }
        fun imported(receipt: JsonObject) = resultRows(receipt).first().getValue("transaction_id").jsonPrimitive.content
    }
    private fun cases(label: String, block: (Fixture) -> Unit) {
        val names = Json.parseToJsonElement(root().resolve("scenarios.json").readText()).jsonObject.filterValues { it.jsonObject["label"] == JsonPrimitive(label) }.keys
        assertTrue(names.isNotEmpty(), label); for (name in names) block(Fixture(name))
    }
    private fun items(view: JsonObject) = view.getValue("items").jsonArray.map { it.jsonObject }
    private fun display(view: JsonObject) = items(view).first().getValue("display").jsonObject
    private fun choice(group: JsonObject, action: String, extras: JsonObject = buildJsonObject {}) = JsonObject(extras + mapOf("suggestion_id" to group.getValue("suggestion_id"), "action" to JsonPrimitive(action)))

    @Test fun importedTypeAndOriginCannotBeForged() = cases("fixed_origin") { f ->
        val detail = f.detail(); assertEquals(JsonPrimitive("import"), detail["record_origin"])
        assertFalse(JsonPrimitive("record_type") in detail.getValue("editable_fields").jsonArray)
        for (patch in listOf(buildJsonObject { put("record_type", "other") }, buildJsonObject { put("record_origin", "manual") },
            buildJsonObject { put("source_payload", buildJsonObject { put("备注", "伪造") }) })) f.rejected { f.preview(patch = patch) }
    }
    @Test fun workspaceDeletionClearsClassifiedEntriesAndPreservesOtherWorkspace() = testApplication {
        val f = Fixture("workspace_delete_0"); f.edit(patch = buildJsonObject { put("category_id", "category-food"); put("note", "已整理") })
        application { financeModule(f.database, clock = { at }) }
        val registered = client.post("/api/v1/auth/register") { contentType(ContentType.Application.Json); setBody("""{"email":"entry-delete-admin@example.test","password":"synthetic-password-123"}""") }
        assertEquals(HttpStatusCode.OK, registered.status)
        val auth = Json.parseToJsonElement(registered.bodyAsText()).jsonObject
        val newUser = f.read { it.sql.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq("entry-delete-admin@example.test")).fetchSingle(USERS.ID) }
        val access = auth.getValue("access_token").jsonPrimitive.content
        f.write { c ->
            c.sql.insertInto(WORKSPACES).set(WORKSPACES.ID, "entry-delete-unrelated").set(WORKSPACES.NAME, "保留工作区").set(WORKSPACES.CREATED_AT, at).execute()
            c.sql.insertInto(CASH_ENTRIES).set(CASH_ENTRIES.ID, "unrelated-entry").set(CASH_ENTRIES.WORKSPACE_ID, "entry-delete-unrelated")
                .set(CASH_ENTRIES.ROOT_TRANSACTION_ID, 0L).set(CASH_ENTRIES.REVISION, 1L).set(CASH_ENTRIES.NOTE, "").set(CASH_ENTRIES.COUNTERPARTY, "")
                .set(CASH_ENTRIES.COUNTERPARTY_ACCOUNT, "").set(CASH_ENTRIES.COUNTERPARTY_ACCOUNT_ATTRS, JsonArray(emptyList())).set(CASH_ENTRIES.MANUAL_FIELDS, JsonArray(emptyList())).execute()
            c.sql.insertInto(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, f.workspace).set(WORKSPACE_MEMBERSHIPS.USER_ID, newUser).set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").set(WORKSPACE_MEMBERSHIPS.CREATED_AT, at).execute()
            c.sql.update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, f.workspace).where(USER_SESSIONS.USER_ID.eq(newUser)).execute()
        }
        val workspaceName = f.read { it.sql.select(WORKSPACES.NAME).from(WORKSPACES).where(WORKSPACES.ID.eq(f.workspace)).fetchSingle(WORKSPACES.NAME) }
        val response = client.delete("/api/v1/auth/workspace") { bearerAuth(access); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("name", workspaceName) }.toString()) }
        assertEquals(HttpStatusCode.OK, response.status)
        f.read { c ->
            assertFalse(c.sql.fetchExists(WORKSPACES, WORKSPACES.ID.eq(f.workspace)))
            assertFalse(c.sql.fetchExists(CASH_ENTRIES, CASH_ENTRIES.WORKSPACE_ID.eq(f.workspace)))
            assertFalse(c.sql.fetchExists(CASH_ENTRY_MEMBERS, CASH_ENTRY_MEMBERS.WORKSPACE_ID.eq(f.workspace)))
            assertTrue(c.sql.fetchExists(CASH_ENTRIES, CASH_ENTRIES.ID.eq("unrelated-entry")))
        }
    }
    @Test fun publicEditsPreserveBothSourcesAndSurviveFullRebuild() = cases("public_rebuild") { f ->
        val old = f.projectionRows().single(); val oldRow = old[CASH_PROJECTIONS.ID]
        val id = f.params.getValue("edited_member").jsonPrimitive.content
        val plan = f.preview(id, buildJsonObject { put("note", ""); put("counterparty", "统一对方"); put("counterparty_account", "显示账号") })
        assertEquals(JsonPrimitive(""), display(plan)["note"]); assertEquals(JsonPrimitive("统一对方"), display(plan)["counterparty"])
        assertEquals(JsonPrimitive("completed"), f.commit(plan)["status"])
        assertEquals(JsonPrimitive("平台原文"), f.fields("1003")["note"]); assertEquals(JsonPrimitive("银行原文"), f.fields("1001")["note"])
        val current = f.projectionRows().single(); assertEquals(oldRow, current[CASH_PROJECTIONS.ID]); assertEquals("", current[CASH_PROJECTIONS.NOTE]); assertEquals("统一对方", current[CASH_PROJECTIONS.COUNTERPARTY])
        val entryId = current[CASH_PROJECTIONS.ENTRY_ID]
        f.read { c -> val state = c.sql.selectFrom(CASH_PROJECTION_STATES).where(CASH_PROJECTION_STATES.WORKSPACE_ID.eq(f.workspace)).fetchSingle()
            assertEquals("succeeded", state.lastBuildStatus); assertNull(state.lastErrorCode); assertNull(state.lastErrorSummary) }
        f.rebuild(); val rebuilt = f.projectionRows().single(); assertEquals(entryId, rebuilt[CASH_PROJECTIONS.ENTRY_ID]); assertEquals("", rebuilt[CASH_PROJECTIONS.NOTE]); assertEquals("统一对方", rebuilt[CASH_PROJECTIONS.COUNTERPARTY])
        assertEquals(JsonArray(emptyList()), f.detail("1001")["editable_fields"])
    }
    @Test fun manualFinancialEditKeepsOriginAndOriginalEditableFields() = cases("manual_financial") { f ->
        val before = f.detail(); assertEquals(JsonPrimitive("manual"), before["record_origin"]); assertTrue(JsonPrimitive("amount") in before.getValue("editable_fields").jsonArray)
        val plan = f.preview(); val member = f.service.item(token, plan.getValue("plan_id").jsonPrimitive.content, items(plan).first().getValue("preview_item_id").jsonPrimitive.content)
            .getValue("members").jsonArray.first().jsonObject
        assertEquals(before.getValue("editable_fields").jsonArray.toSet(), member.getValue("editable_fields").jsonArray.toSet())
        val part = f.fields().getValue("components").jsonArray.first().jsonObject
        f.edit(patch = buildJsonObject { put("amount", "-30"); put("components", JsonArray(listOf(JsonObject(part + ("amount" to JsonPrimitive("-30")))))) })
        assertEquals(JsonPrimitive("-30"), f.fields()["amount"]); assertEquals(JsonPrimitive("manual"), f.detail()["record_origin"])
    }
    @Test fun unlinkRequiresExplicitInheritanceAndKeepsRestoredValuesAfterRebuild() = cases("unlink") { f ->
        f.edit(patch = buildJsonObject { put("note", "整笔人工备注") })
        val first = f.preview(intent = "maintain_relations"); val group = f.groups(first).first { JsonPrimitive("remove") in it.getValue("allowed_actions").jsonArray }
        f.rejected("inheritance_required") { f.choose(first, choice(group, "remove")) }
        val view = f.choose(first, choice(group, "remove", buildJsonObject { put("inherit_common_fields", f.params.getValue("inherit")) }))
        assertEquals(JsonPrimitive("completed"), f.commit(view)["status"])
        val expected = if (f.params["inherit"] == JsonPrimitive(true)) setOf("整笔人工备注") else setOf("平台原文", "银行原文")
        assertEquals(2, f.projectionRows().size); assertEquals(expected, f.projectionRows().map { it[CASH_PROJECTIONS.NOTE] }.toSet())
        f.rebuild(); assertEquals(expected, f.projectionRows().map { it[CASH_PROJECTIONS.NOTE] }.toSet())
    }
    @Test fun staleEntryRevisionRejectsOldEditWithoutChangingSourceRevision() = cases("entry_revision") { f ->
        val old = f.preview(patch = buildJsonObject { put("note", "旧计划") }); val before = f.detail()
        f.edit(patch = buildJsonObject { put("note", "先保存的新值") }); val after = f.detail()
        assertEquals(before["row_revision"], after["row_revision"])
        assertTrue(before.getValue("entry").jsonObject.getValue("revision").jsonPrimitive.long < after.getValue("entry").jsonObject.getValue("revision").jsonPrimitive.long)
        f.rejected { f.commit(old) }; assertEquals(JsonPrimitive("先保存的新值"), f.entry().getValue("fields").jsonObject["note"])
    }
    @Test fun manualOriginPreventsCashRelationsEvenWithForgedSource() = cases("manual_relation") { f ->
        f.rejected("manual_relation_forbidden") { f.write { c -> CurrentCashRelationWriteRepository(c).add(mapOf("kind" to "payment_mirror", "state" to "draft",
            "primary_transaction_id" to 1001, "secondary_transaction_id" to 1003, "anchor_transaction_id" to 1001)) } }
    }
    @Test fun conflictingHumanValuesDeferOnlyRelationUntilExplicitCommonChoice() = cases("common_conflict") { f ->
        for ((id, note) in listOf("1003" to "人工甲", "1001" to "人工乙")) f.edit(id, buildJsonObject { put("note", note) })
        val first = f.preview(intent = "maintain_relations"); val group = f.groups(first).first { JsonPrimitive("accept") in it.getValue("allowed_actions").jsonArray }
        val pending = f.choose(first, choice(group, "accept")); assertEquals(2, items(pending).size)
        val conflict = f.groups(pending).first(); assertEquals(setOf(JsonPrimitive("人工甲"), JsonPrimitive("人工乙")), conflict.getValue("common_field_conflicts").jsonObject.getValue("note").jsonArray.toSet())
        assertEquals(JsonPrimitive("defer"), conflict["action"])
        val confirmed = f.choose(pending, choice(conflict, "accept", buildJsonObject { put("common_fields", buildJsonObject { put("note", "共同决定") }) }))
        assertEquals(1, items(confirmed).size); assertEquals(JsonPrimitive("共同决定"), display(confirmed)["note"])
        val receipt = f.commit(confirmed); assertEquals(JsonPrimitive(1), receipt.getValue("summary").jsonObject["relations_confirmed"])
        assertEquals(JsonPrimitive("共同决定"), f.entry("1001").getValue("fields").jsonObject["note"])
    }
    @Test fun deletingStandaloneRemovesEntryAndMembership() = cases("delete_single") { f ->
        val entryId = f.entry().getValue("entry_id").jsonPrimitive.content; CashDeletionService(f.database, now = { at }).deleteRecord(token, f.id!!)
        f.read { c -> assertFalse(c.sql.fetchExists(CASH_ENTRY_MEMBERS, CASH_ENTRY_MEMBERS.TRANSACTION_ID.eq(f.id.toLong())))
            assertFalse(c.sql.fetchExists(CASH_ENTRIES, CASH_ENTRIES.ID.eq(entryId))) }
    }
    @Test fun deletingDisplayRootKeepsEntryWithSurvivingRoot() = cases("delete_root") { f ->
        val entryId = f.entry("1003").getValue("entry_id").jsonPrimitive.content; CashDeletionService(f.database, now = { at }).deleteRecord(token, "1003")
        assertEquals(1001L, f.read { it.sql.select(CASH_ENTRIES.ROOT_TRANSACTION_ID).from(CASH_ENTRIES).where(CASH_ENTRIES.ID.eq(entryId)).fetchSingle(CASH_ENTRIES.ROOT_TRANSACTION_ID) })
    }
    @Test fun importPublicPatchPreservesSourceAndProtectsEntryAcrossReimport() = cases("import_public") { f ->
        if (f.params["existing"] == JsonPrimitive(true)) f.importCommit(f.importPlan("first"))
        val plan = f.importPlan("second", listOf(mapOf("row_ref" to "file_a:r1", "action" to "apply", "patch" to mapOf("note" to "", "counterparty" to "人工对方"))))
        assertEquals("", ((plan.getValue("items") as List<*>).first() as Map<*, *>).let { it["display"] as Map<*, *> }["note"])
        val id = f.imported(f.importCommit(plan)); assertEquals(JsonPrimitive("来源乙"), f.fields(id)["note"]); assertEquals(JsonPrimitive("合成商户"), f.fields(id)["counterparty"])
        assertEquals(JsonPrimitive(""), f.entry(id).getValue("fields").jsonObject["note"])
        val updated = f.importPlan("third"); assertEquals("", ((updated.getValue("items") as List<*>).first() as Map<*, *>).let { it["display"] as Map<*, *> }["note"])
        f.importCommit(updated); assertEquals(JsonPrimitive("人工对方"), f.entry(id).getValue("fields").jsonObject["counterparty"]); assertEquals(JsonPrimitive("来源丙"), f.fields(id)["note"])
    }
    @Test fun unprotectedReimportMatchesPreviewCommitAndFullRebuild() = cases("reimport_source") { f ->
        val id = f.imported(f.importCommit(f.importPlan("first"))); val entryId = f.entry(id)["entry_id"]
        val plan = f.importPlan("second"); assertEquals("来源乙", ((plan.getValue("items") as List<*>).first() as Map<*, *>).let { it["display"] as Map<*, *> }["note"])
        f.importCommit(plan); f.rebuild(); assertEquals(entryId, f.entry(id)["entry_id"]); assertEquals(JsonPrimitive("来源乙"), f.entry(id).getValue("fields").jsonObject["note"])
    }
    @Test fun reimportCombinesOnePublicDecisionAndRejectsConflictingValues() = cases("reimport_common") { f ->
        f.importCommit(f.importPlan("mirror"))
        val patch = f.params.getValue("second_patch").jsonObject.mapValues { cashSourceValue(it.value) }
        val rows = listOf(mapOf("row_ref" to "file_a:r1", "action" to "apply", "patch" to mapOf("note" to "统一备注")),
            mapOf("row_ref" to "file_b:r1", "action" to "apply", "patch" to patch))
        if (patch["note"] == "冲突备注") f.rejected { f.importPlan("mirror", rows) }
        else {
            val plan = f.importPlan("mirror", rows); assertEquals("统一备注", ((plan.getValue("items") as List<*>).first() as Map<*, *>).let { it["display"] as Map<*, *> }["note"])
            val receipt = f.importCommit(plan); assertEquals(JsonPrimitive(0), receipt.getValue("summary").jsonObject["skipped"])
            val results = f.resultRows(receipt); assertEquals(2, results.size); val fields = f.entry(results.first().getValue("transaction_id").jsonPrimitive.content).getValue("fields").jsonObject
            assertEquals(JsonPrimitive("统一备注"), fields["note"]); if ("counterparty" in patch) assertEquals(f.params.getValue("second_patch").jsonObject["counterparty"], fields["counterparty"])
        }
    }
    @Test fun editEntryHttpUsesPublicRevisionAndKeepsSourceFields() = testApplication {
        val f = Fixture("entry_http_0"); application { financeModule(f.database, clock = { at }) }
        val path = "/api/v1/cash-records/${f.id}"; val beforeResponse = client.get(path) { bearerAuth(token) }; assertEquals(HttpStatusCode.OK, beforeResponse.status)
        val before = Json.parseToJsonElement(beforeResponse.bodyAsText()).jsonObject; assertEquals(JsonPrimitive("import"), before["record_origin"])
        val body = buildJsonObject { put("intent", "edit_entry"); put("transaction_id", f.id); put("expected_revision", before.getValue("row_revision"))
            put("entry_id", before.getValue("entry").jsonObject.getValue("entry_id")); put("expected_entry_revision", before.getValue("entry").jsonObject.getValue("revision"))
            put("patch", buildJsonObject { put("note", ""); put("counterparty_account", "显示账号") }) }
        val response = client.post("/api/v1/cash-mutations/plans") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body.toString()) }
        assertEquals(HttpStatusCode.OK, response.status); val plan = Json.parseToJsonElement(response.bodyAsText()).jsonObject; assertEquals(JsonPrimitive(""), display(plan)["note"])
        val committed = client.post("/api/v1/cash-mutations/plans/${plan.getValue("plan_id").jsonPrimitive.content}/commit") { bearerAuth(token); header("Idempotency-Key", "entry-service-http")
            contentType(ContentType.Application.Json); setBody(buildJsonObject { put("expected_plan_revision", plan.getValue("plan_revision")) }.toString()) }
        assertEquals(HttpStatusCode.OK, committed.status)
        val after = Json.parseToJsonElement(client.get(path) { bearerAuth(token) }.bodyAsText()).jsonObject
        assertEquals(before["fields"], after["fields"]); assertEquals(before["row_revision"], after["row_revision"])
        val entry = after.getValue("entry").jsonObject; assertEquals(JsonPrimitive(""), entry.getValue("fields").jsonObject["note"]); assertEquals(JsonPrimitive("显示账号"), entry.getValue("fields").jsonObject["counterparty_account"])
        assertTrue(entry.getValue("revision").jsonPrimitive.long > before.getValue("entry").jsonObject.getValue("revision").jsonPrimitive.long)
    }
    @Test fun hardKeyLookupAndDiscoveryExcludeManualOrigins() = cases("manual_discovery") { f ->
        f.withoutDiscoveryPlanning { c ->
            val source = CashSourceRows.sourceBatchRows(f.files("default")).rows.values.single()
            val operation = mapOf("values" to mapOf("source_type" to "alipay", "record_type" to "consumption", "amount" to "-12.5",
                "occurred_at" to source.fields.getValue("occurred_at"), "components" to listOf(mapOf("account_id" to "101"))),
                "source_explanation" to mapOf("txn_id" to source.providerId), "source_scope_key" to source.accountScopeKey, "row_refs" to emptyList<String>())
            assertFalse(1003L in CashImportCandidateRepository(c).candidateIds(listOf(operation)))
            assertTrue(c.sql.fetchExists(CASH_SOURCE_BINDINGS, CASH_SOURCE_BINDINGS.TRANSACTION_ID.eq(1003L)
                .and(CASH_SOURCE_BINDINGS.WORKSPACE_ID.eq(f.workspace)).and(CASH_SOURCE_BINDINGS.SOURCE_SCOPE_KEY.eq(source.accountScopeKey))))
            val prepared = CashImportPreviewPreparation(c).prepareOperations(emptyList(), additionalSeedIds = setOf(1003L))
            val snapshot = CashImportPreviewSnapshotBuilder(c).build(prepared, emptyList())
            val seedIds = prepared.context.components.map { it.componentId.toString() }.toSet()
            assertTrue(seedIds.isNotEmpty())
            assertEquals("manual", snapshot.values.getValue("1003")["record_origin"])
            assertEquals(emptyList(), CashImportRelationDiscovery(c).discover(snapshot, seedIds, prepared.accounts))
        }
    }
    @Test fun cashInvestmentTransferKeepsEntryAndPreventsSingleSideFinanceEdit() = cases("investment_transfer") { f ->
        val entry = f.entry(); assertEquals(JsonPrimitive(false), entry["supports_transfer_edit"])
        f.rejected { f.preview(patch = buildJsonObject { put("amount", "-70") }) }
    }
    @Test fun splitOnlyRequiresActuallyChangedManualValues() = cases("split_hint") { f ->
        f.write { c -> val entry = c.entries.current(1101L)!!; c.entries.applyEdit(1101L, mapOf("note" to f.params.getValue("note").jsonPrimitive.content), entry.revision) }
        val opened = f.preview(intent = "maintain_relations"); val group = f.groups(opened).single(); val diffs = group["split_field_diffs"]?.jsonArray ?: JsonArray(emptyList())
        val required = f.params.getValue("required").jsonPrimitive.boolean; assertEquals(required, diffs.isNotEmpty())
        if (required) { assertEquals(setOf(JsonPrimitive("note")), diffs.map { it.jsonObject.getValue("field") }.toSet()); assertTrue(diffs.all { it.jsonObject["current"] == f.params["note"] }) }
        else assertEquals(JsonPrimitive(true), f.choose(opened, choice(group, "remove"))["can_commit"])
    }
    @Test fun deletingPrecheckedCandidateInvalidatesOldPlanBeforeAnyWrites() = cases("candidate_deleted") { f ->
        val first = f.preview(intent = "maintain_relations"); val group = f.groups(first).single()
        val candidate = group.getValue("candidates").jsonArray.map { it.jsonObject }.first { value ->
            value.getValue("member_refs").jsonArray.map { it.jsonObject.getValue("transaction_id").jsonPrimitive.content }.toSet() == setOf("1101", "1102") }
        val selected = f.choose(first, choice(group, "accept", buildJsonObject { put("candidate_id", candidate.getValue("candidate_id")) }))
        CashDeletionService(f.database, now = { at }).deleteRecord(token, "1101")
        f.rejected("relation_context_changed") { f.commit(selected) }
    }
}
