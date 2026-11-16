package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.LocalCashLedgerQuery
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CashCanonicalMaintenanceContractTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }
    private fun authenticated(decorate: (java.sql.Connection) -> java.sql.Connection = { it }, action: (Database, String, Long, String, String) -> Unit) = withCashWriteSetTestWorkspace(backend, decorate) { db, ws, account ->
        val user = UUID.randomUUID().toString(); val token = "canonical-maintenance-$user"
        db.write { c ->
            deletionInsert(c, db, "users", linkedMapOf("id" to user, "email" to "$user@example.invalid", "password_hash" to "unused", "created_at" to deletionTime))
            deletionInsert(c, db, "workspace_memberships", linkedMapOf("workspace_id" to ws, "user_id" to user, "role" to "admin", "created_at" to deletionTime))
            deletionInsert(c, db, "user_sessions", linkedMapOf("id" to UUID.randomUUID().toString(), "user_id" to user, "token_digest" to tokenDigest(token),
                "active_workspace_id" to ws, "created_at" to deletionTime, "expires_at" to java.time.Instant.parse("2099-01-01T00:00:00Z")))
        }
        try { action(db, ws, account, token, user) } finally { db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.deleteFrom(CASH_PROJECTION_MEMBERS).where(CASH_PROJECTION_MEMBERS.WORKSPACE_ID.eq(ws)).execute()
            sql.deleteFrom(CASH_PROJECTION_RELATIONS).where(CASH_PROJECTION_RELATIONS.WORKSPACE_ID.eq(ws)).execute()
            sql.deleteFrom(CASH_PROJECTIONS).where(CASH_PROJECTIONS.WORKSPACE_ID.eq(ws)).execute()
            sql.deleteFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(ws)).execute()
            sql.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(user)).execute()
            sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
            sql.deleteFrom(USERS).where(USERS.ID.eq(user)).execute()
        } }
    }
    private fun sequence(db: Database, ws: String) = db.read { c -> c.jooq(db.sqlite).select(WORKSPACES.LEDGER_SEQ)
        .from(WORKSPACES).where(WORKSPACES.ID.eq(ws)).fetchSingle(WORKSPACES.LEDGER_SEQ)!! }
    private fun snapshot(db: Database, ws: String) = db.read { c -> LedgerSnapshotDto(ws, c.jooq(db.sqlite).select(WORKSPACES.LEDGER_SEQ)
        .from(WORKSPACES).where(WORKSPACES.ID.eq(ws)).fetchSingle(WORKSPACES.LEDGER_SEQ)!!.toString(), CASH_RULES_VERSION, LedgerSnapshotRepository(c, ws, db.sqlite).facts()) }
    private fun allTables(db: Database, ws: String) = db.read { c ->
        com.finance.tracker.backend.persistence.generated.DefaultSchema.DEFAULT_SCHEMA.tables.mapNotNull { table ->
            val scope = table.field(if (table.name == "workspaces") "id" else "workspace_id", String::class.java) ?: return@mapNotNull null
            table.name to c.jooq(db.sqlite).selectFrom(table).where(scope.eq(ws)).fetch().map { it.intoArray().map { it?.toString() } }.sortedBy { it.toString() }
        }.toMap()
    }
    private fun fixture(db: Database, ws: String, account: Long, draft: Boolean = false): Pair<Long, String> = db.write { c ->
        val parent = deletionFact(c, db, ws, account, null)
        val t = CASH_TRANSACTIONS
        c.jooq(db.sqlite).update(t).set(t.RECORD_ORIGIN, "manual").set(t.SOURCE_TYPE, null as String?)
            .set(t.STATE, if (draft) "draft" else "posted").where(t.ID.eq(parent)).execute()
        val part = CASH_TRANSACTION_COMPONENTS
        c.jooq(db.sqlite).insertInto(part).set(part.WORKSPACE_ID, ws).set(part.CASH_TRANSACTION_ID, parent)
            .set(part.ACCOUNT_ID, account).set(part.AMOUNT, CashStoredDecimal("-10")).set(part.CURRENCY, "CNY")
            .set(part.ORDINAL, 0L).set(part.LABEL, "").execute()
        val entry = "entry-${UUID.randomUUID()}"
        deletionEntry(c, db, ws, entry, parent, null)
        deletionInsert(c, db, "cash_entry_members", linkedMapOf("workspace_id" to ws, "transaction_id" to parent, "entry_id" to entry,
            "standalone_values" to buildJsonObject { put("category_id", JsonNull); put("note", "保留备注"); put("counterparty", "保留商家"); put("counterparty_account", ""); put("counterparty_account_attrs", JsonArray(emptyList())) },
            "standalone_manual_fields" to JsonArray(emptyList())))
        c.markLedgerChanged(ws)
        parent to entry
    }
    private fun input(parent: Long, entry: String, draft: Boolean = false, note: String = "维护备注") = buildJsonObject {
        put("intent", if (draft) "save_draft" else "edit_entry"); put("transaction_id", parent.toString()); put("expected_revision", 1)
        if (!draft) { put("entry_id", entry); put("expected_entry_revision", 1) }
        put("patch", buildJsonObject { put("note", note) })
    }
    private fun commit(service: MutationPlanService, token: String, plan: JsonObject, key: String): JsonObject = try { service.commit(token,
        plan.getValue("plan_id").jsonPrimitive.content, plan.getValue("plan_revision").jsonPrimitive.long, key) }
        catch (failure: CashImportCommitFailure) { failure.addSuppressed(failure.failure); throw failure }

    @Test fun publicEditWithoutMaterializedStateCommitsCanonicalEntryAndReplaysReceipt() = authenticated { db, ws, account, token, _ ->
        val (parent, entry) = fixture(db, ws, account); val before = snapshot(db, ws)
        val service = MutationPlanService(db, canonicalEntries = true); val plan = service.preview(token, input(parent, entry))
        val receipt = commit(service, token, plan, "canonical-edit")
        assertEquals(JsonPrimitive(true), receipt["committed"])
        val after = snapshot(db, ws)
        assertEquals(before.ledgerSeq.toLong() + 1, after.ledgerSeq.toLong())
        assertEquals(before.cashFacts(), after.cashFacts())
        assertEquals("维护备注", after.facts.cashEntries.single().note)
        val view = LocalCashLedgerQuery(after) { _, _ -> error("unused") }.record(parent.toString(), true)
        assertEquals(JsonPrimitive("维护备注"), view.getValue("entry").jsonObject.getValue("fields").jsonObject["note"])
        assertEquals(after.ledgerSeq, receipt.getValue("ledger_seq").jsonPrimitive.content)
        assertEquals(after.ledgerSeq.toLong(), receipt.getValue("projection_version").jsonPrimitive.long)
        assertEquals(receipt, commit(service, token, plan, "canonical-edit")); assertEquals(after, snapshot(db, ws))
    }
    private fun LedgerSnapshotDto.cashFacts() = facts.copy(cashEntries = emptyList(), cashEntryMembers = emptyList())
    private fun untouchedEntryFor(snapshot: LedgerSnapshotDto, parent: Long) = snapshot.facts.cashEntryMembers.single { it.transactionId == parent.toString() }.entryId

    @Test fun draftSaveWithoutMaterializedStateRetainsPendingCanonicalLocation() = authenticated { db, ws, account, token, _ ->
        val (parent, entry) = fixture(db, ws, account, draft = true)
        val service = MutationPlanService(db, canonicalEntries = true); val plan = service.preview(token, input(parent, entry, draft = true))
        val receipt = commit(service, token, plan, "canonical-draft")
        assertEquals(JsonPrimitive(true), receipt["committed"])
        assertEquals(JsonPrimitive(1), receipt["pending_item_count"])
        val after = snapshot(db, ws)
        assertEquals("draft", after.facts.cashTransactions.single().state)
        assertEquals("维护备注", after.facts.cashTransactions.single().note)
        assertEquals(1, LocalCashLedgerQuery(after) { _, _ -> error("unused") }.currentGroup("cash-entry:$entry").members.size)
    }
    @Test fun samePublicValueIsANoopWithExactLargeLedgerVersion() = authenticated { db, ws, account, token, _ ->
        val (parent, entry) = fixture(db, ws, account)
        db.write { c -> c.jooq(db.sqlite).update(WORKSPACES).set(WORKSPACES.LEDGER_SEQ, 9007199254740993L).where(WORKSPACES.ID.eq(ws)).execute() }
        val before = snapshot(db, ws)
        val service = MutationPlanService(db, canonicalEntries = true)
        val receipt = commit(service, token, service.preview(token, input(parent, entry, note = "保留备注")), "canonical-noop")
        assertEquals(before, snapshot(db, ws))
        assertEquals("9007199254740993", receipt.getValue("ledger_seq").jsonPrimitive.content)
        assertEquals(9007199254740993L, receipt.getValue("projection_version").jsonPrimitive.long)
        assertEquals(JsonPrimitive("no_changes"), receipt["status"])
    }

    @Test fun draftFinalizeUsesCanonicalCoordinationAndAdvancesOnlyOnce() = authenticated { db, ws, account, token, _ ->
        val (parent, entry) = fixture(db, ws, account, draft = true); val before = snapshot(db, ws)
        val service = MutationPlanService(db, canonicalEntries = true)
        val body = JsonObject(input(parent, entry, draft = true) + ("intent" to JsonPrimitive("finalize_draft")))
        val receipt = commit(service, token, service.preview(token, body), "canonical-finalize")
        val after = snapshot(db, ws)
        assertEquals("posted", after.facts.cashTransactions.single().state)
        assertEquals(entry, after.facts.cashEntryMembers.single().entryId)
        assertEquals(before.ledgerSeq.toLong() + 1, after.ledgerSeq.toLong())
        assertEquals(JsonPrimitive(0), receipt["pending_item_count"])
        assertEquals(after.ledgerSeq, receipt.getValue("ledger_seq").jsonPrimitive.content)
    }

    @Test fun stagesRollbackCanonicalFactsBalancesAndReceiptTogether() {
        for (stage in listOf("record", "projection", "receipt")) authenticated { db, ws, account, token, _ ->
            val (parent, entry) = fixture(db, ws, account, draft = true)
            val service = MutationPlanService(db, canonicalEntries = true, afterCommitStep = { if (it == stage) error("synthetic maintenance $stage failure") })
            val body = JsonObject(input(parent, entry, draft = true) + ("intent" to JsonPrimitive("finalize_draft")))
            val plan = service.preview(token, body); val before = allTables(db, ws)
            val failure = assertFailsWith<CashImportCommitFailure> { commit(service, token, plan, "canonical-rollback-$stage") }
            assertEquals("not_committed", failure.commitState)
            assertEquals(before, allTables(db, ws), stage)
        }
    }

    @Test fun staleSourceAndEntryObservationsAndRevokedWriterRejectWithoutWrites() = authenticated { db, ws, account, token, user ->
        val (parent, entry) = fixture(db, ws, account)
        val service = MutationPlanService(db, canonicalEntries = true)
        val plan = service.preview(token, input(parent, entry))
        db.write { c -> c.jooq(db.sqlite).update(CASH_ENTRIES).set(CASH_ENTRIES.REVISION, 2L).where(CASH_ENTRIES.ID.eq(entry)).execute(); c.markLedgerChanged(ws) }
        val entryChanged = allTables(db, ws)
        assertFailsWith<MutationPlanFieldFailure> { commit(service, token, plan, "canonical-stale-entry") }
        assertEquals(entryChanged, allTables(db, ws))
        db.write { c -> c.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.ROW_REVISION, 2L).where(CASH_TRANSACTIONS.ID.eq(parent)).execute(); c.markLedgerChanged(ws) }
        val sourceChanged = allTables(db, ws)
        assertEquals("record_changed", assertFailsWith<MutationPlanFailure> { commit(service, token, plan, "canonical-stale-source") }.code)
        assertEquals(sourceChanged, allTables(db, ws))
        db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer")
            .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(ws)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute() }
        val revoked = allTables(db, ws)
        assertFailsWith<AccessFailure> { commit(service, token, plan, "canonical-revoked") }
        assertEquals(revoked, allTables(db, ws))
    }

    @Test fun defaultMaintenancePathRemainsUnswitched() = authenticated { db, ws, account, token, _ ->
        val (parent, entry) = fixture(db, ws, account)
        val service = MutationPlanService(db); val plan = service.preview(token, input(parent, entry)); val before = allTables(db, ws)
        val failure = assertFailsWith<CashImportCommitFailure> { commit(service, token, plan, "default-still-materialized") }
        assertEquals("projection.unavailable", (failure.failure as CashProjectionFailure).code)
        assertEquals(before, allTables(db, ws))
    }

    @Test fun removingConfirmedRelationSplitsBothOldMembersAndKeepsUnselectedEntry() {
        for (inherit in listOf(true, false)) authenticated { db, ws, account, token, _ ->
        val (parent, _) = fixture(db, ws, account)
        val (other, _) = fixture(db, ws, account)
        val (untouched, _) = fixture(db, ws, account)
        db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.RECORD_ORIGIN, "legacy").set(CASH_TRANSACTIONS.SOURCE_TYPE, "graph-read-fixture")
                .where(CASH_TRANSACTIONS.ID.`in`(parent, other)).execute()
            val ids = sql.select(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID, CASH_TRANSACTION_COMPONENTS.ID).from(CASH_TRANSACTION_COMPONENTS)
                .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(ws)).fetch().associate { it.value1()!! to it.value2()!! }
            val relation = deletionRelation(c, db, ws, parent, other)
            sql.update(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.KIND, "payment_mirror").set(TRANSACTION_RELATIONS.STATE, "confirmed")
                .set(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, ids.getValue(parent)).set(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID, ids.getValue(other))
                .set(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, ids.getValue(parent)).set(TRANSACTION_RELATIONS.PRIMARY_ROLE, "expense")
                .set(TRANSACTION_RELATIONS.SECONDARY_ROLE, "expense").where(TRANSACTION_RELATIONS.ID.eq(relation)).execute()
            c.markLedgerChanged(ws)
        }
        CashProjectionService(db, ws).rebuild()
        db.write { c ->
            val sql = c.jooq(db.sqlite)
            val group = sql.select(CASH_ENTRY_MEMBERS.ENTRY_ID).from(CASH_ENTRY_MEMBERS).where(CASH_ENTRY_MEMBERS.TRANSACTION_ID.eq(parent))
                .fetchSingle(CASH_ENTRY_MEMBERS.ENTRY_ID)!!
            sql.update(CASH_ENTRIES).set(CASH_ENTRIES.NOTE, "共同手工备注").set(CASH_ENTRIES.MANUAL_FIELDS, JsonArray(listOf(JsonPrimitive("note"))))
                .where(CASH_ENTRIES.ID.eq(group)).execute()
            for ((id, note) in listOf(parent to "原独立甲", other to "原独立乙")) {
                val member = sql.select(CASH_ENTRY_MEMBERS.STANDALONE_VALUES).from(CASH_ENTRY_MEMBERS)
                    .where(CASH_ENTRY_MEMBERS.TRANSACTION_ID.eq(id)).fetchSingle(CASH_ENTRY_MEMBERS.STANDALONE_VALUES)!!.jsonObject
                sql.update(CASH_ENTRY_MEMBERS).set(CASH_ENTRY_MEMBERS.STANDALONE_VALUES, JsonObject(member + ("note" to JsonPrimitive(note))))
                    .set(CASH_ENTRY_MEMBERS.STANDALONE_MANUAL_FIELDS, JsonArray(listOf(JsonPrimitive("note"))))
                    .where(CASH_ENTRY_MEMBERS.TRANSACTION_ID.eq(id)).execute()
            }
            c.markLedgerChanged(ws)
        }
        val before = snapshot(db, ws)
        assertEquals(2, before.facts.cashEntries.size)
        db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.deleteFrom(CASH_PROJECTION_RELATIONS).where(CASH_PROJECTION_RELATIONS.WORKSPACE_ID.eq(ws)).execute()
            sql.deleteFrom(CASH_PROJECTION_MEMBERS).where(CASH_PROJECTION_MEMBERS.WORKSPACE_ID.eq(ws)).execute()
            sql.deleteFrom(CASH_PROJECTIONS).where(CASH_PROJECTIONS.WORKSPACE_ID.eq(ws)).execute()
            sql.deleteFrom(CASH_PROJECTION_STATES).where(CASH_PROJECTION_STATES.WORKSPACE_ID.eq(ws)).execute()
            sql.deleteFrom(CASH_PROJECTION_DATASETS).where(CASH_PROJECTION_DATASETS.WORKSPACE_ID.eq(ws)).execute()
        }
        val service = MutationPlanService(db, canonicalEntries = true)
        val body = buildJsonObject { put("intent", "maintain_relations"); put("transaction_id", parent.toString()); put("expected_revision", 1); put("patch", JsonObject(emptyMap())) }
        val first = service.preview(token, body)
        val choice = first.getValue("items").jsonArray.flatMap { item -> service.item(token, first.getValue("plan_id").jsonPrimitive.content,
            item.jsonObject.getValue("preview_item_id").jsonPrimitive.content).getValue("relation_choices").jsonArray }.single().jsonObject
        val plan = service.preview(token, JsonObject(body + mapOf("edit_context_id" to first.getValue("edit_context_id"), "previous_plan_id" to first.getValue("plan_id"),
            "expected_plan_revision" to first.getValue("plan_revision"), "relation_choices" to JsonArray(listOf(buildJsonObject {
                put("suggestion_id", choice.getValue("suggestion_id")); put("action", "remove"); put("inherit_common_fields", inherit)
            })))))
        val receipt = commit(service, token, plan, "canonical-split")
        val after = snapshot(db, ws)
        assertEquals(before.ledgerSeq.toLong() + 1, after.ledgerSeq.toLong())
        assertEquals(3, after.facts.cashEntries.size)
        assertEquals(3, after.facts.cashEntryMembers.size)
        assertEquals(before.facts.cashTransactions, after.facts.cashTransactions)
        assertFalse(after.facts.transactionRelations.single().active)
        val oldGroup = before.facts.cashEntries.single { it.id != before.facts.cashEntryMembers.single { member -> member.transactionId == untouched.toString() }.entryId }
        assertEquals(oldGroup.identityTransactionId, after.facts.cashEntries.single { it.id == oldGroup.id }.identityTransactionId)
        assertTrue(after.facts.cashEntries.all { it.splitInherit == null })
        val splitEntries = after.facts.cashEntries.filter { it.id != untouchedEntryFor(before, untouched) }
        assertTrue(splitEntries.all { it.manualFields == JsonArray(listOf(JsonPrimitive("note"))) })
        for (split in splitEntries) assertEquals(if (inherit) "共同手工备注" else if (split.rootTransactionId == parent.toString()) "原独立甲" else "原独立乙", split.note)
        val expectedMembers = before.facts.cashEntryMembers.map { member ->
            member.copy(entryId = "", standaloneValues = if (inherit && member.transactionId in setOf(parent.toString(), other.toString()))
                JsonObject(member.standaloneValues.jsonObject + ("note" to JsonPrimitive("共同手工备注"))) else member.standaloneValues)
        }
        assertEquals(expectedMembers.sortedBy { it.transactionId }, after.facts.cashEntryMembers.map { it.copy(entryId = "") }.sortedBy { it.transactionId })
        val untouchedEntry = before.facts.cashEntryMembers.single { it.transactionId == untouched.toString() }.entryId
        assertEquals(before.facts.cashEntries.single { it.id == untouchedEntry }, after.facts.cashEntries.single { it.id == untouchedEntry })
        assertEquals(JsonPrimitive(1), receipt.getValue("summary").jsonObject["relations_removed"])
        assertEquals(receipt, commit(service, token, plan, "canonical-split")); assertEquals(after, snapshot(db, ws))
    }
    }

    @Test fun mergingIdentityDraftRetiresOnlySourceEntryAndKeepsTargetIdentityAndManualNote() = authenticated { db, ws, account, token, _ ->
        val (source, sourceEntry) = fixture(db, ws, account, draft = true)
        val (target, targetEntry) = fixture(db, ws, account)
        val issue = db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.RECORD_ORIGIN, "legacy").set(CASH_TRANSACTIONS.SOURCE_TYPE, "graph-read-fixture")
                .where(CASH_TRANSACTIONS.ID.`in`(source, target)).execute()
            sql.update(CASH_ENTRIES).set(CASH_ENTRIES.NOTE, "来源手工备注").set(CASH_ENTRIES.MANUAL_FIELDS, JsonArray(listOf(JsonPrimitive("note"))))
                .where(CASH_ENTRIES.ID.eq(sourceEntry)).execute()
            deletionInsert(c, db, "cash_source_bindings", linkedMapOf("workspace_id" to ws, "transaction_id" to source, "source_type" to "graph-read-fixture",
                "source_scope_kind" to "file_local", "source_scope_key" to "synthetic-file", "identity_kind" to "file_occurrence", "identity_key" to "row-1",
                "identity_strength" to "file_local", "strategy_version" to "fixture", "coverage" to JsonObject(emptyMap()), "revision" to 1L,
                "created_at" to deletionTime, "updated_at" to deletionTime))
            c.markLedgerChanged(ws)
            CashSourceWriteRepository(CashSqlContext(c, ws, db.sqlite)).saveIssue(source, "identity", "source_identity_ambiguous", candidates = mapOf(target to 1L))
        }
        val before = snapshot(db, ws)
        val service = MutationPlanService(db, canonicalEntries = true)
        val plan = service.preview(token, buildJsonObject {
            put("intent", "resolve_issue"); put("transaction_id", source.toString()); put("expected_revision", 1); put("patch", JsonObject(emptyMap()))
            put("issue_resolutions", JsonArray(listOf(buildJsonObject {
                put("issue_id", issue.id.toString()); put("expected_issue_revision", issue.revision); put("resolution", "merge_into")
                put("target_transaction_id", target.toString()); put("expected_target_revision", 1)
            })))
        })
        val receipt = commit(service, token, plan, "canonical-merge")
        val after = snapshot(db, ws)
        assertEquals(before.ledgerSeq.toLong() + 1, after.ledgerSeq.toLong())
        assertNotNull(after.facts.cashTransactions.single { it.id == source.toString() }.deletedAt)
        assertNull(after.facts.cashTransactions.single { it.id == target.toString() }.deletedAt)
        val entry = after.facts.cashEntries.single()
        assertEquals(targetEntry, entry.id); assertEquals(target.toString(), entry.identityTransactionId)
        assertEquals("来源手工备注", entry.note)
        assertEquals(JsonArray(listOf(JsonPrimitive("note"))), entry.manualFields)
        assertEquals(target.toString(), after.facts.cashEntryMembers.single().transactionId)
        assertEquals(target.toString(), after.facts.cashSourceBindings.single().transactionId)
        assertTrue(after.facts.cashTransactionIssues.none { it.id == issue.id.toString() })
        assertEquals(JsonPrimitive(target.toString()), receipt["retained_transaction_id"])
        assertEquals(receipt, commit(service, token, plan, "canonical-merge")); assertEquals(after, snapshot(db, ws))
    }

    @Test fun pendingCountIncludesAnUnselectedMemberInTheSameCanonicalGroup() = authenticated { db, ws, account, token, _ ->
        val (parent, entry) = fixture(db, ws, account)
        val (other, otherEntry) = fixture(db, ws, account)
        db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.update(CASH_ENTRY_MEMBERS).set(CASH_ENTRY_MEMBERS.ENTRY_ID, entry).where(CASH_ENTRY_MEMBERS.TRANSACTION_ID.eq(other)).execute()
            sql.deleteFrom(CASH_ENTRIES).where(CASH_ENTRIES.ID.eq(otherEntry)).execute()
            sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.RECORD_ORIGIN, "legacy").set(CASH_TRANSACTIONS.SOURCE_TYPE, "graph-read-fixture")
                .where(CASH_TRANSACTIONS.ID.`in`(parent, other)).execute()
            val ids = sql.select(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID, CASH_TRANSACTION_COMPONENTS.ID).from(CASH_TRANSACTION_COMPONENTS)
                .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(ws)).fetch().associate { it.value1()!! to it.value2()!! }
            val relation = deletionRelation(c, db, ws, parent, other)
            sql.update(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.KIND, "payment_mirror").set(TRANSACTION_RELATIONS.STATE, "confirmed")
                .set(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, ids.getValue(parent)).set(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID, ids.getValue(other))
                .set(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, ids.getValue(parent)).set(TRANSACTION_RELATIONS.PRIMARY_ROLE, "expense")
                .set(TRANSACTION_RELATIONS.SECONDARY_ROLE, "expense").where(TRANSACTION_RELATIONS.ID.eq(relation)).execute()
            CashSourceWriteRepository(CashSqlContext(c, ws, db.sqlite)).saveIssue(other, "source-pending", "source_update_pending", pendingFields = mapOf("note" to "新来源备注"))
        }
        val before = snapshot(db, ws)
        val service = MutationPlanService(db, canonicalEntries = true)
        val plan = service.preview(token, input(parent, entry, note = "保留备注"))
        val receipt = commit(service, token, plan, "canonical-pending-member")
        assertEquals(before, snapshot(db, ws))
        assertEquals(JsonPrimitive(1), receipt["pending_item_count"])
        assertEquals(JsonPrimitive(0), receipt["needs_attention_count"])
    }

    private class SqlProbe {
        var active = false
        var queries = 0
        private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.cause ?: failure }
        private fun executed(sql: String) {
            if (!active) return
            check(!sql.contains("cash_projection", ignoreCase = true)) { "materialized SQL in canonical maintenance: $sql" }
            queries++
        }
        fun decorate(connection: java.sql.Connection): java.sql.Connection = java.lang.reflect.Proxy.newProxyInstance(
            java.sql.Connection::class.java.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
            val result = invoke(connection, method, args)
            when (method.name) {
                "prepareStatement" -> {
                    val sql = args?.firstOrNull() as String
                    java.lang.reflect.Proxy.newProxyInstance(java.sql.PreparedStatement::class.java.classLoader,
                        arrayOf(java.sql.PreparedStatement::class.java)) { _, operation, values ->
                        if (operation.name.startsWith("execute")) executed(sql)
                        invoke(checkNotNull(result), operation, values)
                    }
                }
                "createStatement" -> java.lang.reflect.Proxy.newProxyInstance(java.sql.Statement::class.java.classLoader,
                    arrayOf(java.sql.Statement::class.java)) { _, operation, values ->
                    if (operation.name.startsWith("execute") && values?.firstOrNull() is String) executed(values.first() as String)
                    invoke(checkNotNull(result), operation, values)
                }
                else -> result
            }
        } as java.sql.Connection
    }

    @Test fun canonicalCommitWithUnrelatedFactsDoesNotTouchMaterializationAndHasBoundedCost() {
        val probe = SqlProbe()
        authenticated(probe::decorate) { db, ws, account, token, _ ->
            val (parent, entry) = fixture(db, ws, account)
            db.write { c ->
                val unrelated = List(1000) { deletionFact(c, db, ws, account, null) }.toSet()
                maintainCanonicalCashEntries(CashSqlContext(c, ws, db.sqlite), unrelated)
            }
            val service = MutationPlanService(db, canonicalEntries = true)
            val durations = mutableListOf<Double>(); val counts = mutableListOf<Int>()
            repeat(7) { index ->
                val current = snapshot(db, ws).facts.cashEntries.single { it.id == entry }
                val body = JsonObject(input(parent, entry, note = "cost-$index") + ("expected_entry_revision" to JsonPrimitive(current.revision.toLong())))
                val plan = service.preview(token, body)
                probe.queries = 0; probe.active = true
                val started = System.nanoTime()
                try { commit(service, token, plan, "canonical-cost-$index") }
                finally { probe.active = false }
                val ms = (System.nanoTime() - started) / 1_000_000.0
                if (index >= 2) { durations += ms; counts += probe.queries }
            }
            assertTrue(durations.all { it <= 5000 }, durations.toString())
            assertTrue(counts.all { it <= 250 }, counts.toString())
            val result = snapshot(db, ws)
            assertEquals(1001, result.facts.cashEntries.size)
            assertEquals(1000, result.facts.cashEntries.count { it.id != entry && it.note == "" })
            println(buildJsonObject { put("benchmark", "canonical-maintenance"); put("backend", backend); put("unrelated_records", 1000)
                put("samples_ms", JsonArray(durations.map(::JsonPrimitive))); put("sql_counts", JsonArray(counts.map(::JsonPrimitive)))
                put("max_ms", durations.max()); put("max_sql", counts.max()); put("budget_ms", 5000); put("budget_sql", 250) })
        }
    }

}
