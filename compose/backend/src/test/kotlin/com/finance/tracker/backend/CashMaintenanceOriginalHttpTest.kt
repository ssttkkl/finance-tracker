package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.backend.persistence.generated.DefaultSchema
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashMaintenanceOriginalHttpTest {
    private fun names(label: String): Set<String> {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_MAINTENANCE_HTTP_CONTRACT_DIR")))
        return Json.parseToJsonElement(root.resolve("scenarios.json").readText()).jsonObject
            .filterValues { it.jsonObject["label"] == JsonPrimitive(label) }.keys.also { assertTrue(it.isNotEmpty(), label) }
    }
    private class Store(clock: () -> Instant) : ParsedImportStore(1800, clock) {
        val entries = linkedMapOf<String, ParsedImportObject>()
        override fun readRaw(id: String) = entries[id] ?: throw ParsedImportNotFound()
        override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) {
            if (entries[id]?.etag != expected) throw ParsedImportRevisionConflict()
            entries[id] = ParsedImportObject(content.copyOf(), digest(content))
        }
        override fun deleteRaw(id: String) { entries.remove(id) }
        override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean) = ParsedImportSweep(0, 0, null)
    }
    private class Fixture(name: String) {
        private val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_MAINTENANCE_HTTP_CONTRACT_DIR"))).resolve(name)
        private val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        val case = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject
        val id = case.getValue("transaction_id").jsonPrimitive.content
        val path = "/api/v1/cash-records/$id"
        var now = Instant.parse("2026-10-22T00:00:00Z")
        fun database(decorate: (Connection) -> Connection = { it }) = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), decorate)
        val database = database()
        val store = Store { now }
        fun service(db: Database = database, step: (String) -> Unit = {}) = MutationPlanService(db, now = { now }, staging = store, afterCommitStep = step)
        val service = service()
        val base = buildJsonObject { put("intent", "edit_record"); put("transaction_id", id); put("expected_revision", case.getValue("expected_revision")) }
        val params get() = case.getValue("metadata").jsonObject.getValue("params").jsonObject
        fun role(value: String, other: Boolean = false) = database.write { connection -> connection.jooq(database.sqlite).update(WORKSPACE_MEMBERSHIPS)
            .set(WORKSPACE_MEMBERSHIPS.ROLE, value).where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(config.getValue("workspace").jsonPrimitive.content))
            .and(if (other) WORKSPACE_MEMBERSHIPS.USER_ID.ne(config.getValue("user").jsonPrimitive.content) else WORKSPACE_MEMBERSHIPS.USER_ID.eq(config.getValue("user").jsonPrimitive.content)).execute() }
        fun state() = database.read { connection ->
            val tables = DefaultSchema.DEFAULT_SCHEMA.tables; assertEquals(31, tables.size)
            tables.associate { table -> table.name to connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                    buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
                }
            } }
        }
    }
    private class Faults {
        val rollback = AtomicBoolean(); val loseResponse = AtomicBoolean(); val failReplay = AtomicBoolean()
        val receiptReached = AtomicInteger(); val commitsReached = AtomicInteger(); val replaysReached = AtomicInteger()
        fun afterStep(step: String) {
            if (step == "receipt" && rollback.get()) { receiptReached.incrementAndGet(); error("private SQL password /tmp/private.csv") }
        }
        fun decorate(connection: Connection): Connection {
            fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
            return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "commit" && loseResponse.compareAndSet(true, false)) {
                    invoke(connection, method, args); commitsReached.incrementAndGet(); throw java.sql.SQLException("private lost response")
                }
                val value = invoke(connection, method, args)
                if (method.name == "prepareStatement" && value is PreparedStatement) {
                    val sql = (args?.firstOrNull() as? String).orEmpty().lowercase()
                    Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, operation, values ->
                        if (operation.name.startsWith("execute") && failReplay.get() && sql.trimStart().startsWith("select") &&
                            "cash_mutation_receipts" in sql && "idempotency_key" in sql) {
                            replaysReached.incrementAndGet(); error("private replay query")
                        }
                        invoke(value, operation, values)
                    }
                } else if (method.name == "createStatement" && value is Statement) {
                    Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(Statement::class.java)) { _, operation, values ->
                        if (operation.name == "execute" && (values?.firstOrNull() as? String)?.trim()?.equals("COMMIT", ignoreCase = true) == true &&
                            loseResponse.compareAndSet(true, false)) {
                            invoke(value, operation, values); commitsReached.incrementAndGet(); throw java.sql.SQLException("private lost response")
                        }
                        invoke(value, operation, values)
                    }
                } else value
            } as Connection
        }
    }
    private val api = "/api/v1/cash-mutations"
    private fun patch(note: String) = buildJsonObject { put("note", note) }
    private suspend fun HttpResponse.checked(status: Int = 200): JsonObject {
        val body = bodyAsText(); assertEquals(status, this.status.value, body)
        return Json.parseToJsonElement(body).jsonObject
    }
    private suspend fun HttpClient.read(path: String, actor: String = "original-import-owner") = get(path) { bearerAuth(actor) }
    private suspend fun HttpClient.send(path: String, body: JsonObject, key: String? = null, draft: Boolean = false, actor: String = "original-import-owner"): HttpResponse = request(path) {
        method = if (draft) HttpMethod.Patch else HttpMethod.Post
        bearerAuth(actor); contentType(ContentType.Application.Json)
        if (key != null) header("Idempotency-Key", key)
        setBody(body.toString())
    }
    private suspend fun HttpClient.preview(f: Fixture, changes: JsonObject) = send("$api/plans", JsonObject(f.base + changes))
    private suspend fun HttpClient.commit(plan: JsonObject, key: String = "synthetic-maintenance-confirm", actor: String = "original-import-owner") = send(
        "$api/plans/${plan.getValue("plan_id").jsonPrimitive.content}/commit",
        buildJsonObject { put("expected_plan_revision", plan.getValue("plan_revision")) }, key, actor = actor)
    private suspend fun HttpClient.resolve(plan: JsonObject) = send("$api/resolve", buildJsonObject {
        put("plan_id", plan.getValue("plan_id")); put("idempotency_key", "synthetic-maintenance-confirm")
    })
    private fun planPath(plan: JsonObject) = "$api/plans/${plan.getValue("plan_id").jsonPrimitive.content}"
    private fun receiptPath(receipt: JsonObject) = "$api/receipts/${receipt.getValue("receipt_id").jsonPrimitive.content}"
    private fun summary(value: JsonObject, name: String) = value.getValue("summary").jsonObject.getValue(name)

    @Test fun draftSaveReplaysReceiptAndReturnsFreshExplicitCurrentDetailWithoutContext() = testApplication {
        val f = Fixture("draft"); application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service) }
        val before = client.read(f.path).checked(); assertEquals(JsonPrimitive("draft"), before["state"])
        val body = buildJsonObject { put("expected_revision", before.getValue("row_revision")); put("patch", f.case.getValue("empty_note_patch")) }
        val response = client.send(f.path + "/draft", body, "draft-save-http", draft = true); val saved = response.checked()
        val receipt = saved.getValue("receipt").jsonObject; assertEquals(JsonPrimitive(true), receipt["committed"])
        assertEquals(JsonPrimitive("save_draft"), receipt["operation_kind"])
        val current = saved.getValue("current_record").jsonObject
        assertEquals(JsonPrimitive("available"), saved["current_record_status"]); assertEquals(JsonPrimitive("draft"), current["state"])
        assertEquals(JsonPrimitive(""), current.getValue("fields").jsonObject["note"]); assertEquals(JsonPrimitive(f.id), current["transaction_id"])
        assertTrue(current.getValue("row_revision").jsonPrimitive.long > before.getValue("row_revision").jsonPrimitive.long)
        assertEquals(JsonPrimitive("pending"), current["confirmation_status"])
        f.database.write { connection -> connection.jooq(f.database.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.NOTE, "后来的当前备注")
            .set(CASH_TRANSACTIONS.ROW_REVISION, CASH_TRANSACTIONS.ROW_REVISION.plus(1L)).where(CASH_TRANSACTIONS.ID.eq(f.id.toLong())).execute() }
        val replay = client.send(f.path + "/draft", body, "draft-save-http", draft = true).checked()
        assertEquals(receipt, replay["receipt"]); assertEquals(JsonPrimitive("后来的当前备注"), replay.getValue("current_record").jsonObject.getValue("fields").jsonObject["note"])
        assertTrue(f.store.entries.isEmpty())
        for (secret in listOf("request_digest", "manual_overrides")) assertFalse(secret in response.bodyAsText())
    }
    @Test fun finalizeUsesOnePrivatePlanAndRecoversPermanentReceiptAfterCleanup() = testApplication {
        val f = Fixture("finalize"); application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service) }
        val before = client.read(f.path).checked()
        val response = client.preview(f, buildJsonObject { put("intent", "finalize_draft"); put("patch", f.case.getValue("complete_patch")) })
        val plan = response.checked(); assertEquals(JsonPrimitive(1), summary(plan, "finalize_draft")); assertEquals(JsonPrimitive(1), plan["plan_revision"])
        assertEquals(JsonPrimitive(true), plan["can_commit"]); assertEquals(before, client.read(f.path).checked())
        val contextId = plan.getValue("edit_context_id").jsonPrimitive.content
        val staged = ParsedImportCodec().decode(contextId, f.store.entries.getValue(contextId).content)
        assertEquals("mutation", staged.workflowKind); assertTrue(staged.files.isEmpty())
        assertEquals(plan, client.read(planPath(plan)).checked()); assertEquals(plan, client.read("$api/edit-contexts/$contextId/plan").checked())
        val item = client.read(planPath(plan) + "/items?limit=1").checked().getValue("items").jsonArray.first().jsonObject
        val detailResponse = client.read(planPath(plan) + "/items/" + item.getValue("preview_item_id").jsonPrimitive.content)
        val detail = detailResponse.checked(); assertEquals(JsonPrimitive("posted"), detail.getValue("members").jsonArray.first().jsonObject.getValue("fields").jsonObject["state"])
        for (secret in listOf("dependencies", "operations", "fields_ref", "context_revision", "source_scope_key")) {
            assertFalse(secret in response.bodyAsText()); assertFalse(secret in detailResponse.bodyAsText())
        }
        val receipt = client.commit(plan).checked(); assertEquals(JsonPrimitive(1), summary(receipt, "finalized_draft")); assertEquals(JsonPrimitive(f.id), receipt["retained_transaction_id"])
        assertEquals(JsonPrimitive("posted"), client.read(f.path).checked()["state"]); assertEquals(receipt, client.read(receiptPath(receipt)).checked())
        val rows = client.read(receiptPath(receipt) + "/rows").checked()
        assertEquals(JsonPrimitive(f.id), rows.getValue("items").jsonArray.first().jsonObject["transaction_id"])
        assertEquals(1, rows.getValue("projection_refs").jsonArray.size)
        f.store.entries.clear(); assertEquals(receipt, client.commit(plan).checked())
        val consumed = client.read("$api/edit-contexts/$contextId/plan").checked(409); assertEquals(receipt["receipt_id"], consumed["receipt_id"])
        client.commit(plan, "different-maintenance-key").checked(409)
        assertEquals(buildJsonObject { put("status", "committed"); put("receipt", receipt) }, client.resolve(plan).checked())
    }
    @Test fun repreviewReplacesCompleteInputWithoutRenewingContext() = testApplication {
        val f = Fixture("repreview"); application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service) }
        val first = client.preview(f, buildJsonObject { put("patch", patch("第一版")) }).checked(); assertTrue("plan_id" in first)
        val next = client.preview(f, buildJsonObject {
            put("patch", buildJsonObject {}); put("edit_context_id", first.getValue("edit_context_id"))
            put("previous_plan_id", first.getValue("plan_id")); put("expected_plan_revision", first.getValue("plan_revision"))
        }).checked()
        assertEquals(JsonPrimitive(2), next["plan_revision"]); assertEquals(first["expires_at"], next["expires_at"])
        assertNotEquals(JsonPrimitive("第一版"), next.getValue("items").jsonArray.first().jsonObject.getValue("display").jsonObject["note"])
        client.commit(first).checked(409); client.commit(next).checked()
    }
    @Test fun currentSourceIssueAndPublicPatchShareOneHttpConfirmation() = testApplication {
        val f = Fixture("issue"); application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service) }
        val before = client.read(f.path).checked(); val issue = before.getValue("issues").jsonArray.first().jsonObject
        val choice = f.case.getValue("locals").jsonObject.getValue("choice").jsonObject
        assertEquals(choice["expected_issue_revision"], issue["revision"]); assertTrue(JsonPrimitive("apply_source") in issue.getValue("allowed_resolutions").jsonArray)
        assertEquals(buildJsonObject { put("备注", "来源原文") }, issue["pending_source_payload"])
        val plan = client.preview(f, buildJsonObject { put("intent", "resolve_issue"); put("issue_resolutions", JsonArray(listOf(choice))); put("patch", patch("")) }).checked()
        assertEquals(JsonPrimitive(1), summary(client.commit(plan).checked(), "resolved_issues"))
        val after = client.read(f.path).checked(); assertEquals(JsonPrimitive(""), after.getValue("entry").jsonObject.getValue("fields").jsonObject["note"])
        assertEquals(JsonPrimitive("待采用的对方"), after.getValue("fields").jsonObject["counterparty"])
        assertEquals(JsonPrimitive("来源原文"), after.getValue("fields").jsonObject["note"])
        assertEquals(JsonArray(emptyList()), after["issues"]); assertEquals(JsonPrimitive("confirmed"), after["confirmation_status"])
    }
    @Test fun jointCurrentRelationIsOneHttpTaskAndKeepsZeroProjection() = testApplication {
        val f = Fixture("joint"); application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service) }
        val first = client.preview(f, buildJsonObject { put("intent", "maintain_relations") }).checked()
        val groups = linkedMapOf<String, JsonObject>()
        for (raw in first.getValue("items").jsonArray) {
            val item = raw.jsonObject; val detail = client.read(planPath(first) + "/items/" + item.getValue("preview_item_id").jsonPrimitive.content).checked()
            for (value in detail.getValue("relation_choices").jsonArray) {
                val group = value.jsonObject
                if (group["kind"] == JsonPrimitive("payment_mirror")) groups[group.getValue("suggestion_id").jsonPrimitive.content] = group
            }
        }
        val group = groups.values.single()
        val next = client.preview(f, buildJsonObject {
            put("intent", "maintain_relations"); put("edit_context_id", first.getValue("edit_context_id")); put("previous_plan_id", first.getValue("plan_id"))
            put("expected_plan_revision", first.getValue("plan_revision")); put("relation_choices", JsonArray(listOf(buildJsonObject { put("suggestion_id", group.getValue("suggestion_id")); put("action", "accept") })))
        }).checked()
        assertEquals(JsonPrimitive(1), summary(client.commit(next).checked(), "relations_confirmed"))
        val records = (1101..1104).map { client.read("/api/v1/cash-records/$it").checked() }
        val ids = records.map { it.getValue("projection_ref").jsonObject.getValue("projection_id") }.toSet(); assertEquals(1, ids.size)
        assertTrue(records.all { it["confirmation_status"] == JsonPrimitive("confirmed") })
        val projected = client.read("/api/v1/cash-projections").checked().getValue("items").jsonArray
        assertTrue(projected.any { it.jsonObject["projection_id"] in ids && BigDecimal(it.jsonObject.getValue("amount").jsonPrimitive.content).signum() == 0 })
    }
    @Test fun identityMergeReturnsRetainedTargetAndOriginalResultReference() = testApplication {
        val f = Fixture("merge"); application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service) }
        val target = f.case.getValue("locals").jsonObject.getValue("target")
        val detail = client.read(f.path).checked(); val issue = detail.getValue("issues").jsonArray.map { it.jsonObject }.single { it["code"] == JsonPrimitive("source_identity_ambiguous") }
        assertTrue(issue.getValue("candidates").jsonArray.any { it.jsonObject["transaction_id"] == target })
        val plan = client.preview(f, buildJsonObject { put("intent", "resolve_issue"); put("issue_resolutions", JsonArray(listOf(f.case.getValue("issue_choice")))) }).checked()
        val receipt = client.commit(plan).checked(); assertEquals(target, receipt["retained_transaction_id"]); assertEquals(JsonPrimitive(1), summary(receipt, "merged_draft"))
        client.read(f.path).checked(404)
        val row = client.read(receiptPath(receipt) + "/rows").checked().getValue("items").jsonArray.first().jsonObject
        assertEquals(JsonPrimitive(f.id), row["item_ref"]); assertEquals(target, row["transaction_id"])
    }
    @Test fun ordinaryDraftPlanKeepsDraftAndResolvesWithoutOriginalContext() = testApplication {
        val f = Fixture("ordinary"); application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service) }
        val plan = client.preview(f, buildJsonObject { put("intent", "save_draft"); put("patch", f.case.getValue("empty_note_patch")) }).checked()
        assertEquals(JsonArray(emptyList()), plan["additional_effects"]); assertEquals(JsonPrimitive(1), summary(plan, "update_draft"))
        assertEquals(JsonArray(emptyList()), client.read(planPath(plan)).checked()["additional_effects"])
        val receipt = client.commit(plan).checked(); assertEquals(JsonPrimitive("draft"), client.read(f.path).checked()["state"])
        f.store.entries.clear(); assertEquals(buildJsonObject { put("status", "committed"); put("receipt", receipt) }, client.resolve(plan).checked())
    }
    @Test fun rollbackAndLostResponseAreSafeForBothActualMaintenanceWrites() {
        for (name in names("fault")) testApplication {
            val f = Fixture(name); val faults = Faults(); val db = f.database(faults::decorate)
            application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service(db, faults::afterStep),
                cashDraftSave = CashDraftSaveService(db, now = { f.now }, afterStep = faults::afterStep)) }
            val before = client.read(f.path).checked(); val draft = f.params.getValue("operation") == JsonPrimitive("draft")
            val plan = if (draft) null else client.preview(f, buildJsonObject { put("patch", patch("已保存")) }).checked()
            suspend fun perform() = if (draft) client.send(f.path + "/draft", buildJsonObject {
                put("expected_revision", before.getValue("row_revision")); put("patch", patch("已保存"))
            }, "fault-http", draft = true) else client.commit(plan!!, "fault-http")
            val initial = f.state(); faults.rollback.set(true)
            val rejected = perform(); val failure = rejected.checked(503)
            assertEquals(JsonPrimitive("not_committed"), failure["commit_state"]); assertFalse("private" in rejected.bodyAsText())
            assertEquals(1, faults.receiptReached.get()); assertEquals(before, client.read(f.path).checked()); assertEquals(initial, f.state())
            faults.rollback.set(false); faults.loseResponse.set(true)
            val lost = perform(); assertEquals(JsonPrimitive("unknown"), lost.checked(503)["commit_state"]); assertFalse("private" in lost.bodyAsText())
            assertEquals(1, faults.commitsReached.get()); val committed = f.state()
            assertEquals(initial.getValue("cash_mutation_receipts").size + 1, committed.getValue("cash_mutation_receipts").size)
            val replay = perform().checked(); val receipt = if (draft) replay.getValue("receipt").jsonObject else replay
            assertEquals(receipt, client.read("$api/receipts/by-key/fault-http").checked())
            assertEquals(JsonPrimitive("已保存"), client.read(f.path).checked().getValue("entry").jsonObject.getValue("fields").jsonObject["note"])
            assertEquals(committed, f.state())
        }
    }
    @Test fun existingKeyReplayQueryFailureKeepsUnknownAndDoesNotWriteAgain() {
        for (name in names("replay_fault")) testApplication {
            val f = Fixture(name); val faults = Faults(); val db = f.database(faults::decorate)
            application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service(db), cashDraftSave = CashDraftSaveService(db, now = { f.now })) }
            val before = client.read(f.path).checked(); val draft = f.params.getValue("operation") == JsonPrimitive("draft")
            val plan = if (draft) null else client.preview(f, buildJsonObject { put("patch", patch("已保存")) }).checked()
            suspend fun perform() = if (draft) client.send(f.path + "/draft", buildJsonObject {
                put("expected_revision", before.getValue("row_revision")); put("patch", patch("已保存"))
            }, "replay-http", draft = true) else client.commit(plan!!, "replay-http")
            perform().checked(); val saved = f.state(); faults.failReplay.set(true)
            val response = perform(); assertEquals(JsonPrimitive("unknown"), response.checked(503)["commit_state"])
            assertFalse("private" in response.bodyAsText()); assertEquals(1, faults.replaysReached.get()); faults.failReplay.set(false)
            assertEquals(saved, f.state())
        }
    }
    @Test fun currentViewerIsReadOnlyAndExpiredPlansReturnGone() = testApplication {
        val f = Fixture("expiry"); application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service) }
        val plan = client.preview(f, buildJsonObject { put("patch", patch("允许的字段")) }).checked(); f.role("viewer")
        assertEquals(JsonArray(emptyList()), client.read(f.path).checked()["editable_fields"])
        val before = f.state(); client.commit(plan).checked(403); client.preview(f, buildJsonObject {}).checked(403); assertEquals(before, f.state())
        f.role("editor"); f.now = Instant.parse(plan.getValue("expires_at").jsonPrimitive.content).plusSeconds(1)
        client.read(planPath(plan)).checked(410); client.commit(plan).checked(410)
    }
    @Test fun invalidStructuresNeverPublishOrCreateTemporaryContext() {
        for (name in names("invalid")) testApplication {
            val f = Fixture(name); application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service) }
            val before = f.state(); val raw = client.preview(f, f.params.getValue("changes").jsonObject)
            assertEquals(422, raw.status.value, name)
            val response = raw.checked(422)
            assertEquals(JsonArray(emptyList()), response.getValue("error").jsonObject["field_errors"])
            assertTrue(f.store.entries.isEmpty()); assertEquals(before, f.state())
        }
    }
    @Test fun fixedFinancialConflictHasSafeFieldErrorAndNoChanges() = testApplication {
        val f = Fixture("fixed"); application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service) }
        val before = client.read(f.path).checked(); val state = f.state()
        val response = client.preview(f, buildJsonObject { put("patch", buildJsonObject { put("amount", "-999") }) })
        val error = response.checked(422).getValue("error").jsonObject
        assertEquals(JsonPrimitive("fixed_field_conflict"), error["code"])
        assertEquals(JsonPrimitive("amount"), error.getValue("field_errors").jsonArray.first().jsonObject["field"])
        assertFalse("-999" in response.bodyAsText()); assertEquals(before, client.read(f.path).checked()); assertTrue(f.store.entries.isEmpty()); assertEquals(state, f.state())
    }
    @Test fun otherEditorCannotReadOrCommitPrivatePlanAndOwnerCanStillCommit() = testApplication {
        val f = Fixture("private"); application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service) }
        val plan = client.preview(f, buildJsonObject { put("patch", patch("他人的未确认内容")) }).checked(); f.role("editor", other = true)
        client.read(f.path, "original-import-other").checked(); client.read(planPath(plan), "original-import-other").checked(404)
        val before = f.state(); val denied = client.commit(plan, actor = "original-import-other")
        assertEquals(JsonPrimitive(false), denied.checked(404).getValue("error").jsonObject["retryable"])
        assertFalse("他人的未确认内容" in denied.bodyAsText()); assertEquals(before, f.state()); client.commit(plan).checked()
    }
    @Test fun successfulDraftReceiptStaysSuccessfulWhenCurrentDetailReadFails() = testApplication {
        val f = Fixture("outcome"); val reached = AtomicInteger()
        application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service,
            cashDraftSave = CashDraftSaveService(f.database, now = { f.now }, beforeOutcomeRead = { reached.incrementAndGet(); error("private current read") })) }
        val before = client.read(f.path).checked(); val response = client.send(f.path + "/draft", buildJsonObject {
            put("expected_revision", before.getValue("row_revision")); put("patch", patch("已成功"))
        }, "current-read-http", draft = true)
        val result = response.checked(); assertEquals(1, reached.get()); assertEquals(JsonPrimitive(true), result.getValue("receipt").jsonObject["committed"])
        assertEquals(JsonPrimitive("unavailable"), result["current_record_status"]); assertEquals(JsonNull, result["current_record"])
        assertFalse("private" in response.bodyAsText()); assertEquals(JsonPrimitive("已成功"), client.read(f.path).checked().getValue("fields").jsonObject["note"])
    }
    @Test fun missingRecordMissingKeyWrongStateAndStaleRevisionHaveDistinctSafeErrors() = testApplication {
        val f = Fixture("state"); application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service) }
        val before = client.read(f.path).checked(); val state = f.state()
        assertEquals(JsonPrimitive("record_not_found"), client.read("/api/v1/cash-records/9223372036854775807").checked(404).getValue("error").jsonObject["code"])
        val body = buildJsonObject { put("expected_revision", before.getValue("row_revision")); put("patch", patch("不应写入")) }
        client.send(f.path + "/draft", body, draft = true).checked(422)
        assertEquals(JsonPrimitive("draft_required"), client.send(f.path + "/draft", body, "posted-is-not-draft", draft = true).checked(409).getValue("error").jsonObject["code"])
        val stale = client.preview(f, buildJsonObject { put("expected_revision", before.getValue("row_revision").jsonPrimitive.long + 1) }).checked(409)
        assertEquals(JsonPrimitive("record_changed"), stale.getValue("error").jsonObject["code"]); assertEquals(before, client.read(f.path).checked()); assertEquals(state, f.state())
    }
    @Test fun incompleteDraftStaysEditableAndDoesNotPublishFinalizePlan() = testApplication {
        val f = Fixture("incomplete"); application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service) }
        val before = client.read(f.path).checked(); val state = f.state()
        val error = client.preview(f, buildJsonObject { put("intent", "finalize_draft") }).checked(422).getValue("error").jsonObject
        assertEquals(JsonPrimitive("account_missing"), error["code"]); assertTrue("账户" in error.getValue("message").jsonPrimitive.content)
        assertEquals(before, client.read(f.path).checked()); assertTrue(f.store.entries.isEmpty()); assertEquals(state, f.state())
    }
    @Test fun changedAccountAfterFinalizePreviewIsAKnownHttpRefusal() = testApplication {
        val f = Fixture("account"); application { financeModule(f.database, clock = { f.now }, mutationPlans = f.service) }
        val before = client.read(f.path).checked()
        val plan = client.preview(f, buildJsonObject { put("intent", "finalize_draft"); put("patch", f.case.getValue("complete_patch")) }).checked()
        f.database.write { connection -> connection.jooq(f.database.sqlite).update(ACCOUNTS).set(ACCOUNTS.ACTIVE, false)
            .set(ACCOUNTS.REVISION, ACCOUNTS.REVISION.plus(1L)).where(ACCOUNTS.ID.eq(101L)).execute() }
        val response = client.commit(plan); assertTrue(response.status.value in setOf(409, 422), response.bodyAsText())
        assertEquals(JsonPrimitive("not_committed"), Json.parseToJsonElement(response.bodyAsText()).jsonObject["commit_state"])
        assertEquals(before, client.read(f.path).checked())
    }
}
