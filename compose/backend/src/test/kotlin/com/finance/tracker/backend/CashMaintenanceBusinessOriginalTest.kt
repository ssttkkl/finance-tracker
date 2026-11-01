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

class CashMaintenanceBusinessOriginalTest {
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_MAINTENANCE_BUSINESS_CONTRACT_DIR")))
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
    private inner class Fixture(name: String) {
        private val directory = root().resolve(name)
        val case = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject
        val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        val input = case.getValue("input").jsonObject
        val id = input.getValue("transaction_id").jsonPrimitive.content
        val params = case.getValue("metadata").jsonObject.getValue("params").jsonObject
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val token = "original-import-owner"
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        var queries = 0
        var beforeQuery: (String) -> Unit = {}
        private fun observe(raw: Connection): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement") {
                val query = (args!![0] as String).replace(Regex("""'(?:''|[^'])*'|"(?:""|[^"])*"|`(?:``|[^`])*`"""), " ")
                val words = Regex("[A-Za-z_][A-Za-z_0-9]*").findAll(query).map { it.value.lowercase() }.toList()
                assertTrue(words.first() in setOf("select", "with") && words.none {
                    it in setOf("insert", "update", "delete", "merge", "create", "drop", "alter", "truncate", "call", "copy")
                } && !Regex("(?i)\\breplace\\s+into\\b").containsMatchIn(query), "Preview mutation SQL: $query")
                queries++
                beforeQuery(args[0] as String)
            }
            check(method.name != "createStatement" && method.name != "prepareCall") { "Unobserved SQL execution" }
            try { method.invoke(raw, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
        } as Connection
        private val observed = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), ::observe)
        val store = Store(at)
        fun service(factory: ((CashSqlContext) -> CashImportBusinessPlanBuilder)? = null) = MutationPlanService(observed,
            now = { at }, staging = store, planBuilder = CashMutationPlanBuilder { context, request, previous ->
                if (factory == null) MutationPlanBuilder(context).build(request.transactionId.toLong(), request, previous)
                else CurrentCashMaintenancePlanBuilder(context, businessBuilder = factory).build(request, previous)
            })
        fun rawPlan(view: JsonObject) = sourceJson(store.get(view.getValue("edit_context_id").jsonPrimitive.content, workspace,
            config.getValue("user").jsonPrimitive.content).plan!!).jsonObject
        fun members(service: MutationPlanService, view: JsonObject) = items(view).flatMap { item ->
            service.item(token, view.getValue("plan_id").jsonPrimitive.content, item.getValue("preview_item_id").jsonPrimitive.content)
                .getValue("members").jsonArray.map { it.jsonObject }
        }.associateBy { it.getValue("transaction_id").jsonPrimitive.content }
        fun detail() = CashRecordDetailService(database, now = { at }).detail(token, id)
        fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table ->
            table.name to connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            } }
        } }
        fun rejected(code: String) {
            val failure = assertFailsWith<MutationPlanFieldFailure> { service().preview(token, input) }
            assertEquals(code, failure.code)
            assertEquals(0, store.writes); assertTrue(store.entries.isEmpty())
        }
    }
    private fun cases(method: String, block: (Fixture) -> Unit) {
        val names = Json.parseToJsonElement(root().resolve("scenarios.json").readText()).jsonObject.filterValues {
            it.jsonObject["method"] == JsonPrimitive("test_$method")
        }.keys
        assertTrue(names.isNotEmpty(), method)
        for (name in names) {
            val fixture = Fixture(name); val before = fixture.snapshot()
            block(fixture)
            assertTrue(fixture.queries > 0, name); assertEquals(before, fixture.snapshot(), name)
        }
    }
    private fun items(view: JsonObject) = view.getValue("items").jsonArray.map { it.jsonObject }
    private fun operations(plan: JsonObject) = plan.getValue("operations").jsonArray.map { it.jsonObject }
    private fun summary(view: JsonObject, key: String) = view.getValue("summary").jsonObject[key]?.jsonPrimitive?.long ?: 0
    private fun amount(item: JsonObject) = item.getValue("display").jsonObject.getValue("amount").jsonPrimitive.content.toBigDecimal()

    @Test fun postedEditOnInactiveAccountIsReadOnlyWithoutDiscovery() = cases("posted_edit_on_inactive_account_is_a_read_only_real_preview_without_discovery") { f ->
        val before = f.detail(); var discoveries = 0
        val accountId = before.getValue("fields").jsonObject.getValue("account_id").jsonPrimitive.long
        assertEquals(false, f.database.read { it.jooq(f.database.sqlite).select(ACCOUNTS.ACTIVE).from(ACCOUNTS)
            .where(ACCOUNTS.WORKSPACE_ID.eq(f.workspace)).and(ACCOUNTS.ID.eq(accountId)).fetchSingle().value1() })
        val service = f.service { context -> object : CashImportBusinessPlanBuilder(context) {
            override fun discover(snapshot: CashImportPreviewSnapshot, seeds: Set<String>, accounts: Map<String, Map<String, Any?>>): List<RelationProposal> {
                discoveries++; fail("Ordinary posted edit started discovery")
            }
        } }
        val view = service.preview(f.token, f.input); val item = items(view).first(); val member = f.members(service, view).getValue(f.id)
        assertEquals(1, summary(view, "update_posted")); assertEquals(JsonPrimitive(true), view["can_commit"])
        assertEquals(JsonPrimitive("明确人工备注"), item.getValue("display").jsonObject["note"])
        assertEquals(JsonPrimitive("posted"), member.getValue("fields").jsonObject["state"])
        assertFalse(JsonPrimitive("amount") in member.getValue("editable_fields").jsonArray)
        assertFalse(JsonPrimitive("account_name") in member.getValue("editable_fields").jsonArray)
        assertEquals(JsonArray(emptyList()), f.rawPlan(view).getValue("dependencies").jsonObject["discovery_seeds"])
        assertEquals(0, discoveries); assertEquals(before, f.detail())
    }
    @Test fun formalFinancialChangeIsRejectedWithoutPublishing() = cases("formal_financial_change_is_rejected_without_publishing") { f ->
        val before = f.detail(); f.rejected("fixed_field_conflict"); assertEquals(before, f.detail())
    }
    @Test fun unchangedMaintenanceKeepsOneRealItemWithoutClaimingUpdate() = cases("unchanged_maintenance_keeps_one_real_item_without_claiming_update") { f ->
        val view = f.service().preview(f.token, f.input)
        assertEquals(1, summary(view, "duplicate")); assertEquals(0, summary(view, "update_posted"))
        val item = items(view).single()
        assertEquals(JsonArray(listOf(buildJsonObject { put("transaction_id", f.id) })), item["member_refs"])
    }
    @Test fun fullRefundRemainsVisibleAndExistingDecisionIsNotOverwritten() = cases("full_refund_remains_visible_and_existing_decision_is_not_overwritten") { f ->
        val state = f.params.getValue("state").jsonPrimitive.content
        val view = f.service().preview(f.token, f.input); val item = items(view).single()
        assertEquals(0, amount(item).compareTo(if (state == "confirmed") "0".toBigDecimal() else "-12.5".toBigDecimal()))
        assertEquals(if (state == "confirmed") 2 else 1, item.getValue("member_refs").jsonArray.size)
        assertEquals(0, summary(view, "relations_accepted")); assertEquals(0, summary(view, "relations_removed"))
        val states = f.database.read { it.jooq(f.database.sqlite).select(TRANSACTION_RELATIONS.STATE).from(TRANSACTION_RELATIONS)
            .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(f.workspace)).and(TRANSACTION_RELATIONS.KIND.eq("refund_offset")).fetch(TRANSACTION_RELATIONS.STATE) }
        assertEquals(listOf(state), states)
    }
    @Test fun unboundInternalIdentifiersDoNotBecomeRefundProof() = cases("unbound_internal_business_identifiers_do_not_become_refund_proof") { f ->
        val view = f.service().preview(f.token, f.input)
        assertEquals(0, summary(view, "relations_accepted")); assertTrue(items(view).all { it.getValue("member_refs").jsonArray.size == 1 })
    }
    @Test fun commonEntryPreviewKeepsSourcesReadOnlyAndRelationshipUnchanged() = cases("common_entry_preview_keeps_sources_readonly_and_relationship_unchanged") { f ->
        val service = f.service(); val view = service.preview(f.token, f.input); val item = items(view).single()
        val category = f.input.getValue("patch").jsonObject.getValue("category_id")
        assertEquals(1, summary(view, "update_posted")); assertEquals(JsonPrimitive("整笔备注"), item.getValue("display").jsonObject["note"])
        assertEquals(category, item.getValue("display").jsonObject["category_id"])
        val members = f.members(service, view)
        assertEquals(JsonPrimitive("成员甲"), members.getValue("1003").getValue("fields").jsonObject["note"])
        assertEquals(JsonPrimitive("成员乙"), members.getValue("1001").getValue("fields").jsonObject["note"])
        val data = f.rawPlan(view); val operation = operations(data).single()
        assertEquals(category, operation.getValue("entry_edit").jsonObject.getValue("patch").jsonObject["category_id"])
        assertEquals(JsonArray(emptyList()), data["category_member_ids"])
        assertEquals(JsonArray(emptyList()), data.getValue("type_effects").jsonObject["removed_relation_ids"])
        assertEquals(listOf(true), f.database.read { it.jooq(f.database.sqlite).select(TRANSACTION_RELATIONS.ACTIVE).from(TRANSACTION_RELATIONS)
            .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(f.workspace)).fetch(TRANSACTION_RELATIONS.ACTIVE) })
    }
    @Test fun retainedSourceIssueIsVisibleWithoutScheduledRewrite() = cases("retained_source_issue_is_visible_but_not_scheduled_for_rewrite") { f ->
        val issue = f.database.read { it.jooq(f.database.sqlite).select(CASH_TRANSACTION_ISSUES.ID, CASH_TRANSACTION_ISSUES.REVISION, CASH_TRANSACTION_ISSUES.PENDING_SOURCE_PAYLOAD)
            .from(CASH_TRANSACTION_ISSUES).where(CASH_TRANSACTION_ISSUES.WORKSPACE_ID.eq(f.workspace)).and(CASH_TRANSACTION_ISSUES.TRANSACTION_ID.eq(f.id.toLong())).fetchSingle() }
        val view = f.service().preview(f.token, f.input); val item = items(view).first()
        assertEquals(JsonPrimitive("pending"), item["confirmation_status"])
        assertTrue(item.getValue("attention_reasons").jsonArray.any { it.jsonObject["code"] == JsonPrimitive("source_update_pending") })
        val operation = operations(f.rawPlan(view)).single()
        assertEquals(JsonArray(listOf(JsonPrimitive(issue.value1().toString()))), operation["retained_issue_ids"])
        assertFalse(operation.getValue("issues").jsonArray.any { it.jsonObject["code"] == JsonPrimitive("source_update_pending") })
        assertEquals(Json.parseToJsonElement("""{"备注":"待采用原文"}"""), issue.value3())
        assertTrue(issue.value2()!! > 0)
    }
    @Test fun draftFinalizationKeepsIdentityAndOnlyOwnPartsBecomeDiscoverySeeds() = cases("draft_finalization_previews_same_identity_and_only_its_parts_become_discovery_seeds") { f ->
        val before = f.detail(); var observed: Set<String>? = null
        val service = f.service { context -> object : CashImportBusinessPlanBuilder(context) {
            override fun discover(snapshot: CashImportPreviewSnapshot, seeds: Set<String>, accounts: Map<String, Map<String, Any?>>): List<RelationProposal> {
                assertNull(observed); observed = seeds.toSet(); return super.discover(snapshot, seeds, accounts)
            }
        } }
        val view = service.preview(f.token, f.input)
        assertEquals(1, summary(view, "finalize_draft")); assertEquals(JsonPrimitive(true), view["can_commit"])
        assertTrue(items(view).all { it["anticipated_state"] == JsonPrimitive("posted") })
        val data = f.rawPlan(view); val operation = operations(data).single()
        assertEquals(JsonPrimitive(f.id), operation["transaction_id"]); assertEquals(JsonPrimitive("posted"), operation.getValue("values").jsonObject["state"])
        val seeds = operation.getValue("preview_components").jsonArray.map { it.jsonObject.getValue("component_id").jsonPrimitive.content }.toSet()
        assertEquals(seeds, data.getValue("dependencies").jsonObject.getValue("discovery_seeds").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(seeds, observed); assertEquals(before, f.detail())
    }
    @Test fun completeDraftCannotBypassUnresolvedSourceIssue() = cases("complete_draft_cannot_bypass_unresolved_source_issue") { f ->
        f.rejected(f.params.getValue("code").jsonPrimitive.content)
        assertEquals(JsonPrimitive("draft"), f.detail().getValue("fields").jsonObject["state"])
        assertEquals(listOf(Json.parseToJsonElement("""{"备注":"不能丢失"}""")), f.database.read { it.jooq(f.database.sqlite)
            .select(CASH_TRANSACTION_ISSUES.PENDING_SOURCE_PAYLOAD).from(CASH_TRANSACTION_ISSUES)
            .where(CASH_TRANSACTION_ISSUES.WORKSPACE_ID.eq(f.workspace)).and(CASH_TRANSACTION_ISSUES.TRANSACTION_ID.eq(f.id.toLong())).fetch(CASH_TRANSACTION_ISSUES.PENDING_SOURCE_PAYLOAD) })
    }
    @Test fun incompleteDraftDoesNotPublishPostingPlan() = cases("incomplete_draft_does_not_publish_a_posting_plan") { f ->
        f.rejected("account_missing"); assertEquals(JsonPrimitive("draft"), f.detail().getValue("fields").jsonObject["state"])
    }
    @Test fun pairingProgrammingFailureStopsFinalizationPreview() = cases("pairing_programming_failure_stops_finalization_preview") { f ->
        val before = f.detail(); var reached = 0; var entered = 0; var armed = false
        val failure = IllegalStateException("sensitive-native-error-must-not-escape")
        val candidate = f.database.read { it.jooq(f.database.sqlite).select(CASH_TRANSACTIONS.SOURCE_TYPE, CASH_TRANSACTIONS.RECORD_TYPE)
            .from(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace)).and(CASH_TRANSACTIONS.ID.eq(1001L)).fetchSingle() }
        assertEquals("icbc_debit", candidate.value1()); assertEquals("refund", candidate.value2())
        f.beforeQuery = { query ->
            val sql = query.replace("`", "").replace("\"", "").lowercase()
            if (armed && sql.contains("from account_aliases")) { reached++; throw failure }
        }
        val service = f.service { context -> object : CashImportBusinessPlanBuilder(context) {
            override fun discover(snapshot: CashImportPreviewSnapshot, seeds: Set<String>, accounts: Map<String, Map<String, Any?>>): List<RelationProposal> {
                entered++; assertTrue(seeds.isNotEmpty()); assertTrue(snapshot.facts.isNotEmpty()); armed = true
                return super.discover(snapshot, seeds, accounts)
            }
        } }
        assertSame(failure, assertFailsWith<IllegalStateException> { service.preview(f.token, f.input) })
        assertEquals(1, entered); assertEquals(1, reached); assertEquals(before, f.detail())
        assertEquals(0, f.store.writes); assertTrue(f.store.entries.isEmpty())
    }
    @Test fun currentSourceScopeAndRemoteRefundKeyWorkWithoutFiles() = cases("current_source_scope_and_remote_refund_key_work_without_files") { f ->
        val sameScope = f.params.getValue("same_scope").jsonPrimitive.boolean
        val view = f.service().preview(f.token, f.input)
        assertEquals(sameScope, summary(view, "relations_accepted") != 0L)
        if (sameScope) {
            val item = items(view).first()
            assertEquals(JsonArray(listOf("1003", f.id).map { buildJsonObject { put("transaction_id", it) } }), item["member_refs"])
            assertEquals(0, amount(item).compareTo("-7.5".toBigDecimal()))
        }
    }
}
