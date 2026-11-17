package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.*
import java.util.UUID
import java.sql.Connection
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LocalCashEntryReadContractTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }
    private fun sequence(db: Database, ws: String) = db.read { c -> c.jooq(db.sqlite).select(WORKSPACES.LEDGER_SEQ)
        .from(WORKSPACES).where(WORKSPACES.ID.eq(ws)).fetchSingle(WORKSPACES.LEDGER_SEQ)!! }
    private fun facts(db: Database, ws: String) = db.read { LedgerSnapshotRepository(it, ws, db.sqlite).facts() }
    private fun authenticated(decorate: (Connection) -> Connection = { it }, action: (Database, String, Long, String, String) -> Unit) = withCashWriteSetTestWorkspace(backend, decorate) { db, ws, account ->
        val user = UUID.randomUUID().toString(); val token = "entry-read-$user"
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
            sql.deleteFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(ws)).execute()
            sql.deleteFrom(CASH_INVESTMENT_FUNDING_RELATIONS).where(CASH_INVESTMENT_FUNDING_RELATIONS.WORKSPACE_ID.eq(ws)).execute()
            sql.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(user)).execute()
            sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
            sql.deleteFrom(USERS).where(USERS.ID.eq(user)).execute()
        } }
    }
    private fun snapshot(db: Database, ws: String) = LedgerSnapshotDto(ws, sequence(db, ws).toString(), CASH_RULES_VERSION, facts(db, ws))
    private fun fixture(db: Database, ws: String, account: Long): Pair<Long, String> = db.write { c ->
        val parent = deletionFact(c, db, ws, account, null)
        val entry = "entry:"+UUID.randomUUID()
        deletionEntry(c, db, ws, entry, parent, null)
        deletionInsert(c, db, "cash_entry_members", linkedMapOf("workspace_id" to ws, "transaction_id" to parent, "entry_id" to entry,
            "standalone_values" to JsonObject(emptyMap()), "standalone_manual_fields" to JsonArray(emptyList())))
        c.markLedgerChanged(ws); parent to entry
    }
    @Test fun currentMembersReadFromCanonicalFactsWithoutAnyMaterializedState() = authenticated { db, ws, account, _, _ ->
        val (id, entry) = fixture(db, ws, account); val before = snapshot(db, ws)
        val actual = Json.encodeToJsonElement(LocalCashLedgerQuery(before) { _, _ -> error("unused") }.currentGroup("cash-entry:$entry"))
        val expected = CashCurrentMembersDto(before.ledgerSeq.toLong(), listOf(CashCurrentMemberDto(id.toString(), 1, "confirmed")))
        assertEquals(Json.encodeToJsonElement(expected), actual)
        assertEquals(before, snapshot(db, ws))
    }
    @Test fun fullEvidenceReadsFromCanonicalFactsWithoutAnyMaterializedState() = authenticated { db, ws, account, _, _ ->
        val (id, entry) = fixture(db, ws, account); val before = snapshot(db, ws)
        val actual = LocalCashLedgerQuery(before) { _, _ -> error("unused") }.evidence("cash-entry:$entry")
        assertEquals(JsonPrimitive(id.toString()), actual.getValue("root_record").jsonObject["id"])
        assertEquals(JsonPrimitive(before.ledgerSeq.toLong()), actual["projection_version"])
        assertEquals(JsonArray(listOf(JsonPrimitive("root"))), actual.getValue("members").jsonArray.single().jsonObject["roles"])
        assertEquals(before, snapshot(db, ws))
    }

    @Test fun recordReadWithoutMaterializedStateHasStableLocation() = authenticated { db, ws, account, _, _ ->
        val (id, entry) = fixture(db, ws, account); val before = snapshot(db, ws)
        val actual = LocalCashLedgerQuery(before) { _, _ -> error("unused") }.record(id.toString(), canWrite = true)
        assertEquals(buildJsonObject {
            put("transaction_id", id.toString()); put("projection_id", "cash-entry:$entry")
            put("attention_count", 0); put("projection_version", before.ledgerSeq.toLong())
        }, actual["projection_ref"])
        assertEquals(before, snapshot(db, ws))
    }
}
