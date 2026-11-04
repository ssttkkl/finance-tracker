package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CurrentCashRelationPlanOriginalTest {
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private val token = "original-import-owner"
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CURRENT_PLAN_CONTRACT_DIR")))
    private inner class Fixture(val name: String, directory: Path = root().resolve(name)) {
        val case = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject
        val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        val metadata = case.getValue("metadata").jsonObject
        val params = metadata.getValue("params").jsonObject
        val checkpoint = metadata.getValue("checkpoint").jsonPrimitive.int
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val user = config.getValue("user").jsonPrimitive.content
        val input = JsonObject(case.getValue("input").jsonObject - "issue_resolutions")
        val database = database()
        var queries = 0
        var forbidEntryCurrent = false
        private fun database(observe: Boolean = false) = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")) { connection ->
            if (!observe) connection else Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "prepareStatement") {
                    queries++
                    val query = (args!![0] as String).trimStart().lowercase()
                    check(query.startsWith("select") || query.startsWith("with")) { "Preview issued mutation SQL: $query" }
                    if (forbidEntryCurrent) assertFalse(Throwable().stackTrace.any {
                        it.className == CashEntryRepository::class.java.name && it.methodName == "current"
                    }, "Unused top-level entry read")
                }
                check(method.name !in setOf("createStatement", "prepareCall")) { "Unobserved SQL" }
                try { method.invoke(connection, *(args ?: emptyArray())) }
                catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
            } as Connection
        }
        val staging = InMemoryParsedImportStore(clock = { at })
        val store = MutationPlanStore(staging = staging)
        val service = MutationPlanService(database(true), now = { at }, staging = staging,
            planBuilder = CashMutationPlanBuilder { context, request, previous ->
                CurrentCashMaintenancePlanBuilder(context) { sql -> object : CashImportBusinessPlanBuilder(sql) {
                    override fun discover(snapshot: CashImportPreviewSnapshot, seeds: Set<String>, accounts: Map<String, Map<String, Any?>>): List<RelationProposal> =
                        fail("Current relation maintenance searched new pairs")
                } }.build(request, previous)
            })
        var previous: JsonObject? = null
        private var request = input
        init {
            case["previous"]?.takeIf { it != JsonNull }?.jsonObject?.let { old ->
                val context = store.create(workspace, user, input.getValue("transaction_id").jsonPrimitive.content)
                val plan = JsonObject(old + mapOf("plan_id" to JsonPrimitive(store.planId(context.id)),
                    "edit_context_id" to JsonPrimitive(context.id), "context_revision" to sourceJson(context.revision + java.math.BigInteger.ONE),
                    "expires_at" to JsonPrimitive(manualInstantText(context.expiresAt))))
                store.replace(context.id, workspace, user, context.revision, plan, input)
                previous = plan
                request = JsonObject(input + mapOf("edit_context_id" to plan.getValue("edit_context_id"),
                    "previous_plan_id" to plan.getValue("plan_id"), "expected_plan_revision" to plan.getValue("plan_revision")))
            }
        }
        fun preview() = service.preview(token, request)
        fun raw(view: JsonObject) = store.get(view.getValue("edit_context_id").jsonPrimitive.content, workspace, user).plan!!
        fun detail(id: String = "1101") = CashRecordDetailService(database, now = { at }).detail(token, id)
        fun relations() = database.read { connection ->
            val context = CashSqlContext(connection, workspace, database.sqlite, now = { at })
            context.sql.select(TRANSACTION_RELATIONS.ID).from(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace))
                .orderBy(TRANSACTION_RELATIONS.ID).fetch(TRANSACTION_RELATIONS.ID).map { CurrentCashRelationWriteRepository(context).get(it) }
        }
        fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table -> table.name to connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            }
        } } }
        fun groups(view: JsonObject): List<JsonObject> = items(view).flatMap { item -> service.item(token,
            view.getValue("plan_id").jsonPrimitive.content, item.getValue("preview_item_id").jsonPrimitive.content)
            .getValue("relation_choices").jsonArray.map { it.jsonObject } }.distinctBy { it["suggestion_id"] }
        fun rejected(code: String? = null) {
            val old = previous!!; val expected = service.plan(token, old.getValue("plan_id").jsonPrimitive.content)
            val failure = assertFailsWith<RuntimeException> { preview() }
            val actual = when (failure) {
                is MutationPlanFieldFailure -> failure.code
                is MutationPlanFailure -> failure.code
                is CashPolicyError -> failure.code
                is CashImportPlanError -> failure.code
                else -> throw failure
            }
            if (code != null) assertEquals(code, actual)
            assertEquals(expected, service.plan(token, old.getValue("plan_id").jsonPrimitive.content))
        }
    }
    private fun cases(method: String, checkpoint: Int? = null, block: (Fixture) -> Unit) {
        val entries = Json.parseToJsonElement(root().resolve("scenarios.json").readText()).jsonObject.filterValues {
            it.jsonObject["module"] == JsonPrimitive("current") && it.jsonObject["method"] == JsonPrimitive("test_$method") &&
                (checkpoint == null || it.jsonObject["checkpoint"] == JsonPrimitive(checkpoint))
        }
        assertTrue(entries.isNotEmpty(), method)
        for (name in entries.keys) {
            val f = Fixture(name); val before = f.snapshot(); block(f)
            assertTrue(f.queries > 0, name); assertEquals(before, f.snapshot(), name)
        }
    }
    private fun items(view: JsonObject) = view.getValue("items").jsonArray.map { it.jsonObject }
    private fun refs(value: JsonObject) = value.getValue("member_refs").jsonArray.map { it.jsonObject.getValue("transaction_id").jsonPrimitive.content }.toSet()
    private fun summary(view: JsonObject, key: String) = view.getValue("summary").jsonObject[key]?.jsonPrimitive?.long ?: 0
    private fun amount(item: JsonObject) = item.getValue("display").jsonObject.getValue("amount").jsonPrimitive.content.toBigDecimal()
    private fun summaries(value: JsonObject) = value.getValue("relations").jsonArray.map {
        val relation = it.jsonObject
        relation.getValue("kind").jsonPrimitive.content to
            relation.getValue("member_refs").jsonArray.map { member -> member.jsonObject.getValue("transaction_id").jsonPrimitive.content }
    }.toSet()

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun mixedCompleteGroupAndEveryCandidateKeepAllRelationSummaries() = cases("mixed_complete_group_and_candidates_keep_each_relation_summary") { f ->
        val view = f.preview(); val group = f.groups(view).single()
        val expected = setOf("refund_offset" to listOf("1101", "1103"), "refund_offset" to listOf("1102", "1104"),
            "payment_mirror" to listOf("1101", "1102"), "payment_mirror" to listOf("1103", "1104"))
        assertEquals(expected, summaries(group)); val candidates = group.getValue("candidates").jsonArray
        assertTrue(candidates.isNotEmpty()); for (candidate in candidates) assertEquals(expected, summaries(candidate.jsonObject))
        val public = sourceJson(CashImportPlanViews.publicDetail(mapOf("relation_choices" to listOf(cashSourceValue(group))))).jsonObject
            .getValue("relation_choices").jsonArray.single().jsonObject
        assertEquals(expected, summaries(public)); for (candidate in public.getValue("candidates").jsonArray) assertEquals(expected, summaries(candidate.jsonObject))
    }
    @Test fun relationSummaryPublicBoundaryKeepsOnlyDeclaredFieldsWithoutChangingSource() {
        val relation = Json.parseToJsonElement("""{"kind":"refund_offset","evidence":{"private":true},"member_refs":[{"transaction_id":"11","row_revision":99},{"row_ref":"source-row","internal":true}]}""").jsonObject
        val source = buildJsonObject { put("relation_choices", buildJsonArray { add(buildJsonObject {
            put("suggestion_id", "group"); put("relations", JsonArray(listOf(relation)))
            put("candidates", buildJsonArray { add(buildJsonObject { put("candidate_id", "candidate"); put("relations", JsonArray(listOf(relation))) }) })
        }) }) }
        val before = source.toString()
        val expected = Json.parseToJsonElement("""[{"kind":"refund_offset","member_refs":[{"transaction_id":"11"},{"row_ref":"source-row"}]}]""")
        val mutableInput = cashSourceValue(source)
        val inputBefore = sourceJson(mutableInput)
        val public = sourceJson(CashImportPlanViews.publicDetail(mutableInput)).jsonObject
        val frozen = MutationPlanView.item(buildJsonObject { put("item_details", buildJsonObject { put("item", JsonObject(source + ("members" to JsonArray(emptyList())))) }) }, "item")
        for (detail in listOf(public, frozen)) {
            val group = detail.getValue("relation_choices").jsonArray.single().jsonObject
            assertEquals(expected, group["relations"])
            assertEquals(expected, group.getValue("candidates").jsonArray.single().jsonObject["relations"])
        }
        assertEquals(inputBefore, sourceJson(mutableInput))
        assertEquals(before, source.toString()); assertTrue("evidence" in relation)
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun defaultPreviewPreservesEachDecisionAndHidesIgnoredCandidatesWithoutDiscovery() = cases("default_preview_preserves_decisions_and_hides_ignored_candidate_groups") { f ->
        val before = f.relations(); val view = f.preview(); val state = f.params.getValue("state").jsonPrimitive.content
        assertEquals(before, f.relations()); assertEquals(JsonPrimitive(true), view["can_commit"])
        for (key in listOf("relations_accepted", "relations_deferred", "relations_ignored", "relations_removed")) assertEquals(0, summary(view, key))
        if (state == "ignored") assertTrue(f.groups(view).isEmpty()) else assertEquals(JsonPrimitive(if (state == "confirmed") "accept" else "defer"), f.groups(view).single()["action"])
        assertEquals(if (state == "draft") 2 else 1, items(view).size)
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun rejectedCandidateStaysHiddenAfterNativeCommitAndDetailRefresh() {
        val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CURRENT_PLAN_WRITABLE_DIR")))
        val f = Fixture("rejected-current", directory); val before = f.relations(); val view = f.preview(); val group = f.groups(view).single()
        val selected = f.service.preview(token, JsonObject(f.input + mapOf("edit_context_id" to view.getValue("edit_context_id"),
            "previous_plan_id" to view.getValue("plan_id"), "expected_plan_revision" to view.getValue("plan_revision"),
            "relation_choices" to buildJsonArray { add(buildJsonObject { put("suggestion_id", group.getValue("suggestion_id")); put("action", "ignore") }) })))
        val writer = MutationPlanService(f.database, now = { at }, staging = f.staging)
        val receipt = writer.commit(token, selected.getValue("plan_id").jsonPrimitive.content, selected.getValue("plan_revision").jsonPrimitive.long, "synthetic-ignore-current-relation")
        assertEquals(JsonPrimitive(true), receipt["committed"])
        val persisted = f.relations().single(); assertEquals(before.single()["id"], persisted["id"])
        assertEquals("ignored", persisted["state"]); assertEquals(emptyList<Any?>(), persisted["candidates"])
        val refreshed = f.service.preview(token, f.input); assertTrue(f.groups(refreshed).isEmpty()); assertEquals(1, items(refreshed).size)
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun jointDraftLoadsBothMirrorsAndAcceptsOneCompleteZeroAmountPreview() = cases("joint_draft_loads_both_mirrors_and_accepts_only_as_one_full_preview") { f ->
        val before = f.relations(); val view = f.preview()
        if (f.checkpoint == 1) {
            val group = f.groups(view).single { it["kind"] == JsonPrimitive("payment_mirror") }
            val stored = f.raw(view).getValue("relation_groups").jsonArray.single { it.jsonObject["suggestion_id"] == group["suggestion_id"] }.jsonObject
            assertEquals(before.filter { it["kind"] == "payment_mirror" }.map { it["id"].toString() }.toSet(), stored.getValue("edges").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet())
            assertEquals(2, items(view).size); assertTrue(items(view).all { amount(it).signum() == 0 })
        } else {
            val item = items(view).single(); assertEquals(0, amount(item).signum()); assertEquals(JsonPrimitive("confirmed"), item["confirmation_status"])
            assertEquals(setOf("1101", "1102", "1103", "1104"), refs(item)); assertEquals(1, summary(view, "relations_accepted"))
        }
        assertEquals(before, f.relations())
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun mixedInactiveGroupLoadsAllEdgesAndNeverReactivatesThem() = cases("mixed_inactive_old_group_is_loaded_without_reactivation") { f ->
        val view = f.preview(); val stored = f.raw(view).getValue("relation_groups").jsonArray.single().jsonObject
        val edges = stored.getValue("edges").jsonArray
        assertEquals(2, edges.size)
        if (f.checkpoint == 1) assertEquals(1, edges.count { it.jsonObject["active"] == JsonPrimitive(true) })
        else { assertEquals(1, summary(view, "relations_removed")); assertTrue(edges.all { it.jsonObject["active"] == JsonPrimitive(false) }) }
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun explicitRemovalMarksTheExistingConfirmedRelationEffect() = cases("explicit_removal_marks_the_existing_confirmed_relation_effect", 2) { f ->
        val view = f.preview(); val id = f.relations().single().getValue("id").toString()
        val effects = items(view).flatMap { f.service.item(token, view.getValue("plan_id").jsonPrimitive.content,
            it.getValue("preview_item_id").jsonPrimitive.content).getValue("relation_effects").jsonArray.map { value -> value.jsonObject } }
        assertTrue(effects.isNotEmpty()); val matching = effects.filter { it["relation_id"] == JsonPrimitive(id) }
        assertTrue(matching.isNotEmpty()); assertTrue(matching.all { it["effect"] == JsonPrimitive("remove") })
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun staleRemovalCannotRelabelConfirmedGroupAsDeferred() = cases("stale_removal_choice_does_not_relabel_an_existing_confirmed_group_as_deferred", 2) { f ->
        val view = f.preview(); assertTrue(view.getValue("invalidated_choices").jsonArray.isNotEmpty())
        assertEquals(JsonPrimitive("accept"), f.groups(view).single()["action"])
        val item = items(view).single(); assertEquals(JsonPrimitive("confirmed"), item["confirmation_status"]); assertEquals(2, refs(item).size)
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun importedTypePatchIsRejectedWithoutChangingPreviousPlanOrSourceType() = cases("current_imported_type_patch_is_rejected_without_changing_relation", 2) { f ->
        f.rejected(); assertEquals(JsonPrimitive("consumption"), f.detail().getValue("fields").jsonObject["record_type"])
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun openCandidatesRequireCurrentProofAndPreviewTheCompleteSelectedPair() = cases("open_current_candidates_require_original_current_proof_and_preview_complete_choice") { f ->
        val view = f.preview()
        if (f.checkpoint == 1) assertEquals(setOf("1101", "1102"), refs(f.groups(view).single().getValue("candidates").jsonArray.single().jsonObject))
        else assertTrue(items(view).any { refs(it) == setOf("1101", "1102") })
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun candidateContentChangeInvalidatesOldChoiceWithoutParentRevisionChange() = cases("candidate_content_change_without_parent_revision_invalidates_old_selection", 2) { f ->
        assertEquals(f.input["expected_revision"], f.detail()["row_revision"])
        val view = f.preview(); val reference = f.input.getValue("relation_choices").jsonArray.single().jsonObject.getValue("suggestion_id")
        assertEquals(buildJsonArray { add(buildJsonObject { put("kind", "relation"); put("reference", reference); put("code", "relation_context_changed") }) }, view["invalidated_choices"])
        assertEquals(0, summary(view, "relations_accepted"))
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun foreignCandidateReferenceMustBeRejectedAgainstPreviousCurrentPlan() = cases("candidate_reference_must_come_from_the_previous_current_plan", 2) { f -> f.rejected("plan_input_invalid") }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun tentativeSecondaryCannotBypassStaleValidityOrParentRevisionProof() = cases("tentative_secondary_does_not_bypass_a_stale_candidate_proof") { f ->
        val group = f.groups(f.preview()).single(); assertFalse(JsonPrimitive("accept") in group.getValue("allowed_actions").jsonArray)
        assertTrue(group.getValue("candidates").jsonArray.isEmpty())
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun reselectedCandidateKeepsOldTentativeCounterpartAsConfirmedSingleton() = cases("reselected_current_candidate_keeps_old_tentative_counterpart_in_the_preview", 2) { f ->
        val view = f.preview(); val members = items(view).map(::refs)
        assertTrue(setOf("1101", "1105") in members); assertTrue(setOf("1102") in members)
        assertEquals(JsonPrimitive("confirmed"), items(view).single { refs(it) == setOf("1102") }["confirmation_status"])
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun manualCandidateRoleDoesNotRequireSourceEvidence() = cases("manual_candidate_role_does_not_require_source_evidence") { f ->
        val group = f.groups(f.preview()).single()
        assertTrue(JsonPrimitive("accept") in group.getValue("allowed_actions").jsonArray)
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun manualSpecialRefundIgnoresDiscoveryKeysAndScope() = cases("manual_special_refund_ignores_discovery_keys_and_scope") { f ->
        val group = f.groups(f.preview()).single()
        assertTrue(JsonPrimitive("accept") in group.getValue("allowed_actions").jsonArray)
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun parentOnlyDraftDefersOrClearsWithoutGuessingFinancialParts() = cases("parent_only_draft_can_be_deferred_or_cleared_without_guessing_parts") { f ->
        val view = f.preview()
        if (f.checkpoint == 1) assertFalse(JsonPrimitive("accept") in f.groups(view).single().getValue("allowed_actions").jsonArray)
        else { val item = items(view).first(); assertEquals(JsonPrimitive("draft"), item["anticipated_state"]); assertEquals(JsonNull, item.getValue("display").jsonObject["amount"]) }
        assertEquals(JsonPrimitive("draft"), f.detail()["state"])
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun relationPreviewNeverReadsUnusedTopLevelEntry() = cases("relation_preview_does_not_read_unused_top_level_entry") { f ->
        f.forbidEntryCurrent = true; assertTrue(items(f.preview()).isNotEmpty())
    }
}
