package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CashImportEntryContinuityTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private val empty = JsonArray(emptyList())
    private data class Prepared(val source: LedgerSnapshotDto, val raw: Map<String, Any?>,
        val input: JsonObject, val request: CashWriteSetRequestDto) {
        val plan get() = freezeLocalCashImportPreviewPlan(raw)
        val items get() = plan.getValue("items").jsonArray.map { it.jsonObject }
        val reader get() = LocalCashImportPlanReader(plan) { _, _ -> error("Monthly summaries are unrelated to entry details") }
    }
    private inner class Fixture(val db: Database, val workspace: String, val account: Long, val token: String) {
        fun snapshot() = LedgerReadService(db, now = { at }).snapshot(token)
        fun state() = db.read { c -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table ->
            table.name to c.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            } }
        } }
        fun input(files: List<ParsedImportFile>) = sourceJson(mapOf("account_selections" to scanLocalCashFunding(files).groups.map {
            mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to account.toString(), "expected_account_revision" to 1)
        }, "row_choices" to emptyList<Any>(), "relation_choices" to emptyList<Any>(), "entry_choices" to emptyList<Any>())).jsonObject
        fun prepare(files: List<ParsedImportFile>, input: JsonObject = input(files), previous: Map<String, Any?>? = null): Prepared {
            val before = state(); val source = snapshot()
            val normalized = files.map { file -> file.copy(document = file.document?.let {
                localNormalizedCashDocument(normalizedCashDocumentWire(it) { value -> sourceJson(value).jsonObject })
            }) }
            val (plan, writes) = calculateLocalCashImportBusinessPlan(normalized, source, input, previous,
                requireActive = {}, now = { kotlin.time.Instant.parse(at.toString()) })
            assertEquals(before, state(), "Complete local calculation cannot publish facts or receipts")
            return Prepared(source, plan, input, CashWriteSetRequestDto(source.ledgerSeq, CASH_RULES_VERSION, writes))
        }
        fun edit(files: List<ParsedImportFile>, first: Prepared, patch: JsonObject,
            rows: JsonArray? = null, reference: String? = null): Prepared {
            val ref = reference ?: first.reader.detail(first.items.first().apiText("preview_item_id"))
                .getValue("entry").jsonObject.apiText("edit_ref")
            val input = JsonObject(first.input + mapOf("entry_choices" to buildJsonArray { add(buildJsonObject {
                put("edit_ref", ref); put("patch", patch)
            }) }) + if (rows == null) emptyMap() else mapOf("row_choices" to rows))
            return prepare(files, input, first.raw)
        }
        fun submit(prepared: Prepared, key: String = "entry-confirm", service: CashImportWriteSetSubmissionService =
            CashImportWriteSetSubmissionService(db, now = { at })) = service.submit(token, workspace,
            Json.encodeToJsonElement(prepared.request).jsonObject, key)
        fun note(id: String) = snapshot().facts.let { facts -> facts.cashEntries.single { entry ->
            facts.cashEntryMembers.any { it.transactionId == id && it.entryId == entry.id }
        }.note }
    }
    private fun workspace(action: (Fixture) -> Unit) = withCashWriteSetTestWorkspace(backend) { db, ws, account ->
        val user = UUID.randomUUID().toString(); val token = "entry-continuity-$user"
        db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.insertInto(USERS).set(USERS.ID, user).set(USERS.EMAIL, "$user@example.invalid")
                .set(USERS.PASSWORD_HASH, "unused-test-hash").set(USERS.CREATED_AT, at).execute()
            sql.insertInto(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, ws).set(WORKSPACE_MEMBERSHIPS.USER_ID, user)
                .set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").set(WORKSPACE_MEMBERSHIPS.CREATED_AT, at).execute()
            sql.insertInto(USER_SESSIONS).set(USER_SESSIONS.ID, UUID.randomUUID().toString()).set(USER_SESSIONS.USER_ID, user)
                .set(USER_SESSIONS.TOKEN_DIGEST, tokenDigest(token)).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, ws)
                .set(USER_SESSIONS.CREATED_AT, at).set(USER_SESSIONS.EXPIRES_AT, Instant.parse("2099-01-01T00:00:00Z")).execute()
        }
        try { action(Fixture(db, ws, account, token)) } finally { db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(user)).execute()
            sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
            sql.deleteFrom(USERS).where(USERS.ID.eq(user)).execute()
        } }
    }
    private fun file(ref: String = "file_a", digest: Char = 'a', source: String = "alipay", provider: String = "001",
        note: String = "原始备注", refund: Boolean = false): ParsedImportFile {
        val fields = mapOf("amount" to if (refund) "5.0" else "-12.5", "currency" to "CNY",
            "occurred_at" to if (refund) "2026-07-06T00:00:00Z" else "2026-07-05T00:00:00Z",
            "record_type" to if (refund) "refund" else "consumption", "record_subtype" to "not_applicable",
            "counterparty" to "合成商户", "counterparty_account" to "", "counterparty_account_attrs" to emptyList<String>(),
            "note" to note, "source_payload" to mapOf("交易号" to provider, "备注" to note))
        val document = ParsedCashDocument(source, "table", "fixture-v1", listOf(ParsedCashRow(1, fields,
            paymentMethod = "合成支付账户", providerId = provider,
            sourceExplanation = if (source == "alipay") emptyMap() else mapOf("source_display_name" to "工商银行借记卡"))), "fixture-owner", null,
            CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), 1, 1)
        return ParsedImportFile(ref, digest.toString().repeat(64), "parsed", document, sourceType = source)
    }
    private fun candidates() = listOf(file(), file("file_c", 'c', provider = "another-payment"), file("file_b", 'b', "icbc_debit"))
    private fun notePatch(note: String) = buildJsonObject { put("note", note) }
    private fun rows(action: String) = buildJsonArray { add(buildJsonObject { put("row_ref", "file_a:r1"); put("action", action) }) }

    @Test fun clearedPublicFieldsSurviveRepeatedCalculationAndActualHttpSubmission() = workspace { f ->
        val files = listOf(file()); val first = f.prepare(files)
        val patch = buildJsonObject { put("note", ""); put("category_id", JsonNull); put("counterparty_account_attrs", empty) }
        val once = f.edit(files, first, patch); val twice = f.edit(files, once, patch)
        assertEquals(JsonPrimitive(""), twice.items.single().getValue("display").jsonObject["note"])
        assertEquals(patch, twice.input.getValue("entry_choices").jsonArray.single().jsonObject["patch"])
        testApplication {
            application { financeModule(f.db, clock = { at }) }
            val response = client.post("/api/v1/cash-import/write-set?workspace_id=${f.workspace}") {
                bearerAuth(f.token); header("Idempotency-Key", "clear-entry")
                contentType(ContentType.Application.Json); setBody(Json.encodeToJsonElement(twice.request).toString())
            }
            val body = response.bodyAsText(); assertEquals(200, response.status.value, body)
        }
        val facts = f.snapshot().facts; val parent = facts.cashTransactions.single(); val entry = facts.cashEntries.single()
        assertEquals("原始备注", parent.note); assertEquals(sourceJson(files.single().document!!.rows.single().fields!!["source_payload"]), parent.sourcePayload)
        assertEquals("", entry.note); assertTrue(JsonPrimitive("note") in entry.manualFields.jsonArray); assertEquals(empty, entry.counterpartyAccountAttrs)
    }

    @Test fun completeDiscoveryCombinesExistingAndNewMembersBeforeTheFinalPublicEdit() = workspace { f ->
        f.submit(f.prepare(listOf(file())), "initial")
        val initial = f.snapshot().facts; val old = initial.cashTransactions.single().id; val oldEntry = initial.cashEntries.single().id
        val files = listOf(file("file_r", 'd', provider = "001_1", refund = true)); val first = f.prepare(files)
        assertEquals(1, first.items.size); assertEquals(2, first.items.single().getValue("member_refs").jsonArray.size)
        assertTrue(first.items.single().getValue("member_refs").jsonArray.any { "transaction_id" in it.jsonObject })
        assertTrue(first.plan.getValue("operations").jsonArray.none { it.jsonObject["transaction_id"]?.jsonPrimitive?.content == old })
        val selected = f.edit(files, first, notePatch("public result")); f.submit(selected)
        val facts = f.snapshot().facts
        assertEquals(2, facts.cashTransactions.size); assertEquals(1, facts.transactionRelations.size)
        assertEquals("confirmed", facts.transactionRelations.single().state)
        assertEquals(oldEntry, facts.cashEntries.single().id); assertEquals("public result", f.note(old))
        assertTrue(facts.cashTransactions.all { it.note == "原始备注" })
        val before = f.state()
        val current = LocalCashLedgerQuery(f.snapshot()) { _, _ -> error("No month query") }.record(old, true)
        assertEquals(JsonPrimitive(oldEntry), current.getValue("entry").jsonObject["entry_id"])
        assertEquals(JsonPrimitive("public result"), current.getValue("entry").jsonObject.getValue("fields").jsonObject["note"])
        assertEquals(before, f.state())
    }

    @Test fun sourceChoicesCannotSwallowAnIndependentPublicEditOfTheExistingGroup() {
        for (action in listOf("keep_existing", "defer")) workspace { f ->
            val original = listOf(file(), file("file_r", 'd', provider = "001_1", refund = true))
            f.submit(f.prepare(original), "initial-group")
            assertEquals(1, f.snapshot().facts.transactionRelations.size)
            val id = f.snapshot().facts.cashTransactions.single { it.recordType == "consumption" }.id
            val files = listOf(file(digest = 'e', note = "Incoming source note")); val first = f.prepare(files)
            val selected = f.edit(files, first, notePatch("independent public"), rows(action))
            val receipt = f.submit(selected)
            assertNotEquals(JsonPrimitive("no_changes"), receipt["status"])
            assertEquals(JsonPrimitive(true), receipt.getValue("commit_outcome").jsonObject["has_business_writes"])
            assertEquals(JsonPrimitive(1), receipt.getValue("summary").jsonObject["updated_entries"])
            assertEquals("independent public", f.note(id))
            assertEquals("原始备注", f.snapshot().facts.cashTransactions.single { it.id == id }.note)
        }
    }

    @Test fun invalidEditReferencesAndUnavailableCategoriesCannotChangeTheOriginalPlan() = workspace { f ->
        val foreign = UUID.randomUUID().toString(); val category = UUID.randomUUID().toString()
        f.db.write { c ->
            val sql = c.jooq(f.db.sqlite)
            sql.insertInto(WORKSPACES).set(WORKSPACES.ID, foreign).set(WORKSPACES.NAME, "Foreign workspace").set(WORKSPACES.CREATED_AT, at).execute()
            val t = CASH_CATEGORIES
            sql.insertInto(t).set(t.ID, category).set(t.WORKSPACE_ID, foreign).set(t.PARENT_SCOPE_KEY, "root").set(t.NAME, "Foreign")
                .set(t.NORMALIZED_NAME, "foreign").set(t.CATEGORY_PATH, "/foreign/").set(t.DEPTH, 1).set(t.CREATED_AT, at).set(t.UPDATED_AT, at).execute()
        }
        try {
            val files = listOf(file()); val first = f.prepare(files); val frozen = first.plan; val before = f.state()
            assertTrue(first.source.facts.cashCategories.none { it.id == category })
            for ((reference, patch, code) in listOf(Triple("invented", notePatch("unsafe"), "plan_changed"),
                Triple(null, buildJsonObject { put("category_id", "missing-category") }, "category_unavailable"),
                Triple(null, buildJsonObject { put("category_id", category) }, "category_unavailable"))) {
                assertEquals(code, assertFailsWith<CashImportPlanError> { f.edit(files, first, patch, reference = reference) }.code)
                assertEquals(frozen, first.plan); assertEquals(before, f.state())
            }
        } finally { f.db.write { c -> c.jooq(f.db.sqlite).deleteFrom(WORKSPACES).where(WORKSPACES.ID.eq(foreign)).execute() } }
    }

    @Test fun aSavedEntryRevisionInvalidatesTheOldPublicEditReferenceDuringCompleteRecalculation() = workspace { f ->
        f.submit(f.prepare(listOf(file())), "initial")
        val id = f.snapshot().facts.cashTransactions.single().id
        val files = listOf(file(digest = 'e', note = "Incoming source note")); val first = f.prepare(files)
        val entry = first.source.facts.cashEntries.single(); val beforePlan = first.plan
        val service = MutationPlanService(f.db, now = { at }, canonicalEntries = true)
        val input = buildJsonObject {
            put("intent", "edit_entry"); put("transaction_id", id)
            put("expected_revision", first.source.facts.cashTransactions.single().rowRevision.toLong())
            put("entry_id", entry.id); put("expected_entry_revision", entry.revision.toLong()); put("patch", notePatch("another saved value"))
        }
        val plan = service.preview(f.token, input)
        service.commit(f.token, plan.apiText("plan_id"), plan.getValue("plan_revision").jsonPrimitive.long, "save-entry")
        assertTrue(f.snapshot().facts.cashEntries.single().revision.toLong() > entry.revision.toLong())
        val before = f.state()
        assertEquals("plan_changed", assertFailsWith<CashImportPlanError> { f.edit(files, first, notePatch("stale form")) }.code)
        assertEquals(beforePlan, first.plan); assertEquals(before, f.state()); assertEquals("another saved value", f.note(id))
    }

    @Test fun exactDuplicatesHaveNoEditableTargetsOrAdditionalBusinessEffects() = workspace { f ->
        val files = listOf(file()); f.submit(f.prepare(files), "initial")
        val before = f.snapshot(); val duplicate = f.prepare(files)
        assertTrue(duplicate.items.isEmpty()); assertEquals(JsonObject(emptyMap()), duplicate.plan["entry_targets"])
        assertEquals(JsonPrimitive("no_changes"), f.submit(duplicate)["status"]); assertEquals(before, f.snapshot())
    }

    @Test fun threeCandidateMembersAcrossPreviewItemsRemainReadOnlyAndFullyResolved() = workspace { f ->
        val plan = f.prepare(candidates())
        val item = plan.items.first { it["confirmation_status"] == JsonPrimitive("pending") }
        val before = f.state(); val detail = plan.reader.detail(item.apiText("preview_item_id"))
        val members = detail.getValue("relation_members").jsonArray.map { it.jsonObject }
        assertEquals(3, members.size); assertEquals(3, members.map { it.apiText("row_ref") }.toSet().size)
        assertTrue(members.all { it.getValue("editable_fields").jsonArray.isEmpty() && it.getValue("allowed_actions").jsonArray.isEmpty() })
        assertTrue(members.all { it.getValue("fields").jsonObject["amount"] == JsonPrimitive("-12.5") })
        assertFalse("fields_ref" in detail.toString()); assertEquals(detail, plan.reader.detail(item.apiText("preview_item_id")))
        assertEquals(before, f.state())
    }

    @Test fun actualSourceWriteFailureRollsBackTheEditedEntryAndWholeImport() = rollback("sources")
    @Test fun actualPendingRelationWriteFailureRollsBackTheEditedEntryAndWholeImport() = rollback("relations")

    private fun rollback(stage: String) = workspace { f ->
        val files = if (stage == "relations") candidates() else listOf(file())
        val first = f.prepare(files)
        if (stage == "relations") assertTrue(first.plan.getValue("relation_groups").jsonArray.any { it.jsonObject["action"] == JsonPrimitive("defer") })
        val selected = f.edit(files, first, notePatch("must not partially apply")); val before = f.state()
        var connection: Connection? = null; var reached = 0
        val cause = IllegalStateException("synthetic entry rollback")
        val service = CashImportWriteSetSubmissionService(f.db, now = { at }, beforeWorkspaceLock = { connection = it }, afterStep = {
            if (it == stage) {
                reached++
                val sql = checkNotNull(connection).jooq(f.db.sqlite)
                assertEquals(selected.request.changes.transactions.size,
                    sql.fetchCount(CASH_TRANSACTIONS, CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace)))
                if (stage == "relations") {
                    val relations = sql.selectFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(f.workspace)).fetch()
                    assertEquals(selected.request.changes.relations.size, relations.size); assertTrue(relations.isNotEmpty())
                    assertEquals(setOf("draft"), relations.map { it.state }.toSet())
                    val candidates = selected.request.changes.relations.sumOf { it.candidates.size }
                    assertTrue(candidates > 0)
                    assertEquals(candidates, sql.fetchCount(CASH_RELATION_CANDIDATES, CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(f.workspace)))
                }
                throw cause
            }
        })
        val failure = assertFailsWith<CashImportCommitFailure> { f.submit(selected, service = service) }
        assertSame(cause, failure.failure); assertEquals(1, reached); assertEquals("not_committed", failure.commitState)
        assertEquals(before, f.state())
        val receipt = f.submit(selected); val saved = f.state()
        assertEquals(receipt, f.submit(selected)); assertEquals(saved, f.state())
        assertTrue(f.snapshot().facts.cashEntries.any { it.note == "must not partially apply" })
    }
}
