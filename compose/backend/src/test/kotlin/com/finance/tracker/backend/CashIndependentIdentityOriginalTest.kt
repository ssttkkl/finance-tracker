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

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashIndependentIdentityOriginalTest {
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private fun root(readOnly: Boolean = false) = Path.of(requireNotNull(System.getenv(
        if (readOnly) "FT_KOTLIN_INDEPENDENT_IDENTITY_GOLDEN_DIR" else "FT_KOTLIN_INDEPENDENT_IDENTITY_CONTRACT_DIR")))
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
        fun alterBindings(change: String) = database.write { connection ->
            val context = CashSqlContext(connection, workspace, database.sqlite, now = { at }); val sql = context.sql; val b = CASH_SOURCE_BINDINGS
            val all = sql.selectFrom(b).where(b.WORKSPACE_ID.eq(workspace)).and(b.TRANSACTION_ID.eq(id.toLong())).orderBy(b.ID).fetch()
            val row = if (change == "automatic_instance") all.last() else all.first()
            when (change) {
                "add" -> {
                    val data = Json.parseToJsonElement(directory.resolve("input.json").readText()).jsonObject
                    val files = ParsedImportCodec().decode(data.getValue("session_id").jsonPrimitive.content, data.getValue("session").toString().encodeToByteArray()).files
                    CashSourceWriteRepository(context).saveOccurrence(id, CashSourceRows.sourceBatchRows(files).rows.values.single().copy(fileDigest = "c".repeat(64)))
                }
                "delete" -> sql.deleteFrom(b).where(b.ID.eq(row[b.ID])).execute()
                "coverage" -> sql.update(b).set(b.COVERAGE, JsonObject(row[b.COVERAGE]!!.jsonObject + ("evidence" to JsonPrimitive("changed-certificate"))))
                    .set(b.REVISION, row[b.REVISION]!! + 1).where(b.ID.eq(row[b.ID])).execute()
                "parser" -> sql.update(b).set(b.STRATEGY_VERSION, "later-parser").set(b.REVISION, row[b.REVISION]!! + 1).where(b.ID.eq(row[b.ID])).execute()
                "transfer" -> sql.update(b).set(b.TRANSACTION_ID, 1003L).set(b.REVISION, row[b.REVISION]!! + 1).where(b.ID.eq(row[b.ID])).execute()
                "instance", "automatic_instance", "other_owner" -> {
                    if (change != "other_owner") sql.deleteFrom(b).where(b.ID.eq(row[b.ID])).execute()
                    val fields = b.fields().filter { change == "instance" || it != b.ID }
                    sql.insertInto(b).columns(fields).values(fields.map { when {
                        it == b.ID -> row[b.ID]!! + 100000L
                        change != "instance" && it in listOf(b.CREATED_AT, b.UPDATED_AT) -> at
                        change == "other_owner" && it == b.TRANSACTION_ID -> 1003L
                        change == "other_owner" && it == b.IDENTITY_KEY -> "e".repeat(64)
                        else -> row[it]
                    } }).execute()
                }
                else -> error(change)
            }
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

    @Test fun allOriginalIndependentPlansMatchTheCompleteReadOnlyBusinessGraph() {
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

    @Test fun independentPreservesEntityBindingsAndPermanentReplayAfterContextRemoval() {
        val captures = mutableListOf<JsonObject>()
        for (name in names("independent_confirmation_preserves_entity_bindings_and_replays_without_context")) testApplication {
            val f = Fixture(name); val before = f.detail(); val bindings = f.bindings(); val balance = f.balances(); val initial = f.state()
            val finalize = f.params.getValue("finalize").jsonPrimitive.boolean
            assertTrue(JsonPrimitive("create_independent") in f.issues().first().jsonObject.getValue("allowed_resolutions").jsonArray)
            application { financeModule(f.database, mutationPlans = f.service) }
            suspend fun current(): JsonObject {
                val response = client.get("/api/v1/cash-records/${f.id}") { header(HttpHeaders.Authorization, "Bearer ${f.token}") }
                val body = response.bodyAsText(); assertEquals(200, response.status.value, body); return Json.parseToJsonElement(body).jsonObject
            }
            val publicBefore = current()
            val response = client.post("/api/v1/cash-mutations/plans") {
                header(HttpHeaders.Authorization, "Bearer ${f.token}"); contentType(ContentType.Application.Json); setBody(f.input.toString())
            }
            val body = response.bodyAsText(); assertEquals(200, response.status.value, body)
            val plan = Json.parseToJsonElement(body).jsonObject
            assertEquals(1, summary(plan, "create_independent")); assertEquals(1, summary(plan, "resolve_issue"))
            val itemId = plan.getValue("items").jsonArray.single().jsonObject.getValue("preview_item_id").jsonPrimitive.content
            val itemResponse = client.get("/api/v1/cash-mutations/plans/${plan.getValue("plan_id").jsonPrimitive.content}/items/$itemId") {
                header(HttpHeaders.Authorization, "Bearer ${f.token}")
            }
            val itemBody = itemResponse.bodyAsText(); assertEquals(200, itemResponse.status.value, itemBody)
            assertEquals(before, f.detail()); assertEquals(bindings, f.bindings()); assertEquals(initial, f.state())
            val committed = client.post("/api/v1/cash-mutations/plans/${plan.getValue("plan_id").jsonPrimitive.content}/commit") {
                header(HttpHeaders.Authorization, "Bearer ${f.token}"); header("Idempotency-Key", "independent-confirm")
                contentType(ContentType.Application.Json); setBody(buildJsonObject { put("expected_plan_revision", plan.getValue("plan_revision")) }.toString())
            }
            val receiptBody = committed.bodyAsText(); assertEquals(200, committed.status.value, receiptBody)
            val receipt = Json.parseToJsonElement(receiptBody).jsonObject
            assertEquals(JsonPrimitive("completed"), receipt["status"]); assertEquals(1, summary(receipt, "resolved_issues"))
            assertEquals(JsonPrimitive(f.id), f.detail()["transaction_id"]); assertEquals(JsonPrimitive(if (finalize) "posted" else "draft"), f.detail()["state"])
            assertTrue(f.issues().isEmpty()); assertEquals(bindings, f.bindings())
            if (!finalize) { assertEquals(before["row_revision"], f.detail()["row_revision"]); assertEquals(before["fields"], f.detail()["fields"]); assertEquals(balance, f.balances()) }
            captures += buildJsonObject {
                put("request", f.input); put("before", publicBefore); put("plan", plan); put("item", Json.parseToJsonElement(itemBody))
                put("receipt", receipt); put("after", current()); put("key", "independent-confirm"); put("finalize", finalize)
            }
            f.store.entries.clear()
            assertEquals(receipt, f.commit(plan, "independent-confirm"))
            assertEquals(receipt, f.service.resolve(f.token, plan.getValue("plan_id").jsonPrimitive.content, "independent-confirm")["receipt"])
            val repeated = f.reimport(); assertEquals(JsonPrimitive(f.id), f.resultRows(repeated).single().jsonObject["transaction_id"])
            assertEquals(JsonPrimitive("posted"), f.detail()["state"]); assertTrue(f.issues().isEmpty())
        }
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { directory ->
            Files.createDirectories(Path.of(directory)); Files.writeString(Path.of(directory).resolve("independent-identity-response.json"), JsonArray(captures).toString())
        }
    }

    @Test fun multipleIdentityChoicesCountOneRecordAndClearOnlySelectedIssues() {
        val f = Fixture(names("multiple_identity_choices_count_one_record_and_only_clear_selected_issues").single())
        reject(f, "source_identity_ambiguous", f.case.getValue("rejected_input").jsonObject)
        val plan = f.plan(); assertEquals(1, summary(plan, "create_independent")); assertEquals(2, summary(plan, "resolve_issue"))
        val receipt = f.commit(plan); assertEquals(2, summary(receipt, "resolved_issues")); assertEquals(JsonPrimitive("draft"), f.detail()["state"])
    }

    @Test fun independentSharesSourceAdoptionExplicitClearAndFinalization() {
        val f = Fixture(names("independent_can_share_source_adoption_and_explicit_patch_before_finalization").single())
        val receipt = f.commit(f.plan()); assertEquals(1, summary(receipt, "finalized_draft")); assertEquals(2, summary(receipt, "resolved_issues"))
        assertEquals(JsonPrimitive("posted"), f.detail()["state"]); assertEquals(JsonPrimitive("采用的新名称"), f.fields()["counterparty"])
        assertEquals(JsonPrimitive(""), f.fields()["note"]); assertEquals(JsonPrimitive(true), f.raw().getValue("manual_overrides").jsonObject["note"])
    }

    @Test fun wechatKeepsVerifiedFileOrUnboundScopeAcrossIndependentAndFinalization() {
        for (name in names("wechat_scope_stays_current_during_independent_and_later_finalization")) {
            val f = Fixture(name); val before = f.bindings(); val plan = f.plan()
            val scope = f.frozen(plan).getValue("operations").jsonArray.single().jsonObject.getValue("source_scope_key").jsonPrimitive.content
            assertNotEquals(CashSourceScope.WECHAT_DEFAULT_SCOPE, scope)
            when (f.params.getValue("bindings").jsonPrimitive.content) {
                "custom" -> assertEquals("custom-verified-wechat", scope)
                "file" -> assertTrue(scope.startsWith("file:"))
                "none" -> assertTrue(scope.startsWith("unbound:"))
            }
            f.commit(plan)
            if (!f.params.getValue("same_plan").jsonPrimitive.boolean) {
                val input = JsonObject(f.input + mapOf("intent" to JsonPrimitive("finalize_draft"), "issue_resolutions" to JsonArray(emptyList()),
                    "expected_revision" to f.detail().getValue("row_revision")))
                val next = f.plan(input); assertEquals(JsonPrimitive(scope), f.frozen(next).getValue("operations").jsonArray.single().jsonObject["source_scope_key"])
                f.commit(next, "finalize-later")
            }
            assertEquals(JsonPrimitive("posted"), f.detail()["state"]); assertEquals(before, f.bindings())
        }
    }

    @Test fun everyBindingFactOrInstanceChangeInvalidatesOldPlanWithoutChangingParentRevision() {
        for (name in names("binding_changes_invalidate_independent_plan_without_parent_revision")) {
            val f = Fixture(name); val plan = f.plan(); val revision = f.detail().getValue("row_revision")
            f.alterBindings(f.params.getValue("change").jsonPrimitive.content); assertEquals(revision, f.detail()["row_revision"])
            val state = f.state(); val bindings = f.bindings()
            val failure = assertFailsWith<RuntimeException>(name) { f.rawCommit(plan) }
            when (failure) {
                is CashImportCommitFailure -> { assertEquals("not_committed", failure.commitState); assertIs<CashSourceBindingConflict>(failure.failure) }
                is MutationPlanFailure -> assertEquals("record_changed", failure.code)
                else -> throw failure
            }
            assertEquals(bindings, f.bindings()); assertNotNull(f.issue(f.selectedId)); assertEquals(plan, f.service.plan(f.token, plan.getValue("plan_id").jsonPrimitive.content))
            assertEquals(state, f.state())
        }
    }

    @Test fun activeOtherOwnerOfStableEvidenceBlocksIndependentWithoutFinancialWrites() {
        val f = Fixture(names("stable_evidence_already_owned_by_other_active_record_blocks_independent").single()); val plan = f.plan(); f.alterBindings("other_owner")
        assertTrue(f.issues().first().jsonObject.getValue("allowed_resolutions").jsonArray.isEmpty())
        val before = f.detail(); val bindings = f.bindings(); val state = f.state()
        val failure = assertFailsWith<CashImportCommitFailure> { f.rawCommit(plan) }
        assertEquals("not_committed", failure.commitState); assertEquals("active_source_binding", assertIs<CashSourceBindingConflict>(failure.failure).code)
        assertEquals(before, f.detail()); assertEquals(bindings, f.bindings()); assertNotNull(f.issue(f.selectedId)); assertEquals(state, f.state())
    }

    @Test fun conflictingCertifiedPagesOfferNoIndependentChoiceAndRejectWithoutWrites() = testApplication {
        val f = Fixture(names("conflicting_certified_page_bindings_do_not_offer_independent_confirmation").single())
        assertTrue(f.issues().first().jsonObject.getValue("allowed_resolutions").jsonArray.isEmpty())
        val before = f.bindings(); reject(f, "source_document_conflict"); assertEquals(before, f.bindings()); assertNotNull(f.issue(f.selectedId))
        val state = f.state(); application { financeModule(f.database, mutationPlans = f.service) }
        val response = client.post("/api/v1/cash-mutations/plans") {
            header(HttpHeaders.Authorization, "Bearer ${f.token}"); contentType(ContentType.Application.Json); setBody(f.input.toString())
        }
        val expected = f.case.getValue("public_error").jsonObject
        assertEquals(expected.getValue("status").jsonPrimitive.int, response.status.value)
        assertEquals(expected.getValue("body"), Json.parseToJsonElement(response.bodyAsText()))
        assertEquals(state, f.state()); assertEquals(0, f.store.writes)
    }

    @Test fun actualRecordProjectionAndReceiptFailuresRestoreIssuesCandidatesBindingsAndBalances() {
        for (name in names("independent_failures_restore_issue_candidates_bindings_and_balances")) {
            val f = Fixture(name); val plan = f.plan(); val before = f.detail(); val bindings = f.bindings(); val balance = f.balances(); val state = f.state()
            val step = when (f.params.getValue("failure").jsonPrimitive.content) { "row" -> "record"; else -> f.params.getValue("failure").jsonPrimitive.content }
            var reached = 0; val marker = IllegalStateException("synthetic failure after independent confirmation")
            val target = MutationPlanService(f.database, now = { at }, staging = f.store, afterCommitStep = { if (it == step) { reached++; throw marker } })
            val failure = assertFailsWith<CashImportCommitFailure> { f.rawCommit(plan, target = target) }
            assertEquals("not_committed", failure.commitState); assertSame(marker, failure.failure); assertEquals(1, reached)
            assertEquals(plan, f.service.plan(f.token, plan.getValue("plan_id").jsonPrimitive.content)); assertEquals(before, f.detail())
            assertEquals(bindings, f.bindings()); assertEquals(balance, f.balances()); assertEquals(state, f.state())
        }
    }

    @Test fun viewerHasNoIndependentChoiceAndCannotPublishOrCommit() {
        val f = Fixture(names("viewer_has_no_independent_action_and_cannot_publish_or_commit").single()); val plan = f.plan()
        f.database.write { connection -> connection.jooq(f.database.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer")
            .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(f.workspace)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(f.user)).execute() }
        val before = f.state(); assertTrue(f.issues().first().jsonObject.getValue("allowed_resolutions").jsonArray.isEmpty())
        assertFailsWith<AccessFailure> { f.plan() }; assertFailsWith<AccessFailure> { f.rawCommit(plan) }; assertEquals(before, f.state())
    }
}
