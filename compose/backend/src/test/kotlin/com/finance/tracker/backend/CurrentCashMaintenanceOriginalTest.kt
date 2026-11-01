package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CurrentCashMaintenanceOriginalTest {
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CURRENT_COMMIT_CONTRACT_DIR")))
    private fun names(label: String) = Json.parseToJsonElement(root().resolve("scenarios.json").readText()).jsonObject
        .filterValues { it.jsonObject["label"] == JsonPrimitive(label) }.keys.also { assertTrue(it.isNotEmpty(), label) }
    private inner class Fixture(val name: String) {
        private val directory = root().resolve(name)
        private val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        val case = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject
        val params = case.getValue("metadata").jsonObject.getValue("params").jsonObject
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val staging = InMemoryParsedImportStore(clock = { at })
        val service = MutationPlanService(database, now = { at }, staging = staging)
        fun instrument(db: Database = database, step: (String) -> Unit) = MutationPlanService(db, now = { at }, staging = staging, afterCommitStep = step)
        fun observedDatabase(observe: (String) -> Unit) = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")) { connection ->
            Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "prepareStatement") observe(args!![0] as String)
                try { method.invoke(connection, *(args ?: emptyArray())) }
                catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
            } as Connection
        }
        val token = "original-import-owner"
        fun detail(id: Int = 1101) = CashRecordDetailService(database, now = { at }).detail(token, id.toString())
        fun fields(id: Int = 1101) = detail(id).getValue("fields").jsonObject
        fun relations() = database.read { connection ->
            val context = CashSqlContext(connection, workspace, database.sqlite, now = { at })
            val ids = context.sql.select(TRANSACTION_RELATIONS.ID).from(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace))
                .orderBy(TRANSACTION_RELATIONS.ID).fetch(TRANSACTION_RELATIONS.ID)
            ids.map { CurrentCashRelationWriteRepository(context).get(it) }
        }
        fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table -> table.name to connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            }
        } } }
        fun preview(onFailure: (String) -> Unit = {}, onPreview: (Int, JsonObject) -> Unit = { _, _ -> }): JsonObject {
            val plans = mutableMapOf<String, JsonObject>()
            for ((index, step) in case.getValue("previews").jsonArray.withIndex()) {
                val source = step.jsonObject; var input = source.getValue("input").jsonObject
                input["previous_plan_id"]?.jsonPrimitive?.contentOrNull?.let { previousId ->
                    val previous = plans.getValue(previousId)
                    input = JsonObject(input + mapOf("previous_plan_id" to previous.getValue("plan_id"), "edit_context_id" to previous.getValue("edit_context_id"),
                        "expected_plan_revision" to previous.getValue("plan_revision")))
                }
                if (source["error"] != null) {
                    val before = snapshot()
                    val failure = assertFailsWith<RuntimeException>(name) { service.preview(token, input) }
                    assertEquals(source.getValue("error").jsonPrimitive.content, failureCode(failure), name)
                    assertEquals(before, snapshot(), name)
                    input["previous_plan_id"]?.jsonPrimitive?.contentOrNull?.let { id ->
                        val previous = plans.values.single { it["plan_id"] == JsonPrimitive(id) }
                        assertEquals(previous, service.plan(token, id), name)
                    }
                    onFailure(failureCode(failure))
                    continue
                }
                val plan = service.preview(token, input)
                plans[source.getValue("plan_id").jsonPrimitive.content] = plan
                onPreview(index, plan)
            }
            val target = case["plan_id"]?.jsonPrimitive?.contentOrNull ?: return JsonObject(emptyMap())
            return plans.getValue(target)
        }
        fun rawCommit(plan: JsonObject, target: MutationPlanService = service) = target.commit(token, plan.getValue("plan_id").jsonPrimitive.content,
            plan.getValue("plan_revision").jsonPrimitive.long, case.getValue("commit").jsonObject.getValue("idempotency_key").jsonPrimitive.content)
        fun commit(plan: JsonObject, target: MutationPlanService = service): JsonObject = try {
            rawCommit(plan, target)
        } catch (failure: CashImportCommitFailure) { throw AssertionError("$name: ${failure.commitState}", failure.failure) }
        fun applyChanges() = database.write { connection ->
            val context = CashSqlContext(connection, workspace, database.sqlite, now = { at })
            for (value in case.getValue("changes").jsonArray) {
                val change = value.jsonObject; val id = change.getValue("id").jsonPrimitive.long
                when (change.getValue("table").jsonPrimitive.content) {
                    "cash_relation_candidates" -> {
                        assertEquals(setOf("evidence"), change.getValue("after").jsonObject.keys)
                        assertEquals(1, context.sql.update(CASH_RELATION_CANDIDATES).set(CASH_RELATION_CANDIDATES.EVIDENCE,
                            change.getValue("after").jsonObject.getValue("evidence")).where(CASH_RELATION_CANDIDATES.ID.eq(id)).execute())
                    }
                    "cash_transactions" -> {
                        assertEquals(setOf("row_revision"), change.getValue("after").jsonObject.keys)
                        assertEquals(1, context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.ROW_REVISION,
                            change.getValue("after").jsonObject.getValue("row_revision").jsonPrimitive.long).where(CASH_TRANSACTIONS.ID.eq(id)).execute())
                    }
                    else -> error("Unexpected original dependency change: $change")
                }
            }
        }
    }
    private fun failureCode(failure: RuntimeException) = when (failure) {
        is MutationPlanFailure -> failure.code
        is MutationPlanFieldFailure -> failure.code
        is CashPolicyError -> failure.code
        is CashImportPlanError -> failure.code
        else -> throw failure
    }
    private fun summary(receipt: JsonObject, name: String) = receipt.getValue("summary").jsonObject.getValue(name).jsonPrimitive.long
    private fun projection(detail: JsonObject) = detail.getValue("projection_ref").jsonObject.getValue("projection_id")

    @Test fun unchangedPlansPreserveEveryDecisionProofParentRevisionAndPermanentReplay() {
        for (name in names("unchanged")) {
            val f = Fixture(name); val before = f.relations(); val initial = f.snapshot(); val revision = f.detail()["row_revision"]
            val plan = f.preview(); val receipt = f.commit(plan)
            assertEquals(JsonPrimitive(true), receipt["committed"]); assertEquals(JsonPrimitive("no_changes"), receipt["status"])
            assertEquals(before, f.relations(), name); assertEquals(initial["ledger_snapshots"], f.snapshot()["ledger_snapshots"])
            assertEquals(revision, f.detail()["row_revision"])
            for (key in listOf("relations_confirmed", "relations_deferred", "relations_ignored", "relations_removed", "relations_failed")) assertEquals(0, summary(receipt, key), name)
            assertEquals(receipt, f.commit(plan, MutationPlanService(f.database, now = { at })), name)
        }
    }
    @Test fun jointConfirmationKeepsOriginalIdentitiesAnchorsAndFullRefundInOneProjection() {
        for (name in names("joint")) {
            val f = Fixture(name); val before = f.relations(); val initial = f.snapshot(); val plan = f.preview(); val receipt = f.commit(plan)
            assertEquals(1, summary(receipt, "relations_confirmed")); assertEquals(0, summary(receipt, "relations_failed"))
            val after = f.relations(); assertEquals(before.map { it["id"] }, after.map { it["id"] })
            for ((old, new) in before.zip(after)) if (old["kind"] == "payment_mirror") {
                assertEquals("confirmed", new["state"]); assertEquals(true, new["active"])
                assertTrue((new["row_revision"] as Number).toLong() > (old["row_revision"] as Number).toLong())
                for (key in listOf("decision_group_id", "anchor_transaction_id", "anchor_component_id")) assertEquals(old[key], new[key], key)
                assertEquals("manual", new["decision_source"])
            } else assertEquals(old, new)
            val details = (1101..1104).map(f::detail)
            assertTrue(details.all { it["confirmation_status"] == JsonPrimitive("confirmed") }); assertEquals(1, details.map(::projection).toSet().size)
            assertEquals(initial["ledger_snapshots"], f.snapshot()["ledger_snapshots"]); assertEquals(receipt, f.commit(plan))
        }
    }
    @Test fun cleanupOfMixedGroupsPreservesInactiveMembersAndBalances() {
        for (name in names("inactive")) {
            val f = Fixture(name); val before = f.relations(); val balances = f.snapshot()["ledger_snapshots"]
            val receipt = f.commit(f.preview()); assertEquals(0, summary(receipt, "relations_failed"))
            for ((old, new) in before.zip(f.relations())) when {
                old["active"] == false -> assertEquals(old, new)
                f.params["action"] == JsonPrimitive("remove") -> { assertEquals(false, new["active"]); assertEquals(old["state"], new["state"]) }
                else -> { assertEquals(true, new["active"]); assertEquals(if (f.params["action"] == JsonPrimitive("defer")) "draft" else "ignored", new["state"]) }
            }
            assertEquals(balances, f.snapshot()["ledger_snapshots"])
        }
    }
    @Test fun notePatchConfirmsPrecheckedCandidateAndClearsCandidateProofs() {
        for (name in names("note")) {
            val f = Fixture(name); val receipt = f.commit(f.preview())
            assertEquals(1, summary(receipt, "updated_posted")); assertEquals(1, summary(receipt, "relations_confirmed"))
            assertEquals(JsonPrimitive("本次明确备注"), f.detail().getValue("entry").jsonObject.getValue("fields").jsonObject["note"])
            val row = f.relations().single(); assertEquals("confirmed", row["state"]); assertEquals(true, row["active"]); assertEquals(emptyList<Any>(), row["candidates"])
        }
    }
    @Test fun componentRemovalAndCandidateCleanupCommitTogether() {
        for (name in names("coupled")) {
            val f = Fixture(name); val receipt = f.commit(f.preview())
            assertEquals(1, summary(receipt, "saved_draft")); assertEquals(0, summary(receipt, "skipped")); assertEquals(0, summary(receipt, "relations_failed"))
            assertEquals(1, summary(receipt, if (f.params["action"] == JsonPrimitive("remove")) "relations_removed" else "relations_ignored"))
            assertEquals(JsonArray(emptyList()), f.fields()["components"]); assertEquals(emptyList<Any>(), f.relations().single()["candidates"])
        }
    }
    @Test fun reselectedCounterpartJoinsProjectionAndOldCounterpartRemainsConfirmed() {
        for (name in names("reselect")) {
            val f = Fixture(name); assertEquals(1, summary(f.commit(f.preview()), "relations_confirmed"))
            assertEquals(projection(f.detail()), projection(f.detail(1105))); assertNotEquals(projection(f.detail()), projection(f.detail(1102)))
            assertEquals(JsonPrimitive("confirmed"), f.detail(1102)["confirmation_status"])
        }
    }
    @Test fun parentOnlyDraftCanSaveEveryCleanupActionWithoutInventingComponents() {
        for (name in names("parent")) {
            val f = Fixture(name); val before = f.fields(); assertEquals(0, summary(f.commit(f.preview()), "relations_failed"))
            val row = f.relations().single(); assertNull(row["primary_component_id"]); assertNull(row["anchor_component_id"])
            assertEquals(f.params["action"] != JsonPrimitive("remove"), row["active"])
            assertEquals(if (f.params["action"] == JsonPrimitive("ignore")) "ignored" else "draft", row["state"]); assertEquals(before, f.fields())
        }
    }
    @Test fun manualAssociationOutsideCandidatesPreservesSourceFieldsAndReplaysOnce() {
        for (name in names("manual")) {
            val f = Fixture(name); val before = listOf(f.fields(), f.fields(1102)); val plan = f.preview()
            assertEquals(JsonPrimitive(true), plan["can_commit"]); assertEquals(1, plan.getValue("items").jsonArray.size)
            assertEquals(setOf("1101", "1102"), plan.getValue("items").jsonArray.single().jsonObject.getValue("member_refs").jsonArray.map { it.jsonObject.getValue("transaction_id").jsonPrimitive.content }.toSet())
            assertTrue(f.relations().isEmpty()); val receipt = f.commit(plan); assertEquals(JsonPrimitive(true), receipt["committed"]); assertEquals(receipt, f.commit(plan))
            val relation = f.relations().single(); assertEquals("confirmed", relation["state"]); assertEquals("manual", relation["decision_source"])
            assertEquals(before, listOf(f.fields(), f.fields(1102)))
        }
    }
    @Test fun manualAssociationExplicitlyClearsConflictingPublicNote() {
        for (name in names("manual_clear")) {
            val f = Fixture(name); val plan = f.preview { index, pending -> if (index == 0) {
                val details = pending.getValue("items").jsonArray.map { item -> f.service.item(f.token,
                    pending.getValue("plan_id").jsonPrimitive.content, item.jsonObject.getValue("preview_item_id").jsonPrimitive.content) }
                assertTrue(details.flatMap { it.getValue("relation_choices").jsonArray }.any {
                    !it.jsonObject["common_field_conflicts"]?.jsonObject?.get("note")?.jsonArray.isNullOrEmpty()
                })
            } }; assertEquals(JsonPrimitive(true), plan["can_commit"])
            assertEquals(JsonPrimitive(true), f.commit(plan)["committed"])
            assertEquals(JsonPrimitive(""), f.detail().getValue("entry").jsonObject.getValue("fields").jsonObject["note"])
        }
    }
    @Test fun currentDecisionsAndManualAdditionsUsePublicHttpAndProduceClientResponses() {
        val captured = mutableListOf<JsonObject>()
        for (label in listOf("joint_http", "manual_http")) for (name in names(label)) testApplication {
            val f = Fixture(name)
            application { financeModule(f.database, mutationPlans = f.service) }
            val plans = mutableMapOf<String, JsonObject>(); val previews = mutableListOf<JsonObject>()
            for (step in f.case.getValue("previews").jsonArray) {
                val source = step.jsonObject; var input = source.getValue("input").jsonObject
                input["previous_plan_id"]?.jsonPrimitive?.contentOrNull?.let { id ->
                    val previous = plans.getValue(id)
                    input = JsonObject(input + mapOf("previous_plan_id" to previous.getValue("plan_id"), "edit_context_id" to previous.getValue("edit_context_id"),
                        "expected_plan_revision" to previous.getValue("plan_revision")))
                }
                val response = client.post("/api/v1/cash-mutations/plans") {
                    header(HttpHeaders.Authorization, "Bearer ${f.token}"); contentType(ContentType.Application.Json); setBody(input.toString())
                }
                val body = response.bodyAsText(); assertEquals(200, response.status.value, body)
                val plan = Json.parseToJsonElement(body).jsonObject
                plans[source.getValue("plan_id").jsonPrimitive.content] = plan
                previews += buildJsonObject { put("request", input); put("body", plan) }
            }
            val plan = plans.getValue(f.case.getValue("plan_id").jsonPrimitive.content)
            val id = plan.getValue("plan_id").jsonPrimitive.content
            val items = plan.getValue("items").jsonArray.map { item ->
                val response = client.get("/api/v1/cash-mutations/plans/$id/items/${item.jsonObject.getValue("preview_item_id").jsonPrimitive.content}") {
                    header(HttpHeaders.Authorization, "Bearer ${f.token}")
                }
                val body = response.bodyAsText(); assertEquals(200, response.status.value, body); Json.parseToJsonElement(body)
            }
            val key = f.case.getValue("commit").jsonObject.getValue("idempotency_key").jsonPrimitive.content
            val response = client.post("/api/v1/cash-mutations/plans/$id/commit") {
                header(HttpHeaders.Authorization, "Bearer ${f.token}"); header("Idempotency-Key", key); contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("expected_plan_revision", plan.getValue("plan_revision")) }.toString())
            }
            val body = response.bodyAsText(); assertEquals(200, response.status.value, body)
            val receipt = Json.parseToJsonElement(body).jsonObject
            assertEquals(1, summary(receipt, "relations_confirmed")); assertEquals(JsonPrimitive(true), receipt["committed"])
            captured += buildJsonObject {
                put("label", label); put("backend", if (f.database.sqlite) "sqlite" else "postgresql"); put("previews", JsonArray(previews))
                put("plan", plan); put("items", JsonArray(items)); put("receipt", receipt); put("key", key)
            }
        }
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { output ->
            val path = Path.of(output); Files.createDirectories(path)
            Files.writeString(path.resolve("current-maintenance-response.json"), JsonArray(captured).toString())
        }
    }
    @Test fun secondJointMemberFailureRollsBackTheCompleteRelationGroup() {
        for (name in names("fault_joint")) {
            val f = Fixture(name); val plan = f.preview(); val before = f.snapshot(); var attempts = 0
            val failure = assertFailsWith<CashImportCommitFailure> { f.rawCommit(plan, f.instrument { step ->
                if (step == "current_relation" && ++attempts == 2) error("synthetic private second relation failure")
            }) }
            assertEquals(2, attempts); assertEquals("not_committed", failure.commitState); assertEquals(before, f.snapshot())
        }
    }
    @Test fun publicationFailureRollsBackAndKeepsTheOriginalPlanRetryable() {
        for (name in names("fault_publish")) {
            val f = Fixture(name); val plan = f.preview(); val before = f.snapshot(); val stage = f.params.getValue("failure").jsonPrimitive.content
            var attempted = false
            val failure = assertFailsWith<CashImportCommitFailure> { f.rawCommit(plan, f.instrument { if (it == stage) {
                attempted = true; error("synthetic late $stage failure")
            } }) }
            assertTrue(attempted); assertEquals("not_committed", failure.commitState); assertEquals(before, f.snapshot())
            assertEquals(plan, f.service.plan(f.token, plan.getValue("plan_id").jsonPrimitive.content))
            assertEquals(1, summary(f.commit(plan), "relations_confirmed"))
        }
    }
    @Test fun candidateEvidenceChangeRefusesTheOldPlanWithoutParentRevisionOrWrites() {
        for (name in names("stale_candidate")) {
            val f = Fixture(name); val plan = f.preview(); val revision = f.detail()["row_revision"]
            f.applyChanges(); val before = f.snapshot(); assertEquals(revision, f.detail()["row_revision"])
            val failure = assertFailsWith<MutationPlanFailure> { f.rawCommit(plan) }
            assertEquals("relation_context_changed", failure.code); assertEquals("not_committed", failure.commitState)
            assertEquals(before, f.snapshot()); assertEquals(plan, f.service.plan(f.token, plan.getValue("plan_id").jsonPrimitive.content))
        }
    }
    @Test fun lateFieldFailureRollsBackBothCompleteAndCandidateOnlyRelationGroups() {
        for (label in listOf("fault_field", "fault_candidate_field")) for (name in names(label)) {
            val f = Fixture(name); val plan = f.preview(); val before = f.snapshot(); val stages = mutableListOf<String>()
            val failure = assertFailsWith<CashImportCommitFailure> { f.rawCommit(plan, f.instrument { stage ->
                stages += stage; if (stage == "record") error("synthetic late field save failure")
            }) }
            assertEquals("not_committed", failure.commitState); assertEquals(1, stages.count { it == "record" })
            assertFalse("current_relation" in stages); assertEquals(before, f.snapshot())
        }
    }
    @Test fun coupledCleanupFailureKeepsDraftPartsAndEveryDistinctCandidateProof() {
        for (name in names("fault_coupled")) {
            val f = Fixture(name); val plan = f.preview(); val before = f.snapshot(); val fields = f.fields(); val stages = mutableListOf<String>()
            val failure = assertFailsWith<CashImportCommitFailure> { f.rawCommit(plan, f.instrument { stage ->
                stages += stage; if (stage == "current_relation") error("synthetic late candidate cleanup failure")
            }) }
            assertEquals("not_committed", failure.commitState); assertEquals(1, stages.count { it == "current_relation" }); assertFalse("record" in stages)
            assertEquals(before, f.snapshot()); assertEquals(fields, f.fields())
        }
    }
    @Test fun deferredGroupCannotDiscardDistinctProofsToAllowComponentRemoval() {
        for (name in names("defer_distinct")) {
            val f = Fixture(name); val before = f.snapshot(); val fields = f.fields(); val plan = f.preview()
            val failure = assertFailsWith<CashImportCommitFailure> { f.rawCommit(plan) }
            assertEquals("not_committed", failure.commitState); assertEquals(before, f.snapshot()); assertEquals(fields, f.fields())
        }
    }
    @Test fun rejectedTypePatchPreservesOriginalRelationsBeforeAValidExplicitConfirmation() {
        for (name in names("type_rejected")) {
            val f = Fixture(name); val before = f.relations(); var rejected = 0
            val plan = f.preview(onFailure = { rejected++ })
            assertEquals(1, rejected); assertEquals(before, f.relations()); assertEquals(1, summary(f.commit(plan), "relations_confirmed"))
        }
    }
    @Test fun forgedReferenceCannotAuthorizeAnotherActionOrCandidate() {
        for (name in names("forged")) {
            val f = Fixture(name); val before = f.snapshot(); var rejected = 0
            f.preview(onFailure = { assertEquals("plan_input_invalid", it); rejected++ })
            assertEquals(1, rejected); assertEquals(before, f.snapshot())
        }
    }
    @Test fun rejectedTypePatchKeepsContextAndNeverImplicitlyConfirmsTheRelation() {
        for (name in names("type_context")) {
            val f = Fixture(name); var rejected = 0; val plan = f.preview(onFailure = { rejected++ })
            assertEquals(1, rejected); val reselect = f.params.getValue("reselect").jsonPrimitive.boolean
            assertEquals(if (reselect) 1L else 0L, summary(f.commit(plan), "relations_confirmed"))
            assertEquals(if (reselect) "confirmed" else "draft", f.relations().single()["state"])
            assertEquals(JsonPrimitive("consumption"), f.fields()["record_type"])
        }
    }
    @Test fun invalidManualInputIsRejectedWithoutPartialSuccess() {
        for (name in names("manual_invalid")) {
            val f = Fixture(name); val before = f.snapshot(); var rejected = 0
            f.preview(onFailure = { rejected++ }); assertEquals(1, rejected); assertTrue(f.relations().isEmpty()); assertEquals(before, f.snapshot())
        }
    }
    @Test fun targetRevisionChangeRefusesManualAssociationBeforeAnyWrites() {
        for (name in names("manual_stale")) {
            val f = Fixture(name); val plan = f.preview(); f.applyChanges(); val before = f.snapshot()
            val failure = assertFailsWith<RuntimeException> { f.rawCommit(plan) }
            assertTrue(failure is MutationPlanFailure || failure is MutationPlanFieldFailure)
            assertEquals(before, f.snapshot()); assertTrue(f.relations().isEmpty())
        }
    }
    @Test fun originalManualSearchAndExplicitTargetPreviewUseTheHttpContract() {
        for (name in names("manual_contract_http")) testApplication {
            val f = Fixture(name); val before = f.snapshot()
            application { financeModule(f.database, mutationPlans = f.service) }
            val found = client.get("/api/v1/cash-records/1101/association-targets?query=自由搜索&amount=100.00") {
                header(HttpHeaders.Authorization, "Bearer ${f.token}")
            }
            assertEquals(200, found.status.value, found.bodyAsText())
            assertEquals(listOf(JsonPrimitive("1102")), Json.parseToJsonElement(found.bodyAsText()).jsonObject.getValue("items").jsonArray.map { it.jsonObject["transaction_id"] })
            val response = client.post("/api/v1/cash-mutations/plans") {
                header(HttpHeaders.Authorization, "Bearer ${f.token}"); contentType(ContentType.Application.Json)
                setBody(f.case.getValue("previews").jsonArray.single().jsonObject.getValue("input").toString())
            }
            assertEquals(200, response.status.value, response.bodyAsText())
            assertEquals(JsonPrimitive(true), Json.parseToJsonElement(response.bodyAsText()).jsonObject["can_commit"]); assertEquals(before, f.snapshot())
        }
    }
    @Test fun relationWriterUsesOneFreshContextAndStillValidatesTheCompleteGraph() {
        for (name in names("fresh_context")) {
            val f = Fixture(name); val plan = f.preview(); var recording = false; val queries = mutableListOf<String>()
            val database = f.observedDatabase { if (recording) queries += it.lowercase().replace("\"", "").replace("`", "") }
            val receipt = f.rawCommit(plan, f.instrument(database) { stage ->
                if (stage == "current_relation_start") recording = true
                if (stage == "current_relation_end") recording = false
            })
            assertEquals(1, summary(receipt, "relations_confirmed"))
            val loads = queries.filter { "cash_transaction_components as p" in it && "cash_investment_funding_relations as f" in it }
            assertEquals(1, loads.size, queries.joinToString("\n"))
        }
    }
}
