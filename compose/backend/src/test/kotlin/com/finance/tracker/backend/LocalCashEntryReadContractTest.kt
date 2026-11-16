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
            sql.deleteFrom(CASH_PROJECTION_RELATIONS).where(CASH_PROJECTION_RELATIONS.WORKSPACE_ID.eq(ws)).execute()
            sql.deleteFrom(CASH_PROJECTIONS).where(CASH_PROJECTIONS.WORKSPACE_ID.eq(ws)).execute()
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

    private fun part(c: Connection, db: Database, ws: String, parent: Long, account: Long, amount: String, ordinal: Long = 0): Long =
        c.jooq(db.sqlite).insertInto(CASH_TRANSACTION_COMPONENTS)
            .set(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID, ws).set(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID, parent)
            .set(CASH_TRANSACTION_COMPONENTS.ACCOUNT_ID, account).set(CASH_TRANSACTION_COMPONENTS.AMOUNT, CashStoredDecimal(amount))
            .set(CASH_TRANSACTION_COMPONENTS.CURRENCY, "CNY").set(CASH_TRANSACTION_COMPONENTS.ORDINAL, ordinal)
            .set(CASH_TRANSACTION_COMPONENTS.LABEL, "part:$ordinal").returning().fetchSingle().id!!

    private fun otherAccount(db: Database, ws: String): Long = db.write { c -> deletionInsert(c, db, "accounts", linkedMapOf(
        "workspace_id" to ws, "name" to "Other account", "type" to "security", "active" to true, "revision" to 1L,
        "currencies" to JsonArray(listOf(JsonPrimitive("USD"))), "metadata_json" to JsonObject(emptyMap()),
        "created_at" to deletionTime, "updated_at" to deletionTime))!! }

    private fun assertOriginalReads(db: Database, ws: String, token: String, afterRebuild: (Long) -> Unit = {}) {
        val published = CashProjectionService(db, ws).rebuild()
        afterRebuild(published.getValue("projection_version").jsonPrimitive.long)
        val before = snapshot(db, ws)
        val query = LocalCashLedgerQuery(before) { _, _ -> error("unused") }
        val old = CurrentProjectionReadService(db)
        val decoder = Json { ignoreUnknownKeys = true }
        for (entry in before.facts.cashEntries) {
            val id = "cash-entry:${entry.id}"
            val evidence = old.evidence(token, id)
            val expected = JsonObject(evidence + buildJsonObject {
                put("projection_version", before.ledgerSeq.toLong())
                put("projection", JsonObject(evidence.getValue("projection").jsonObject +
                    ("projection_version" to JsonPrimitive(before.ledgerSeq.toLong()))))
            })
            assertEquals(expected, query.evidence(id), "full evidence for $id")
            val view = expected.getValue("projection").jsonObject
            if (view.getValue("visible").jsonPrimitive.boolean || view.getValue("attention_count").jsonPrimitive.long > 0) {
                val members = decoder.decodeFromJsonElement<CashCurrentMembersDto>(old.detail(token, id))
                assertEquals(members.copy(projectionVersion = before.ledgerSeq.toLong()), query.currentGroup(id))
            } else {
                assertEquals("projection_not_found", assertFailsWith<CashProjectionDomainFailure> { query.currentGroup(id) }.code)
            }
        }
        assertEquals(before, snapshot(db, ws))
    }

    @Test fun standaloneEvidenceMatchesTheOriginalServiceWithComponentsAndInactiveEndpoints() = authenticated { db, ws, account, token, _ ->
        val category = CategoryService(db).create(token, "Evidence category").id
        db.write { c ->
            val ordinal = if (db.sqlite) 2147483648L else Int.MAX_VALUE.toLong()
            val root = deletionFact(c, db, ws, account, category)
            val primary = part(c, db, ws, root, account, "-7.340000000000000001", ordinal)
            part(c, db, ws, root, account, "-5", 0)
            for ((state, active) in listOf("draft" to true, "ignored" to true, "confirmed" to false)) {
                val deleted = deletionFact(c, db, ws, account, category, deleted = true)
                val secondary = part(c, db, ws, deleted, account, "1.2300", ordinal)
                val edge = deletionRelation(c, db, ws, root, deleted, active = active)
                c.jooq(db.sqlite).update(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.STATE, state)
                    .set(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, primary).set(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID, secondary)
                    .set(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, primary).set(TRANSACTION_RELATIONS.PRIMARY_ROLE, "expense")
                    .set(TRANSACTION_RELATIONS.SECONDARY_ROLE, "refund")
                    .where(TRANSACTION_RELATIONS.ID.eq(edge)).execute()
            }
            c.markLedgerChanged(ws)
        }
        assertOriginalReads(db, ws, token)
    }

    @Test fun hiddenZeroAndDraftUnknownMembersMatchOriginalEligibility() = authenticated { db, ws, account, token, _ ->
        db.write { c ->
            val zero = deletionFact(c, db, ws, account, null)
            val draft = deletionFact(c, db, ws, account, null)
            c.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.AMOUNT, CashStoredDecimal("-0.0000"))
                .where(CASH_TRANSACTIONS.ID.eq(zero)).execute()
            c.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.STATE, "draft")
                .setNull(CASH_TRANSACTIONS.AMOUNT).setNull(CASH_TRANSACTIONS.CURRENCY).setNull(CASH_TRANSACTIONS.OCCURRED_AT)
                .where(CASH_TRANSACTIONS.ID.eq(draft)).execute()
            c.markLedgerChanged(ws)
        }
        assertOriginalReads(db, ws, token)
    }

    @Test fun publicOnlyCategoryKeepsOriginalEvidenceDirectoryQualification() = authenticated { db, ws, account, token, _ ->
        fixture(db, ws, account)
        val category = CategoryService(db).create(token, "Public only").id
        assertOriginalReads(db, ws, token) { version ->
            val entry = snapshot(db, ws).facts.cashEntries.single()
            CashClassificationService(db).setCategory(token, JsonArray(listOf(JsonPrimitive("cash-entry:${entry.id}"))),
                JsonPrimitive(version), JsonPrimitive(category))
        }
        val captured = snapshot(db, ws)
        assertTrue(captured.facts.cashTransactions.all { it.categoryId == null })
        assertEquals(category, captured.facts.cashEntries.single().categoryId)
        val id = "cash-entry:${captured.facts.cashEntries.single().id}"
        assertEquals(JsonNull, LocalCashLedgerQuery(captured) { _, _ -> error("unused") }.evidence(id).getValue("projection").jsonObject["category"])
    }

    @Test fun combinedMirrorAndRefundEvidenceMatchesOriginalCompleteMemberOrder() = authenticated { db, ws, account, token, _ ->
        db.write { c ->
            val root = deletionFact(c, db, ws, account, null)
            val mirror = deletionFact(c, db, ws, account, null)
            val refund = deletionFact(c, db, ws, account, null)
            c.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.AMOUNT, CashStoredDecimal("2.500"))
                .set(CASH_TRANSACTIONS.RECORD_TYPE, "refund").where(CASH_TRANSACTIONS.ID.eq(refund)).execute()
            val a = part(c, db, ws, root, account, "-10")
            val b = part(c, db, ws, mirror, account, "-10")
            val r = part(c, db, ws, refund, account, "2.500")
            for ((kind, target, endpoint) in listOf(Triple("payment_mirror", mirror, b), Triple("refund_offset", refund, r))) {
                val edge = deletionRelation(c, db, ws, root, target)
                c.jooq(db.sqlite).update(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.KIND, kind).set(TRANSACTION_RELATIONS.STATE, "confirmed")
                    .set(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, a).set(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID, endpoint)
                    .set(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, a).set(TRANSACTION_RELATIONS.PRIMARY_ROLE, "expense")
                    .set(TRANSACTION_RELATIONS.SECONDARY_ROLE, if (kind == "refund_offset") "refund" else "expense")
                    .set(TRANSACTION_RELATIONS.APPLIED_AMOUNT, CashStoredDecimal(if (kind == "refund_offset") "2.500" else "0"))
                    .where(TRANSACTION_RELATIONS.ID.eq(edge)).execute()
            }
            c.markLedgerChanged(ws)
        }
        assertOriginalReads(db, ws, token)
    }

    @Test fun transferEvidenceMatchesOriginalAccountsAndAmounts() = authenticated { db, ws, account, token, _ ->
        val targetAccount = otherAccount(db, ws)
        db.write { c ->
            val root = deletionFact(c, db, ws, account, null)
            val target = deletionFact(c, db, ws, targetAccount, null)
            val sql = c.jooq(db.sqlite)
            sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.RECORD_TYPE, "transfer_out").set(CASH_TRANSACTIONS.RECORD_SUBTYPE, "ordinary_transfer")
                .where(CASH_TRANSACTIONS.ID.eq(root)).execute()
            sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.AMOUNT, CashStoredDecimal("10"))
                .set(CASH_TRANSACTIONS.RECORD_TYPE, "transfer_in").set(CASH_TRANSACTIONS.RECORD_SUBTYPE, "ordinary_transfer")
                .where(CASH_TRANSACTIONS.ID.eq(target)).execute()
            val a = part(c, db, ws, root, account, "-10"); val b = part(c, db, ws, target, targetAccount, "10")
            val edge = deletionRelation(c, db, ws, root, target)
            sql.update(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.KIND, "transfer_pair").set(TRANSACTION_RELATIONS.STATE, "confirmed")
                .set(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, a).set(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID, b)
                .set(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, a).set(TRANSACTION_RELATIONS.PRIMARY_ROLE, "outgoing")
                .set(TRANSACTION_RELATIONS.SECONDARY_ROLE, "incoming").where(TRANSACTION_RELATIONS.ID.eq(edge)).execute()
            c.markLedgerChanged(ws)
        }
        assertOriginalReads(db, ws, token)
    }

    @Test fun investmentFundingEvidenceMatchesOriginalSafeFieldsAndTransfer() = authenticated { db, ws, account, token, _ ->
        val target = otherAccount(db, ws)
        db.write { c ->
            val root = deletionFact(c, db, ws, account, null)
            val component = part(c, db, ws, root, account, "-10")
            val event = c.jooq(db.sqlite).insertInto(INVESTMENT_EVENTS).set(INVESTMENT_EVENTS.WORKSPACE_ID, ws)
                .set(INVESTMENT_EVENTS.ACCOUNT_ID, target).set(INVESTMENT_EVENTS.OCCURRED_AT, deletionTime)
                .set(INVESTMENT_EVENTS.RECORD_TYPE, "funding").set(INVESTMENT_EVENTS.RECORD_SUBTYPE, "external")
                .set(INVESTMENT_EVENTS.CURRENCY, "USD").set(INVESTMENT_EVENTS.TO_AMOUNT, CashStoredDecimal("2.340000000000000001"))
                .set(INVESTMENT_EVENTS.PAYLOAD, JsonObject(emptyMap())).set(INVESTMENT_EVENTS.CREATED_AT, deletionTime).returning().fetchSingle().id!!
            deletionInsert(c, db, "cash_investment_funding_relations", linkedMapOf("workspace_id" to ws,
                "cash_transaction_component_id" to component, "investment_event_id" to event, "direction" to "cash_to_investment",
                "status" to "accepted", "rule_id" to "fixture", "evidence" to Json.parseToJsonElement("""{"business_day_window":7,"candidate_count":2,"cash_record_type":"investment_out","match_keys":["institution_name","direction","business_day"],"private":"secret"}""")))
            c.markLedgerChanged(ws)
        }
        assertOriginalReads(db, ws, token)
    }

    @Test fun repeatedEvidenceUsesOneCaptureWithTenThousandUnrelatedRelations() = authenticated { db, ws, account, _, _ ->
        val (parent, _) = fixture(db, ws, account)
        db.write { c ->
            val relation = deletionRelation(c, db, ws, parent)
            c.jooq(db.sqlite).update(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.STATE, "ignored")
                .where(TRANSACTION_RELATIONS.ID.eq(relation)).execute()
        }
        val original = snapshot(db, ws)
        val source = original.facts.cashTransactions.single()
        val entry = original.facts.cashEntries.single()
        val member = original.facts.cashEntryMembers.single()
        val relation = original.facts.transactionRelations.single()
        val ids = (1..10_000).map(Int::toString)
        val large = original.copy(facts = original.facts.copy(
            cashTransactions = ids.map { source.copy(id = it, recordId = "source:$it") },
            cashEntries = ids.map { entry.copy(id = "entry:$it", rootTransactionId = it, identityTransactionId = it) },
            cashEntryMembers = ids.map { member.copy(transactionId = it, entryId = "entry:$it") },
            transactionRelations = ids.map { relation.copy(id = it, primaryTransactionId = it,
                secondaryTransactionId = (it.toInt() % 10_000 + 1).toString(), anchorTransactionId = it) }))
        val started = System.nanoTime()
        val query = LocalCashLedgerQuery(large) { _, _ -> error("unused") }
        val id = "cash-entry:entry:5000"
        assertEquals(2, query.evidence(id).getValue("inactive_relation_hints").jsonArray.size)
        val captureMs = (System.nanoTime() - started) / 1_000_000.0
        repeat(5) { query.evidence(id) }
        val samples = List(7) {
            val start = System.nanoTime()
            repeat(100) { assertEquals(JsonPrimitive(large.ledgerSeq.toLong()), query.evidence(id)["projection_version"]) }
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        println(buildJsonObject { put("benchmark", "local-entry-evidence"); put("backend", backend)
            put("records", 10_000); put("relations", 10_000); put("capture_ms", captureMs)
            put("repeated_reads", 100); put("median_ms", samples[3]); put("max_ms", samples.last()) })
        assertEquals(original, snapshot(db, ws))
    }
}
