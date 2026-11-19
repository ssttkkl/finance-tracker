package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.util.UUID
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.sql.Statement
import java.sql.PreparedStatement
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CashEntryDeletionContractTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }
    private fun sequence(db: Database, ws: String) = db.read { c -> c.jooq(db.sqlite).select(WORKSPACES.LEDGER_SEQ)
        .from(WORKSPACES).where(WORKSPACES.ID.eq(ws)).fetchSingle(WORKSPACES.LEDGER_SEQ)!! }
    private fun facts(db: Database, ws: String) = db.read { LedgerSnapshotRepository(it, ws, db.sqlite).facts() }
    private fun authenticated(decorate: (Connection) -> Connection = { it }, action: (Database, String, Long, String, String) -> Unit) = withCashWriteSetTestWorkspace(backend, decorate) { db, ws, account ->
        val user = UUID.randomUUID().toString(); val token = "entry-deletion-$user"
        db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.insertInto(USERS).set(USERS.ID, user).set(USERS.EMAIL, "$user@example.invalid")
                .set(USERS.PASSWORD_HASH, "unused").set(USERS.CREATED_AT, deletionTime).execute()
            sql.insertInto(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, ws).set(WORKSPACE_MEMBERSHIPS.USER_ID, user)
                .set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").set(WORKSPACE_MEMBERSHIPS.CREATED_AT, deletionTime).execute()
            sql.insertInto(USER_SESSIONS).set(USER_SESSIONS.ID, UUID.randomUUID().toString()).set(USER_SESSIONS.USER_ID, user)
                .set(USER_SESSIONS.TOKEN_DIGEST, tokenDigest(token)).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, ws)
                .set(USER_SESSIONS.CREATED_AT, deletionTime).set(USER_SESSIONS.EXPIRES_AT, java.time.Instant.parse("2099-01-01T00:00:00Z")).execute()
        }
        try { action(db, ws, account, token, user) } finally { db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(user)).execute()
            sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
            sql.deleteFrom(USERS).where(USERS.ID.eq(user)).execute()
        } }
    }
    private data class Fixture(val group: String, val single: String, val parents: List<Long>, val source: String, val target: String)
    private fun fixture(db: Database, ws: String, account: Long, token: String): Fixture {
        val source = CategoryService(db).create(token, "原分类").id
        val target = CategoryService(db).create(token, "目标分类").id
        return db.write { c ->
            val ids = List(3) { deletionFact(c, db, ws, account, source) }
            val group = "group-${UUID.randomUUID()}"; val single = "single-${UUID.randomUUID()}"
            deletionEntry(c, db, ws, group, ids[0], source)
            deletionEntry(c, db, ws, single, ids[2], source)
            for ((index, id) in ids.withIndex()) deletionInsert(c, db, "cash_entry_members", linkedMapOf(
                "workspace_id" to ws, "transaction_id" to id, "entry_id" to if (index < 2) group else single,
                "standalone_values" to buildJsonObject { put("category_id", source); put("note", "来源独立备注") },
                "standalone_manual_fields" to JsonArray(listOf(JsonPrimitive("note")))))
            c.markLedgerChanged(ws)
            Fixture(group, single, ids, source, target)
        }
    }
    @Test fun previewCanonicalEntriesWithoutMaterializedStateDoesNotWrite() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token); val before = facts(db, ws); val version = sequence(db, ws)
        val result = CashDeletionService(db).previewEntries(token, listOf(data.group, data.single, data.group), version)
        assertEquals(buildJsonObject { put("projection_count", 2); put("transaction_count", 3); put("relation_group_count", 0) }, result)
        assertEquals(before, facts(db, ws)); assertEquals(version, sequence(db, ws))
    }
    @Test fun deleteCanonicalGroupWithoutMaterializedStateKeepsUnselectedEntry() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token); val before = facts(db, ws); val version = sequence(db, ws)
        val result = CashDeletionService(db).deleteEntries(token, listOf(data.group), version, ws)
        assertEquals(JsonPrimitive(true), result["deleted"])
        assertEquals(data.parents.take(2).toSet(), result.getValue("deleted_fact_ids").jsonArray.map { it.jsonPrimitive.long }.toSet())
        assertEquals(version + 1, sequence(db, ws))
        val after = facts(db, ws)
        assertEquals(before.cashEntryMembers.filter { it.transactionId == data.parents.last().toString() }, after.cashEntryMembers)
        assertEquals(before.cashEntries.filter { it.id == data.single }, after.cashEntries)
    }

    private fun confirmedEdge(db: Database, ws: String, account: Long, first: Long, second: Long): Long = db.write { c ->
        val sql = c.jooq(db.sqlite); val part = CASH_TRANSACTION_COMPONENTS
        fun component(fact: Long) = sql.insertInto(part).set(part.WORKSPACE_ID, ws).set(part.CASH_TRANSACTION_ID, fact)
            .set(part.ACCOUNT_ID, account).set(part.AMOUNT, CashStoredDecimal("-10")).set(part.CURRENCY, "CNY")
            .set(part.ORDINAL, 0L).set(part.LABEL, "").returning(part.ID).fetchSingle().id!!
        val primary = component(first); val secondary = component(second)
        deletionRelation(c, db, ws, first, second).also { id ->
            sql.update(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.KIND, "payment_mirror").set(TRANSACTION_RELATIONS.STATE, "confirmed")
                .set(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, primary).set(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID, secondary)
                .set(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, primary).set(TRANSACTION_RELATIONS.PRIMARY_ROLE, "expense")
                .set(TRANSACTION_RELATIONS.SECONDARY_ROLE, "expense").where(TRANSACTION_RELATIONS.ID.eq(id)).execute()
        }
    }

    @Test fun recordDeleteCurrentDissolveClearsEdgeAndKeepsOtherEndpoint() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token); confirmedEdge(db, ws, account, data.parents[0], data.parents[1])
        val version = sequence(db, ws)
        val result = CashDeletionService(db).deleteRecord(token, data.parents[0].toString(), "delete_current_dissolve", ws)
        assertEquals(JsonPrimitive(true), result["deleted"]); assertEquals(JsonPrimitive(1), result["related_count"])
        assertEquals(setOf(data.parents[0]), result.getValue("deleted_fact_ids").jsonArray.map { it.jsonPrimitive.long }.toSet())
        assertEquals(version + 1, sequence(db, ws))
        val after = facts(db, ws)
        assertNotNull(after.cashTransactions.single { it.id == data.parents[0].toString() }.deletedAt)
        assertNull(after.cashTransactions.single { it.id == data.parents[1].toString() }.deletedAt)
        assertTrue(after.transactionRelations.none { it.active })
        assertNotNull(CashRecordDetailService(db).detail(token, data.parents[1].toString()))
        assertEquals("record_not_found", assertFailsWith<CashRecordDetailFailure> { CashRecordDetailService(db).detail(token, data.parents[0].toString()) }.code)
    }

    @Test fun recordDeleteAllRemovesWholeConfirmedGroup() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token); confirmedEdge(db, ws, account, data.parents[0], data.parents[1])
        val result = CashDeletionService(db).deleteRecord(token, data.parents[0].toString(), "delete_all", ws)
        assertEquals(setOf(data.parents[0], data.parents[1]), result.getValue("deleted_fact_ids").jsonArray.map { it.jsonPrimitive.long }.toSet())
        assertEquals(JsonPrimitive(1), result["related_count"])
        val after = facts(db, ws)
        assertEquals(setOf(data.parents[0], data.parents[1]), after.cashTransactions.filter { it.deletedAt != null }.map { it.id.toLong() }.toSet())
        assertTrue(after.transactionRelations.none { it.active })
    }

    @Test fun recordDeleteLaterFailuresRollBackFactsReferencesEntriesBalancesAndVersion() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token); confirmedEdge(db, ws, account, data.parents[0], data.parents[1])
        val before = snapshot(db, ws); val version = sequence(db, ws)
        for (stage in listOf("references", "entries", "balance")) {
            var reached = 0
            val service = CashDeletionService(db, afterStage = { if (it == stage) { reached++; error("synthetic $stage failure") } })
            assertEquals("internal", assertFailsWith<CashDeletionFailure> { service.deleteRecord(token, data.parents[0].toString(), "delete_all", ws) }.code)
            assertEquals(1, reached); assertEquals(before, snapshot(db, ws)); assertEquals(version, sequence(db, ws))
        }
    }

    @Test fun recordDeleteRejectsMissingFactInvalidModeAndViewerWithoutWrites() = authenticated { db, ws, account, token, user ->
        val data = fixture(db, ws, account, token)
        assertEquals("找不到这条流水记录", assertFailsWith<CashDeletionFailure> { CashDeletionService(db).deleteRecord(token, "999999999", "delete_all", ws) }.message)
        assertEquals("无效的删除方式", assertFailsWith<CashDeletionFailure> { CashDeletionService(db).deleteRecord(token, data.parents[0].toString(), "unknown", ws) }.message)
        db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer")
            .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(ws)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute() }
        val before = snapshot(db, ws)
        assertFailsWith<AccessFailure> { CashDeletionService(db).deleteRecord(token, data.parents[0].toString(), "delete_all", ws) }
        assertEquals(before, snapshot(db, ws))
    }

    private fun snapshot(db: Database, ws: String) = db.read { c ->
        val sql = c.jooq(db.sqlite)
        com.finance.tracker.backend.persistence.generated.DefaultSchema.DEFAULT_SCHEMA.tables.mapNotNull { table ->
            val scope = table.field(if (table.name == "workspaces") "id" else "workspace_id", String::class.java) ?: return@mapNotNull null
            table.name to sql.selectFrom(table).where(scope.eq(ws)).fetch().map { row ->
                row.intoArray().map { it?.toString() }
            }.sortedBy { it.toString() }
        }.toMap()
    }

    @Test fun staleMissingEmptyAndNegativeSelectionsRejectWithoutAnyWrites() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token); val base = sequence(db, ws); val before = snapshot(db, ws)
        for ((ids, version, code) in listOf(
            Triple(listOf(data.group), base - 1, "projection.version_conflict"),
            Triple(listOf(data.group), -1L, "projection.version_conflict"),
            Triple(listOf(data.group, "absent"), base, "projection.version_conflict"),
            Triple(emptyList(), base, "projection.delete_required"))) {
            assertEquals(code, assertFailsWith<CashDeletionFailure> { CashDeletionService(db).previewEntries(token, ids, version) }.code)
            assertEquals(code, assertFailsWith<CashDeletionFailure> { CashDeletionService(db).deleteEntries(token, ids, version, ws) }.code)
            assertEquals(before, snapshot(db, ws))
        }
    }

    @Test fun incompleteRootIdentityAndDeletedSelectionsEachRejectAtomically() {
        for (case in listOf("missing_member", "root", "identity", "deleted")) authenticated { db, ws, account, token, _ ->
            val data = fixture(db, ws, account, token)
            db.write { c ->
                val sql = c.jooq(db.sqlite)
                when (case) {
                    "missing_member" -> sql.deleteFrom(CASH_ENTRY_MEMBERS).where(CASH_ENTRY_MEMBERS.TRANSACTION_ID.eq(data.parents.first())).execute()
                    "root" -> sql.update(CASH_ENTRIES).set(CASH_ENTRIES.ROOT_TRANSACTION_ID, data.parents.last())
                        .where(CASH_ENTRIES.ID.eq(data.group)).execute()
                    "identity" -> sql.update(CASH_ENTRIES).set(CASH_ENTRIES.IDENTITY_TRANSACTION_ID, data.parents.last())
                        .where(CASH_ENTRIES.ID.eq(data.group)).execute()
                    else -> sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.DELETED_AT, deletionTime)
                        .where(CASH_TRANSACTIONS.ID.eq(data.parents.first())).execute()
                }
            }
            val before = snapshot(db, ws); val base = sequence(db, ws)
            assertEquals("projection.version_conflict", assertFailsWith<CashDeletionFailure> {
                CashDeletionService(db).previewEntries(token, listOf(data.group), base)
            }.code)
            assertEquals("projection.version_conflict", assertFailsWith<CashDeletionFailure> {
                CashDeletionService(db).deleteEntries(token, listOf(data.group), base, ws)
            }.code)
            assertEquals(before, snapshot(db, ws), case)
        }
    }

    @Test fun confirmedEdgeOutsideSelectionCannotSilentlyExpandDeletionScope() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token)
        db.write { c ->
            val edge = deletionRelation(c, db, ws, data.parents.first(), data.parents.last())
            confirm(c, db, ws, account, edge, data.parents.first(), data.parents.last())
        }
        val before = snapshot(db, ws); val base = sequence(db, ws)
        assertEquals("projection.version_conflict", assertFailsWith<CashDeletionFailure> {
            CashDeletionService(db).previewEntries(token, listOf(data.group), base)
        }.code)
        assertEquals("projection.version_conflict", assertFailsWith<CashDeletionFailure> {
            CashDeletionService(db).deleteEntries(token, listOf(data.group), base, ws)
        }.code)
        assertEquals(before, snapshot(db, ws))
    }

    @Test fun eachExecutionStageRollsBackFactsReferencesEntriesBalancesAndVersion() {
        for (stage in listOf("references", "facts", "entries", "balance")) authenticated { db, ws, account, token, _ ->
            val data = fixture(db, ws, account, token)
            db.write { c ->
                deletionRelation(c, db, ws, data.parents.first(), data.parents.last())
                val context = CashSqlContext(c, ws, db.sqlite)
                CashBalanceService(context).applyChanges(CashDeletionBalanceRows(context).read(data.parents).map { null to it })
            }
            val before = snapshot(db, ws); val base = sequence(db, ws)
            val fault = IllegalStateException("injected $stage"); var reached = false
            val service = CashDeletionService(db, afterStage = { if (it == stage) { reached = true; throw fault } })
            assertSame(fault, assertFailsWith<IllegalStateException> { service.deleteEntries(token, listOf(data.group), base, ws) })
            assertTrue(reached); assertEquals(before, snapshot(db, ws), stage)
        }
    }

    @Test fun completeGroupDeletionClearsEdgesAndCandidateReferencesButKeepsOtherOwners() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token)
        val retained = db.write { c ->
            val sql = c.jooq(db.sqlite)
            val confirmed = deletionRelation(c, db, ws, data.parents.first(), data.parents[1])
            confirm(c, db, ws, account, confirmed, data.parents.first(), data.parents[1])
            deletionRelation(c, db, ws, data.parents.first(), data.parents.last())
            val relation = deletionRelation(c, db, ws, data.parents.last())
            deletionCandidate(c, db, ws, relation, data.parents.first())
            val issue = sql.insertInto(CASH_TRANSACTION_ISSUES).set(CASH_TRANSACTION_ISSUES.WORKSPACE_ID, ws)
                .set(CASH_TRANSACTION_ISSUES.TRANSACTION_ID, data.parents.last()).set(CASH_TRANSACTION_ISSUES.ISSUE_KEY, "test")
                .set(CASH_TRANSACTION_ISSUES.CODE, "source_identity_ambiguous").set(CASH_TRANSACTION_ISSUES.ORIGIN, JsonObject(emptyMap()))
                .set(CASH_TRANSACTION_ISSUES.CREATED_AT, deletionTime).set(CASH_TRANSACTION_ISSUES.UPDATED_AT, deletionTime)
                .returningResult(CASH_TRANSACTION_ISSUES.ID).fetchSingle().value1()!!
            sql.insertInto(CASH_ISSUE_CANDIDATES).set(CASH_ISSUE_CANDIDATES.WORKSPACE_ID, ws).set(CASH_ISSUE_CANDIDATES.ISSUE_ID, issue)
                .set(CASH_ISSUE_CANDIDATES.CANDIDATE_TRANSACTION_ID, data.parents.first()).set(CASH_ISSUE_CANDIDATES.EXPECTED_ROW_REVISION, 1L).execute()
            relation to issue
        }
        val base = sequence(db, ws)
        assertEquals(JsonPrimitive(1), CashDeletionService(db).previewEntries(token, listOf(data.group), base)["relation_group_count"])
        CashDeletionService(db).deleteEntries(token, listOf(data.group), base, ws)
        val after = facts(db, ws)
        assertEquals(1, after.transactionRelations.size)
        assertEquals(retained.first.toString(), after.transactionRelations.single().id)
        assertEquals("2", after.transactionRelations.single().rowRevision)
        assertTrue(after.cashRelationCandidates.isEmpty()); assertTrue(after.cashIssueCandidates.isEmpty())
        assertEquals(retained.second.toString(), after.cashTransactionIssues.single().id)
        assertEquals("2", after.cashTransactionIssues.single().revision)
        assertEquals(listOf(data.single), after.cashEntries.map { it.id })
        assertEquals(base + 1, sequence(db, ws))
    }

    @Test fun balanceDeductsOnlyDeletedSourcesAndRepeatCannotDeductAgain() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token)
        db.write { c ->
            val context = CashSqlContext(c, ws, db.sqlite)
            CashBalanceService(context).applyChanges(CashDeletionBalanceRows(context).read(data.parents).map { null to it })
        }
        fun balance() = db.read { c -> CashSqlContext(c, ws, db.sqlite).snapshot.load().value.getValue("accounts").jsonObject
            .getValue("cash").jsonObject.values.single().jsonObject.getValue("CNY").jsonPrimitive.content.toBigDecimal() }
        assertEquals(0, balance().compareTo(java.math.BigDecimal("-30")))
        val base = sequence(db, ws)
        CashDeletionService(db).deleteEntries(token, listOf(data.group, data.group), base, ws)
        assertEquals(0, balance().compareTo(java.math.BigDecimal("-10")))
        val before = snapshot(db, ws)
        assertFailsWith<CashDeletionFailure> { CashDeletionService(db).deleteEntries(token, listOf(data.group), base, ws) }
        assertFailsWith<CashDeletionFailure> { CashDeletionService(db).deleteEntries(token, listOf(data.group), base + 1, ws) }
        assertEquals(before, snapshot(db, ws))
    }

    @Test fun exactLargeLedgerVersionIsUsedWithoutMaterializedVersion() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token); val base = 9007199254740993L
        db.write { c -> c.jooq(db.sqlite).update(WORKSPACES).set(WORKSPACES.LEDGER_SEQ, base).where(WORKSPACES.ID.eq(ws)).execute() }
        assertEquals(JsonPrimitive(2), CashDeletionService(db).previewEntries(token, listOf(data.group, data.single), base)["projection_count"])
        val result = CashDeletionService(db).deleteEntries(token, listOf(data.group), base, ws)
        assertEquals(JsonPrimitive(base + 1), result["projection_version"])
        assertEquals(base + 1, sequence(db, ws))
    }

    @Test fun previewRechecksOriginalWorkspaceAndRejectsCrossWorkspacePrincipal() = authenticated { db, ws, account, token, user ->
        val data = fixture(db, ws, account, token); val base = sequence(db, ws)
        assertFailsWith<AccessFailure> {
            CashDeletionService(db).previewEntries(token, listOf(data.group), base, Principal(user, "unused", "another"))
        }
        val before = snapshot(db, ws)
        assertFailsWith<AccessFailure> { CashDeletionService(db).deleteEntries(token, listOf(data.group), base, "another") }
        assertEquals(before, snapshot(db, ws))
        val service = CashDeletionService(db, beforeReturn = { db.write { c ->
            c.jooq(db.sqlite).update(USER_SESSIONS).setNull(USER_SESSIONS.ACTIVE_WORKSPACE_ID)
                .where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute()
        } })
        assertFailsWith<AccessFailure> { service.previewEntries(token, listOf(data.group), base) }
        assertEquals(before, snapshot(db, ws))
    }

    @Test fun deletionRechecksWritePermissionAfterWorkspaceLockAndFreezesInputBeforeCallback() {
        for (case in listOf("viewer", "removed", "expired")) authenticated { db, ws, account, token, user ->
            val data = fixture(db, ws, account, token); val base = sequence(db, ws); val before = snapshot(db, ws)
            val service = CashDeletionService(db, beforeWorkspaceLock = { c ->
                val sql = c.jooq(db.sqlite)
                when (case) {
                    "viewer" -> sql.update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer")
                        .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(ws)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
                    "removed" -> sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(ws))
                        .and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
                    else -> sql.update(USER_SESSIONS).set(USER_SESSIONS.EXPIRES_AT, deletionTime)
                        .where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute()
                }
            })
            assertFailsWith<AccessFailure> { service.deleteEntries(token, listOf(data.group), base, ws) }
            assertEquals(before, snapshot(db, ws), case)
        }
        authenticated { db, ws, account, token, _ ->
            val data = fixture(db, ws, account, token); val base = sequence(db, ws); val ids = mutableListOf(data.group)
            val service = CashDeletionService(db, beforeWorkspaceLock = { ids.clear(); ids += data.single })
            val result = service.deleteEntries(token, ids, base, ws)
            assertEquals(data.parents.take(2).toSet(), result.getValue("deleted_fact_ids").jsonArray.map { it.jsonPrimitive.long }.toSet())
            assertEquals(listOf(data.single), facts(db, ws).cashEntries.map { it.id })
        }
    }

    @Test fun ledgerAndEntryOverflowRollBackTheWholeCommand() {
        for (ledger in listOf(false, true)) authenticated { db, ws, account, token, _ ->
            val data = fixture(db, ws, account, token)
            db.write { c ->
                val sql = c.jooq(db.sqlite)
                if (ledger) sql.update(WORKSPACES).set(WORKSPACES.LEDGER_SEQ, Long.MAX_VALUE).where(WORKSPACES.ID.eq(ws)).execute()
                else sql.update(CASH_ENTRIES).set(CASH_ENTRIES.REVISION, Long.MAX_VALUE).where(CASH_ENTRIES.ID.eq(data.group)).execute()
            }
            val before = snapshot(db, ws); val base = sequence(db, ws)
            assertFailsWith<ArithmeticException> { CashDeletionService(db).deleteEntries(token, listOf(data.group), base, ws) }
            assertEquals(before, snapshot(db, ws))
        }
    }
    private fun confirm(c: Connection, db: Database, ws: String, account: Long, edge: Long, primary: Long, secondary: Long) {
        fun part(parent: Long) = c.prepareStatement("INSERT INTO cash_transaction_components(workspace_id,cash_transaction_id,account_id,amount,currency,ordinal) VALUES(?,?,?,'-10','CNY',0) RETURNING id").use {
            it.setString(1, ws); it.setLong(2, parent); it.setLong(3, account)
            it.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
        }
        val a = part(primary); val b = part(secondary)
        c.jooq(db.sqlite).update(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.STATE, "confirmed")
            .set(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, a).set(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID, b)
            .set(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, a).set(TRANSACTION_RELATIONS.PRIMARY_ROLE, "expense")
            .set(TRANSACTION_RELATIONS.SECONDARY_ROLE, "refund").where(TRANSACTION_RELATIONS.ID.eq(edge)).execute()
    }

    private class SqlObservation {
        var active = false
        val statements = mutableListOf<String>()
        private fun call(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
            try { method.invoke(target, *(args ?: emptyArray())) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        fun wrap(connection: Connection): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader,
            arrayOf(Connection::class.java)) { _, method, args ->
            val value = call(connection, method, args)
            if (value !is Statement) value else {
                val prepared = args?.firstOrNull() as? String
                Proxy.newProxyInstance(Statement::class.java.classLoader,
                    arrayOf(if (value is PreparedStatement) PreparedStatement::class.java else Statement::class.java)) { _, operation, parameters ->
                    val text = prepared ?: parameters?.firstOrNull() as? String
                    if (active && operation.name in setOf("execute", "executeUpdate", "executeQuery", "executeBatch", "executeLargeBatch") &&
                        text != null && text !in setOf("COMMIT", "ROLLBACK")) statements += text
                    call(value, operation, parameters)
                }
            }
        } as Connection
    }

    @Test fun hundredSelectedEntriesHaveBoundedSqlRatherThanOneWritePerEntry() {
        val observer = SqlObservation()
        authenticated(observer::wrap) { db, ws, account, token, _ ->
            val ids = db.write { c ->
                val ids = List(101) { index ->
                    val parent = deletionFact(c, db, ws, account, null); val entry = "$ws:bulk:$index"
                    deletionEntry(c, db, ws, entry, parent, null)
                    deletionInsert(c, db, "cash_entry_members", linkedMapOf("workspace_id" to ws, "transaction_id" to parent, "entry_id" to entry,
                        "standalone_values" to JsonObject(emptyMap()), "standalone_manual_fields" to JsonArray(emptyList())))
                    entry
                }
                c.markLedgerChanged(ws); ids
            }
            val base = sequence(db, ws)
            observer.active = true
            val start = System.nanoTime()
            val result = try { CashDeletionService(db).deleteEntries(token, ids.take(100), base, ws) }
                finally { observer.active = false }
            val elapsed = System.nanoTime() - start
            assertEquals(JsonPrimitive(100), result["projection_count"])
            assertEquals(listOf(ids.last()), facts(db, ws).cashEntries.map { it.id })
            println("ENTRY_DELETION_COST backend=$backend count="+observer.statements.size+" elapsed_ns=$elapsed")
            assertTrue(observer.statements.size <= 50, "100 selected entries issued "+observer.statements.size+" SQL commands")
        }
    }
    @Test fun selectionsAcrossMultipleSqlChunksDeleteExactlyTheSelectedGroups() = authenticated { db, ws, account, token, _ ->
        val ids = db.write { c ->
            val ids = List(502) { index ->
                val parent = deletionFact(c, db, ws, account, null); val entry = "$ws:chunk:$index"
                deletionEntry(c, db, ws, entry, parent, null)
                deletionInsert(c, db, "cash_entry_members", linkedMapOf("workspace_id" to ws, "transaction_id" to parent, "entry_id" to entry,
                    "standalone_values" to JsonObject(emptyMap()), "standalone_manual_fields" to JsonArray(emptyList())))
                entry
            }
            c.markLedgerChanged(ws); ids
        }
        val before = facts(db, ws); val base = sequence(db, ws)
        val selected = ids.take(501) + ids.first()
        val impact = CashDeletionService(db).previewEntries(token, selected, base)
        assertEquals(JsonPrimitive(501), impact["projection_count"]); assertEquals(JsonPrimitive(501), impact["transaction_count"])
        val result = CashDeletionService(db).deleteEntries(token, selected, base, ws)
        assertEquals(JsonPrimitive(501), result["transaction_count"])
        assertEquals(501, result.getValue("deleted_fact_ids").jsonArray.size)
        val after = facts(db, ws)
        assertEquals(before.cashEntries.filter { it.id == ids.last() }, after.cashEntries)
        assertEquals(before.cashEntryMembers.filter { it.entryId == ids.last() }, after.cashEntryMembers)
        assertEquals(base + 1, sequence(db, ws))
    }
    @Test fun deletingHundredSelectionsFromTenThousandEntriesHasBoundedSqlLatencyAndMemory() {
        val observation = SqlObservation()
        authenticated(observation::wrap) { db, ws, account, token, _ ->
            val source = CategoryService(db).create(token, "来源分类").id
            val ids = db.write { c ->
                val selected = mutableListOf<String>()
                for (index in 0 until 10000) {
                    val root = deletionFact(c, db, ws, account, source)
                    val id = "$ws:performance:$index"
                    deletionEntry(c, db, ws, id, root, source)
                    val members = if (index < 700) listOf(root, deletionFact(c, db, ws, account, source)) else listOf(root)
                    if (index < 700) selected += id
                    for (member in members) deletionInsert(c, db, "cash_entry_members", linkedMapOf(
                        "workspace_id" to ws, "transaction_id" to member, "entry_id" to id,
                        "standalone_values" to buildJsonObject { put("category_id", source); put("note", "保持原值") },
                        "standalone_manual_fields" to JsonArray(emptyList())))
                }
                c.markLedgerChanged(ws)
                selected
            }
            fun rss(): Long {
                val process = ProcessBuilder("/bin/ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()).start()
                val output = process.inputStream.bufferedReader().readText().trim().toLong()
                check(process.waitFor() == 0)
                return output * 1024
            }
            val baseline = rss()
            val peak = java.util.concurrent.atomic.AtomicLong(baseline)
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val sampleFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val sampler = Thread {
                try { while (running.get()) { peak.accumulateAndGet(rss(), ::maxOf); Thread.sleep(10) } }
                catch (failure: Throwable) { sampleFailure.set(failure) }
            }.apply { isDaemon = true; start() }
            val elapsed = mutableListOf<Long>(); val counts = mutableListOf<Int>()
            var version = sequence(db, ws)
            try {
                for (round in 0 until 7) {
                    observation.statements.clear(); observation.active = true
                    val started = System.nanoTime()
                    val result = try { CashDeletionService(db).deleteEntries(token, ids.drop(round * 100).take(100), version, ws) }
                    finally { observation.active = false }
                    val duration = System.nanoTime() - started
                    version++
                    assertEquals(JsonPrimitive(version), result["projection_version"])
                    assertEquals(JsonPrimitive(100), result["projection_count"])
                    assertEquals(JsonPrimitive(200), result["transaction_count"])
                    if (round >= 2) { elapsed += duration; counts += observation.statements.size }
                }
            } finally { running.set(false); sampler.join() }
            sampleFailure.get()?.let { throw AssertionError("RSS sampling failed", it) }
            val growth = (peak.get() - baseline).coerceAtLeast(0)
            val evidence = buildJsonObject {
                put("backend", backend); put("entries", 10000); put("selected", 100); put("selected_members", 200)
                put("p95_ns", elapsed.max()); put("max_sql", counts.max()); put("sampled_rss_growth_bytes", growth)
                put("sql", JsonArray(observation.statements.map(::JsonPrimitive)))
            }
            println("ENTRY_DELETION_PERFORMANCE $evidence")
            System.getenv("FT_ENTRY_DELETION_PERF_DIR")?.let {
                val path = java.nio.file.Path.of(it); require(path.isAbsolute)
                java.nio.file.Files.createDirectories(path)
                java.nio.file.Files.writeString(path.resolve("entry-deletion-performance-$backend.json"), evidence.toString())
            }
            // One transactional retry on a slower runner can double the statement count; the bound still catches N+1 regressions.
            assertTrue(counts.all { it <= 120 }, "100 selections exceeded 120 SQL statements: $evidence")
            assertTrue(elapsed.max() <= 500000000L, "p95 exceeded 500ms: $evidence")
            assertTrue(growth <= 96L * 1024 * 1024, "RSS growth exceeded 96 MiB: $evidence")
        }
    }
    @Test fun publicDeletionUsesCanonicalMembersWithoutMaterializedState() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token); val version = sequence(db, ws)
        testApplication {
            application { financeModule(db, "http://localhost:18390") }
            fun body(confirmed: Boolean) = buildJsonObject {
                put("projection_ids", JsonArray(listOf(JsonPrimitive("cash-entry:${data.single}"))))
                put("projection_version", version); if (confirmed) put("confirmed", true)
            }.toString()
            val preview = client.post("/api/v1/cash-projections/delete-impact") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody(body(false))
            }
            assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())
            assertEquals(1, Json.parseToJsonElement(preview.bodyAsText()).jsonObject.getValue("transaction_count").jsonPrimitive.int)
            val response = client.delete("/api/v1/cash-projections") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody(body(true))
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertTrue(facts(db, ws).cashTransactions.single { it.id == data.parents[2].toString() }.deletedAt != null)
            assertEquals(version + 1, sequence(db, ws))
        }
    }

    @Test fun publicDeleteRetainsNumericVersionAndIdentifierBudgetBeforeAnyWrite() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token); val version = sequence(db, ws); val before = facts(db, ws)
        testApplication {
            application { financeModule(db, "http://localhost:18390") }
            for ((identifier, value) in listOf("cash-entry:${data.single}" to JsonPrimitive(version.toString()),
                    "cash-entry:${data.single}" to JsonPrimitive(true), "cash-entry:" + "a".repeat(86) to JsonPrimitive(version))) {
                val response = client.delete("/api/v1/cash-projections") {
                    bearerAuth(token); contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("projection_ids", JsonArray(listOf(JsonPrimitive(identifier))))
                        put("projection_version", value); put("confirmed", true) }.toString())
                }
                assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
                assertEquals(before, facts(db, ws)); assertEquals(version, sequence(db, ws))
            }
        }
    }

}
