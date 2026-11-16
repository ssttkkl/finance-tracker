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

    @Test fun recordReadWithoutMaterializedStateHasStableLocation() = authenticated { db, ws, account, _, _ ->
        val (id, entry) = fixture(db, ws, account); val before = snapshot(db, ws)
        val actual = LocalCashLedgerQuery(before) { _, _ -> error("unused") }.record(id.toString(), canWrite = true)
        assertEquals(buildJsonObject {
            put("transaction_id", id.toString()); put("projection_id", "cash-entry:$entry")
            put("attention_count", 0); put("projection_version", before.ledgerSeq.toLong())
        }, actual["projection_ref"])
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

    private fun issue(c: Connection, db: Database, ws: String, parent: Long, code: String, fields: JsonElement, payload: JsonElement = JsonNull): Long =
        c.jooq(db.sqlite).insertInto(CASH_TRANSACTION_ISSUES).set(CASH_TRANSACTION_ISSUES.WORKSPACE_ID, ws)
            .set(CASH_TRANSACTION_ISSUES.TRANSACTION_ID, parent).set(CASH_TRANSACTION_ISSUES.ISSUE_KEY, UUID.randomUUID().toString())
            .set(CASH_TRANSACTION_ISSUES.CODE, code).set(CASH_TRANSACTION_ISSUES.PENDING_FIELDS, fields)
            .set(CASH_TRANSACTION_ISSUES.PENDING_SOURCE_PAYLOAD, payload).set(CASH_TRANSACTION_ISSUES.ORIGIN, JsonObject(emptyMap()))
            .set(CASH_TRANSACTION_ISSUES.CREATED_AT, deletionTime).set(CASH_TRANSACTION_ISSUES.UPDATED_AT, deletionTime).returning().fetchSingle().id!!

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
        val user = ReadServices(db).currentContext(token).userId
        try {
            for (canWrite in listOf(true, false)) {
                db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS)
                    .set(WORKSPACE_MEMBERSHIPS.ROLE, if (canWrite) "admin" else "viewer")
                    .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(ws)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute() }
                for (record in before.facts.cashTransactions.filter { it.deletedAt == null }) {
                    val original = CashRecordDetailService(db).detail(token, record.id)
                    val ref = original.getValue("projection_ref").jsonObject
                    val expected = JsonObject(original + ("projection_ref" to JsonObject(ref +
                        ("projection_version" to JsonPrimitive(before.ledgerSeq.toLong())))))
                    assertEquals(expected, query.record(record.id, canWrite), "full record ${record.id}, canWrite=$canWrite")
                }
            }
        } finally {
            db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "admin")
                .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(ws)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute() }
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
        db.write { c ->
            c.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.RECORD_ORIGIN, "manual_transfer")
                .set(CASH_TRANSACTIONS.MANUAL_TRANSFER_ID, "manual-transfer-fixture").where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(ws)).execute()
            c.markLedgerChanged(ws)
        }
        assertOriginalReads(db, ws, token)
    }

    @Test fun manualAndImportedSourceIssuesMatchOriginalCapabilitiesAndRawPayload() = authenticated { db, ws, account, token, _ ->
        db.write { c ->
            val sql = c.jooq(db.sqlite)
            for (origin in listOf("manual", "import")) for (draft in listOf(false, true)) {
                val parent = deletionFact(c, db, ws, account, null)
                sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.RECORD_ORIGIN, origin)
                    .set(CASH_TRANSACTIONS.STATE, if (draft) "draft" else "posted")
                    .set(CASH_TRANSACTIONS.SOURCE_PAYLOAD, Json.parseToJsonElement("""{"note":"original \\ \"line\" ","n":9007199254740993,"v":"1.2300"}"""))
                    .where(CASH_TRANSACTIONS.ID.eq(parent)).execute()
                part(c, db, ws, parent, account, "-10")
                if (origin == "import") deletionInsert(c, db, "cash_source_bindings", linkedMapOf(
                    "workspace_id" to ws, "transaction_id" to parent, "source_type" to "graph-read-fixture", "source_scope_kind" to "account",
                    "source_scope_key" to "fixture", "identity_kind" to "provider_id", "identity_key" to parent.toString(), "identity_strength" to "provider",
                    "strategy_version" to "fixture", "coverage" to JsonObject(emptyMap()), "created_at" to deletionTime, "updated_at" to deletionTime))
                issue(c, db, ws, parent, "source_update_pending", buildJsonObject { put("note", "incoming") })
                issue(c, db, ws, parent, "source_update_pending", buildJsonObject { put("amount", "-99") })
                issue(c, db, ws, parent, "source_update_pending", JsonArray(emptyList()))
            }
            c.markLedgerChanged(ws)
        }
        assertOriginalReads(db, ws, token) {
            db.write { c -> c.jooq(db.sqlite).update(CASH_TRANSACTIONS)
                .set(CASH_TRANSACTIONS.SOURCE_PAYLOAD, Json.parseToJsonElement("""{"note":"original \\ \"line\" ","n":9007199254740993,"v":1.2300}"""))
                .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(ws)).execute(); c.markLedgerChanged(ws) }
        }
    }

    @Test fun identityIssueCandidatesAndBindingConflictsMatchOriginalReadOnlyPreparation() = authenticated { db, ws, account, token, _ ->
        val draft = db.write { c ->
            val sql = c.jooq(db.sqlite)
            val parent = deletionFact(c, db, ws, account, null); val target = deletionFact(c, db, ws, account, null)
            sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.STATE, "draft").where(CASH_TRANSACTIONS.ID.eq(parent)).execute()
            part(c, db, ws, target, account, "-10")
            val issue = issue(c, db, ws, parent, "source_identity_ambiguous", JsonObject(emptyMap()))
            sql.insertInto(CASH_ISSUE_CANDIDATES).set(CASH_ISSUE_CANDIDATES.WORKSPACE_ID, ws).set(CASH_ISSUE_CANDIDATES.ISSUE_ID, issue)
                .set(CASH_ISSUE_CANDIDATES.CANDIDATE_TRANSACTION_ID, target).set(CASH_ISSUE_CANDIDATES.EXPECTED_ROW_REVISION, 99L).execute()
            c.markLedgerChanged(ws); parent
        }
        assertOriginalReads(db, ws, token)
        db.write { c ->
            for (scope in listOf("scope-a", "scope-b")) deletionInsert(c, db, "cash_source_bindings", linkedMapOf(
                "workspace_id" to ws, "transaction_id" to draft, "source_type" to "graph-read-fixture", "source_scope_kind" to "account",
                "source_scope_key" to scope, "identity_kind" to "provider_id", "identity_key" to "provider", "identity_strength" to "provider",
                "strategy_version" to "fixture", "coverage" to JsonObject(emptyMap()), "created_at" to deletionTime, "updated_at" to deletionTime))
            c.markLedgerChanged(ws)
        }
        assertOriginalReads(db, ws, token)
    }

    @Test fun manualOverrideTruthKeepsOriginalExtremeExponentBoundary() = authenticated { db, ws, account, token, _ ->
        val truth = listOf("0" to false, "-0.00" to false, "1" to true, "0e2147483647" to false,
            "0e2147483648" to false, "0e2147483649" to true, "0.0e2147483649" to false,
            "1e2147483649" to true, "0.0e-2147483647" to true)
        val expected = db.write { c ->
            val values = truth.map { (value, protected) ->
                val parent = deletionFact(c, db, ws, account, null, overrides = Json.parseToJsonElement("{\"record_type\":$value}"))
                c.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.RECORD_ORIGIN, "manual")
                    .where(CASH_TRANSACTIONS.ID.eq(parent)).execute()
                issue(c, db, ws, parent, "source_update_pending", buildJsonObject { put("record_type", "unknown") })
                parent.toString() to protected
            }
            c.markLedgerChanged(ws); values
        }
        assertOriginalReads(db, ws, token)
        val query = LocalCashLedgerQuery(snapshot(db, ws)) { _, _ -> error("unused") }
        for ((id, protected) in expected) assertEquals(JsonArray((if (protected) listOf("apply_source", "keep_existing") else listOf("keep_existing")).map(::JsonPrimitive)),
            query.record(id, true).getValue("issues").jsonArray.single().jsonObject["allowed_resolutions"])
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
        val recordSnapshot = large.copy(facts = large.facts.copy(
            cashTransactions = large.facts.cashTransactions.map { if (it.id == "5000") it.copy(state = "draft") else it },
            cashTransactionIssues = listOf(LedgerCashIssueFactDto("1", "5000", "identity", "source_identity_ambiguous", null,
                JsonNull, JsonObject(emptyMap()), JsonObject(emptyMap()), "1", source.createdAt, source.createdAt)),
            cashSourceBindings = ids.map { LedgerSourceBindingFactDto(it, it, source.sourceType!!, "account", "scope", "provider_id", it,
                "provider", "evidence:$it", "fixture", JsonObject(emptyMap()), "1", source.createdAt, source.createdAt) }))
        val recordStarted = System.nanoTime()
        val recordQuery = LocalCashLedgerQuery(recordSnapshot) { _, _ -> error("unused") }
        val firstRecord = recordQuery.record("5000", true)
        val recordCaptureMs = (System.nanoTime() - recordStarted) / 1_000_000.0
        assertEquals(JsonArray(listOf(JsonPrimitive("create_independent"))), firstRecord.getValue("issues").jsonArray.single().jsonObject["allowed_resolutions"])
        repeat(5) { recordQuery.record("5000", true) }
        val recordSamples = List(7) {
            val start = System.nanoTime()
            repeat(100) { assertEquals(JsonPrimitive("5000"), recordQuery.record("5000", true)["transaction_id"]) }
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        val historyIds = (10_001..12_000).map(Int::toString)
        val aliasTemplate = recordSnapshot.facts.cashSourceBindings.first { it.transactionId == "5000" }
        val aliases = (1..2000).map { index -> aliasTemplate.copy(id = (20_000 + index).toString(), sourceScopeKind = "file_local",
            sourceScopeKey = "alias:$index", identityKind = "file_occurrence", identityKey = "occurrence:$index") }
        val history = historyIds.map { aliasTemplate.copy(id = it, transactionId = it, sourceScopeKey = "history", identityKey = it) }
        val aliasSnapshot = recordSnapshot.copy(facts = recordSnapshot.facts.copy(
            cashTransactions = recordSnapshot.facts.cashTransactions + historyIds.map { source.copy(id = it, recordId = "source:$it", deletedAt = source.createdAt) },
            cashSourceBindings = recordSnapshot.facts.cashSourceBindings + aliases + history))
        val aliasStarted = System.nanoTime()
        val aliasQuery = LocalCashLedgerQuery(aliasSnapshot) { _, _ -> error("unused") }
        assertEquals(JsonArray(listOf(JsonPrimitive("create_independent"))), aliasQuery.record("5000", true)
            .getValue("issues").jsonArray.single().jsonObject["allowed_resolutions"])
        val aliasCaptureMs = (System.nanoTime() - aliasStarted) / 1_000_000.0
        repeat(5) { aliasQuery.record("5000", true) }
        val aliasSamples = List(7) {
            val start = System.nanoTime()
            repeat(100) { assertEquals(JsonPrimitive("5000"), aliasQuery.record("5000", true)["transaction_id"]) }
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        println(buildJsonObject { put("benchmark", "local-entry-evidence"); put("backend", backend)
            put("records", 10_000); put("relations", 10_000); put("capture_ms", captureMs)
            put("repeated_reads", 100); put("median_ms", samples[3]); put("max_ms", samples.last())
            put("record_bindings", 10_000); put("record_capture_ms", recordCaptureMs)
            put("record_median_ms", recordSamples[3]); put("record_max_ms", recordSamples.last())
            put("aliases", aliases.size); put("historical_owners", history.size); put("alias_capture_ms", aliasCaptureMs)
            put("alias_median_ms", aliasSamples[3]); put("alias_max_ms", aliasSamples.last()) })
        assertEquals(original, snapshot(db, ws))
    }
}
