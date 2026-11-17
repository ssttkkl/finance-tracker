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
import java.sql.PreparedStatement
import java.sql.Statement
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
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
    private fun workspace(decorate: (Connection) -> Connection = { it }, action: (Fixture) -> Unit) = withCashWriteSetTestWorkspace(backend, decorate) { db, ws, account ->
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

    private fun refundBundle(): List<ParsedImportFile> {
        fun amount(file: ParsedImportFile, value: String) = file.copy(document = file.document!!.let { doc ->
            doc.copy(rows = doc.rows.map { it.copy(fields = it.fields!! + ("amount" to value)) })
        })
        return listOf(file(), file("refund_a", 'b', provider = "001_1", refund = true),
            amount(file("refund_b", 'c', provider = "001_2", refund = true), "5"),
            amount(file("refund_c", 'e', provider = "001_3", refund = true), "2.5"),
            amount(file("independent", 'd', provider = "independent"), "-99")).map { original ->
            original.copy(document = original.document!!.let { doc -> doc.copy(rows = doc.rows.map { row ->
                val refund = row.fields!!["record_type"] == "refund"
                row.copy(fields = row.fields!! + ("source_payload" to mapOf("交易订单号" to row.providerId,
                    "商品说明" to "原始商品", "收/支" to if (refund) "收入" else "支出",
                    "交易状态" to if (refund) "退款成功" else "交易成功")),
                    sourceExplanation = mapOf("txn_id" to row.providerId, "payment_method" to "合成支付账户",
                        "platform_status" to if (refund) "退款成功" else "交易成功", "_alipay_direction" to if (refund) "收入" else "支出"))
            }) })
        }
    }

    @Test fun completeZeroRefundBundleConfirmsOrDefersAllEdgesWhileKeepingTheIndependentEntry() {
        for (defer in listOf(false, true)) workspace { f ->
            val files = refundBundle(); val first = f.prepare(files)
            val group = first.plan.getValue("relation_groups").jsonArray.single().jsonObject
            assertEquals(3, group.getValue("edges").jsonArray.size)
            val prepared = if (!defer) first else f.prepare(files, JsonObject(first.input + ("relation_choices" to buildJsonArray {
                add(buildJsonObject { put("suggestion_id", group.getValue("suggestion_id")); put("action", "defer") })
            })), first.raw)
            assertEquals(5, prepared.request.changes.transactions.size)
            assertEquals(3, prepared.request.changes.relations.size)
            assertEquals(1, prepared.request.changes.relationGroups.size)
            assertEquals(if (defer) 5 else 2, prepared.items.size)
            if (defer) {
                assertEquals(4, prepared.items.count { it["confirmation_status"] == JsonPrimitive("pending") })
                assertEquals(1, prepared.items.count { it["confirmation_status"] == JsonPrimitive("confirmed") })
            }
            if (!defer) assertTrue(prepared.items.any {
                it.getValue("display").jsonObject.getValue("amount").jsonPrimitive.content.toBigDecimal().signum() == 0
            })
            val receipt = f.submit(prepared); val facts = f.snapshot().facts
            assertEquals(5, facts.cashTransactions.size); assertEquals(3, facts.transactionRelations.size)
            assertEquals(setOf(if (defer) "draft" else "confirmed"), facts.transactionRelations.map { it.state }.toSet())
            assertEquals(1, facts.transactionRelations.map { it.decisionGroupId }.toSet().size)
            assertEquals(if (defer) 5 else 2, facts.cashEntries.size)
            assertEquals(JsonPrimitive(1), receipt.getValue("summary").jsonObject[if (defer) "relations_deferred" else "relations_confirmed"])
            val reader = LocalCashLedgerQuery(f.snapshot()) { instant, zone ->
                java.time.Instant.parse(instant.toString()).atZone(java.time.ZoneId.of(zone))
                    .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM"))
            }
            if (!defer) {
                val parent = facts.cashTransactions.single { it.recordType == "consumption" && it.amount!!.toBigDecimal().compareTo("-12.5".toBigDecimal()) == 0 }
                val filters = normalizeLocalCashLedgerQueryInput(CashFiltersDto(hideZeroAmount = "false"), "UTC") { from, to, zone ->
                    assertNull(from); assertNull(to); CashLedgerDateBounds(null, null, checkNotNull(zone), null, null)
                }
                val zero = reader.page(filters).items.single { it.amount!!.toBigDecimal().signum() == 0 }
                assertTrue(zero.visible)
                val members = reader.currentGroup(zero.projectionId).members.map { it.transactionId }.toSet()
                assertEquals(4, members.size); assertTrue(parent.id in members)
                val entry = facts.cashEntryMembers.single { it.transactionId == parent.id }.entryId
                assertEquals(members, facts.cashEntryMembers.filter { it.entryId == entry }.map { it.transactionId }.toSet())
            }
            val saved = f.state(); assertEquals(receipt, f.submit(prepared)); assertEquals(saved, f.state())
        }
    }

    private class SecondRelationInsertFault {
        var active = false
        var workspace = ""
        var inserts = 0
        var relationRows = -1
        var parentRows = -1
        var existingId = ""
        var existingNote: String? = null
        var existingRevision: String? = null
        val cause = IllegalStateException("synthetic second joint relation insert")
        private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
            try { method.invoke(target, *(args ?: emptyArray())) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        fun decorate(connection: Connection): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader,
            arrayOf(Connection::class.java)) { _, method, args ->
            val value = invoke(connection, method, args)
            if (value is Statement && method.name in setOf("prepareStatement", "createStatement")) {
                val type = if (value is PreparedStatement) PreparedStatement::class.java else Statement::class.java
                val prepared = args?.firstOrNull() as? String
                Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(type)) { _, action, values ->
                    val result = invoke(value, action, values)
                    val sql = (prepared ?: values?.firstOrNull() as? String).orEmpty().replace("\"", "").replace("`", "").lowercase()
                    if (active && action.name in setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch") &&
                        Regex("\\binsert\\s+(?:or\\s+\\w+\\s+)?into\\s+(?:\\w+\\.)?transaction_relations\\b").containsMatchIn(sql) && ++inserts == 2) {
                        fun count(table: String) = connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE workspace_id=?").use { statement ->
                            statement.setString(1, workspace)
                            statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
                        }
                        relationRows = count("transaction_relations"); parentRows = count("cash_transactions")
                        connection.prepareStatement("SELECT note,row_revision FROM cash_transactions WHERE workspace_id=? AND id=?").use { statement ->
                            statement.setString(1, workspace); statement.setLong(2, existingId.toLong())
                            statement.executeQuery().use { rows -> check(rows.next()); existingNote = rows.getString(1); existingRevision = rows.getString(2) }
                        }
                        throw cause
                    }
                    result
                }
            } else value
        } as Connection
    }

    @Test fun secondActualJointEdgeFailureRollsBackBothEdgesSourcesAndTheIndependentGroup() {
        val fault = SecondRelationInsertFault()
        workspace(fault::decorate) { f ->
            val files = refundBundle()
            f.submit(f.prepare(listOf(files.first())), "existing-expense")
            val original = f.snapshot().facts.cashTransactions.single()
            val updated = files.mapIndexed { index, file -> if (index != 0) file else file.copy(document = file.document!!.let { doc ->
                doc.copy(rows = doc.rows.map { it.copy(fields = it.fields!! + ("note" to "Source updated")) })
            }) }
            val prepared = f.prepare(updated); val before = f.state()
            assertEquals(3, prepared.request.changes.relations.size); assertEquals(5, prepared.request.changes.transactions.size)
            val update = prepared.request.changes.transactions.single { it.target.reference == ExistingCashWriteReferenceDto(original.id) }
            assertEquals("Source updated", update.values.note)
            fault.workspace = f.workspace; fault.existingId = original.id; fault.active = true
            val failure = try { assertFailsWith<CashImportCommitFailure> { f.submit(prepared) } } finally { fault.active = false }
            assertSame(fault.cause, failure.failure); assertEquals("not_committed", failure.commitState)
            assertEquals(2, fault.inserts); assertEquals(2, fault.relationRows); assertEquals(5, fault.parentRows)
            assertEquals("Source updated", fault.existingNote); assertEquals(original.rowRevision.toLong() + 1, fault.existingRevision!!.toLong())
            assertEquals(before, f.state())
            val receipt = f.submit(prepared); val saved = f.state()
            assertEquals(3, f.snapshot().facts.transactionRelations.size); assertEquals(2, f.snapshot().facts.cashEntries.size)
            assertEquals("Source updated", f.snapshot().facts.cashTransactions.single { it.id == original.id }.note)
            assertEquals(receipt, f.submit(prepared)); assertEquals(saved, f.state())
        }
    }

    @Test fun completeDraftReimportReplacesTheComponentAtTheSameOrdinalBeforePosting() = workspace { f ->
        val files = listOf(file()); val firstInput = JsonObject(f.input(files) + ("row_choices" to rows("defer")))
        f.submit(f.prepare(files, firstInput), "draft")
        val before = f.snapshot().facts; val parent = before.cashTransactions.single(); val component = before.cashTransactionComponents.single()
        assertEquals("draft", parent.state); assertEquals("0", component.ordinal)
        val input = JsonObject(f.input(files) + ("row_choices" to buildJsonArray { add(buildJsonObject {
            put("row_ref", "file_a:r1"); put("action", "apply"); put("patch", buildJsonObject {
                put("account_id", f.account.toString()); put("components", buildJsonArray { add(buildJsonObject {
                    put("ordinal", 0); put("account_id", f.account.toString()); put("amount", "-12.5"); put("currency", "CNY")
                }) })
            })
        }) }))
        val prepared = f.prepare(files, input)
        assertEquals("finalize_draft", prepared.plan.getValue("operations").jsonArray.single().jsonObject.apiText("planned_action"))
        val receipt = f.submit(prepared, "finalize"); val after = f.snapshot().facts
        assertEquals(parent.id, after.cashTransactions.single().id); assertEquals("posted", after.cashTransactions.single().state)
        val part = after.cashTransactionComponents.single()
        assertNotEquals(component.id, part.id); assertEquals("0", part.ordinal); assertEquals(f.account.toString(), part.accountId)
        val result = Json.decodeFromJsonElement<CashWriteSetResultDto>(receipt.getValue("write_set_result"))
        assertEquals(listOf(part.id), result.transactions.single().componentIds)
        assertEquals(parent.sourcePayload, after.cashTransactions.single().sourcePayload)
        val saved = f.state(); assertEquals(receipt, f.submit(prepared, "finalize")); assertEquals(saved, f.state())
    }

    @Test fun mixedMultiRowImportPreservesBothAliasesPublicEditsComponentsAndExactBalances() = workspace { f ->
        val second = f.db.write { c -> deletionInsert(c, f.db, "accounts", linkedMapOf(
            "workspace_id" to f.workspace, "name" to "Second account", "type" to "cash", "active" to true, "revision" to 1L,
            "currencies" to JsonArray(listOf(JsonPrimitive("CNY"))), "metadata_json" to JsonObject(emptyMap()),
            "created_at" to at, "updated_at" to at))!! }
        val template = file().document!!
        val sourceRows = (0 until 32).map { index -> template.rows.single().let { row ->
            row.copy(position = index + 1, providerId = "bulk-$index", fields = row.fields!! + mapOf(
                "amount" to "-${12 + index}.5001", "counterparty" to "Merchant $index",
                "source_payload" to mapOf("synthetic_index" to SourceJsonInteger(index))))
        } }
        val document = template.copy(rows = sourceRows, declaredCount = 32, observedCount = 32)
        val files = listOf(file().copy(document = document), file("file_b", 'b').copy(document = document))
        val input = JsonObject(f.input(files) + ("row_choices" to buildJsonArray {
            for (index in 0 until 32) if (index % 2 == 1) add(buildJsonObject {
                put("row_ref", "file_a:r${index + 1}"); put("action", "apply"); put("patch", buildJsonObject {
                    put("cash_granularity", "aggregate"); put("account_id", JsonNull)
                    put("components", buildJsonArray {
                        add(buildJsonObject { put("ordinal", 0); put("account_id", f.account.toString()); put("amount", "-5.0001"); put("currency", "CNY") })
                        add(buildJsonObject { put("ordinal", 1); put("account_id", second.toString()); put("amount", "-${7 + index}.5"); put("currency", "CNY") })
                    })
                })
            })
        }))
        val first = f.prepare(files, input)
        assertEquals(32, first.request.changes.transactions.size)
        val choices = buildJsonArray { for (item in first.items) {
            val detail = first.reader.detail(item.apiText("preview_item_id"))
            val ref = detail.getValue("entry").jsonObject.getValue("edit_ref")
            val index = item.getValue("display").jsonObject.apiText("counterparty").removePrefix("Merchant ").toInt()
            add(buildJsonObject { put("edit_ref", ref); put("patch", buildJsonObject { put("note", "人工备注$index") }) })
        } }
        val prepared = f.prepare(files, JsonObject(input + ("entry_choices" to choices)), first.raw)
        val receipt = f.submit(prepared)
        assertEquals(JsonPrimitive(32), receipt.getValue("summary").jsonObject["created_posted"])
        val facts = f.snapshot().facts; assertEquals(32, facts.cashTransactions.size); assertEquals(32, facts.cashEntries.size)
        val expected = linkedMapOf(f.account to java.math.BigDecimal.ZERO, second to java.math.BigDecimal.ZERO)
        f.db.read { c ->
            val context = CashSqlContext(c, f.workspace, f.db.sqlite)
            val source = CashSourceRows.sourceBatchRows(files).rows; val repository = CashSourceReadRepository(context)
            for (index in 0 until 32) {
                fun identity(ref: String): Long {
                    val row = source.getValue(ref)
                    return repository.forKeys(row.bindingSpecs, listOfNotNull(row.stableEvidenceBindingKey)).map { it.transactionId }.toSet().single()
                }
                val id = identity("file_a:r${index + 1}"); assertEquals(id, identity("file_b:r${index + 1}"))
                val parent = facts.cashTransactions.single { it.id == id.toString() }
                assertEquals(sourceJson(mapOf("synthetic_index" to SourceJsonInteger(index))), parent.sourcePayload)
                assertEquals("原始备注", parent.note); assertEquals(0, parent.amount!!.toBigDecimal().compareTo(java.math.BigDecimal("-${12 + index}.5001")))
                val entry = facts.cashEntries.single { it.rootTransactionId == parent.id }
                assertEquals("人工备注$index", entry.note); assertTrue(JsonPrimitive("note") in entry.manualFields.jsonArray)
                val parts = facts.cashTransactionComponents.filter { it.cashTransactionId == parent.id }.sortedBy { it.ordinal.toInt() }
                val values = if (index % 2 == 0) listOf(f.account to "-${12 + index}.5001") else listOf(f.account to "-5.0001", second to "-${7 + index}.5")
                assertEquals(values.size, parts.size)
                for ((part, value) in parts.zip(values)) {
                    assertEquals(value.first.toString(), part.accountId); assertEquals("CNY", part.currency)
                    assertEquals(0, part.amount!!.toBigDecimal().compareTo(value.second.toBigDecimal()))
                    expected[value.first] = expected.getValue(value.first) + value.second.toBigDecimal()
                }
            }
            val payload = context.sql.select(LEDGER_SNAPSHOTS.PAYLOAD).from(LEDGER_SNAPSHOTS)
                .where(LEDGER_SNAPSHOTS.WORKSPACE_ID.eq(f.workspace)).fetchSingle(LEDGER_SNAPSHOTS.PAYLOAD)!!.jsonObject
            for ((account, amount) in expected) assertEquals(0,
                payload.getValue("accounts").jsonObject.getValue("cash").jsonObject.getValue(account.toString()).jsonObject
                    .getValue("CNY").jsonPrimitive.content.toBigDecimal().compareTo(amount))
        }
        val saved = f.state(); assertEquals(receipt, f.submit(prepared)); assertEquals(saved, f.state())
    }

    @Test fun protectedTypeConflictLeavesTheExistingRefundAndAllBusinessFactsUnchanged() = workspace { f ->
        val files = listOf(file(), file("refund", 'b', provider = "001_1", refund = true))
        f.submit(f.prepare(files), "confirmed")
        val before = f.snapshot(); assertEquals(1, before.facts.transactionRelations.size)
        assertEquals("confirmed", before.facts.transactionRelations.single().state); assertTrue(before.facts.transactionRelations.single().active)
        val changed = file(digest = 'e').let { original -> original.copy(document = original.document!!.let { doc ->
            doc.copy(rows = doc.rows.map { it.copy(fields = it.fields!! + ("record_type" to "income")) })
        }) }
        val prepared = f.prepare(listOf(changed)); assertTrue(prepared.items.isEmpty())
        val decision = prepared.plan.getValue("row_decisions").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("skip"), decision["planned_action"]); assertEquals(JsonPrimitive("fixed_field_conflict"), decision["code"])
        assertTrue(prepared.request.changes.transactions.isEmpty()); assertTrue(prepared.request.changes.relations.isEmpty())
        val receipt = f.submit(prepared)
        assertEquals(JsonPrimitive("no_changes"), receipt["status"]); assertEquals(before, f.snapshot())
    }

    @Test fun partialDraftKeepsTheKnownAccountAndInvalidPartsCannotPublishWhileRetainedPartsCanReorder() {
        workspace { f ->
            val files = listOf(file())
            val patch = buildJsonObject { put("components", buildJsonArray { add(buildJsonObject {
                put("ordinal", 0); put("account_id", f.account.toString()); put("amount", JsonNull); put("currency", JsonNull)
            }) }) }
            val input = JsonObject(f.input(files) + ("row_choices" to buildJsonArray { add(buildJsonObject {
                put("row_ref", "file_a:r1"); put("action", "defer"); put("patch", patch)
            }) }))
            f.submit(f.prepare(files, input), "partial")
            val before = f.snapshot(); val part = before.facts.cashTransactionComponents.single()
            assertEquals("draft", before.facts.cashTransactions.single().state)
            assertEquals(f.account.toString(), part.accountId); assertNull(part.amount); assertNull(part.currency)
            val invalid = JsonObject(input + ("row_choices" to buildJsonArray { add(buildJsonObject {
                put("row_ref", "file_a:r1"); put("action", "defer"); put("patch", buildJsonObject { put("components", "invalid") })
            }) }))
            val state = f.state()
            assertEquals("plan_input_invalid", assertFailsWith<CashImportPlanError> { f.prepare(files, invalid) }.code)
            assertEquals(state, f.state()); assertEquals(before, f.snapshot())
        }
        workspace { f ->
            val files = listOf(file())
            fun choices(parts: JsonArray) = JsonObject(f.input(files) + ("row_choices" to buildJsonArray { add(buildJsonObject {
                put("row_ref", "file_a:r1"); put("action", "defer"); put("patch", buildJsonObject {
                    put("cash_granularity", "aggregate"); put("account_id", JsonNull); put("components", parts)
                })
            }) }))
            val initial = buildJsonArray { for (index in 0..1) add(buildJsonObject {
                put("ordinal", index); put("account_id", f.account.toString()); put("amount", "-6.25"); put("currency", "CNY")
            }) }
            f.submit(f.prepare(files, choices(initial)), "two-parts")
            val before = f.snapshot().facts; val old = before.cashTransactionComponents
            assertEquals(2, old.size)
            val swapped = buildJsonArray { for (part in old) add(buildJsonObject {
                put("component_id", part.id); put("ordinal", 1 - part.ordinal.toInt()); put("account_id", part.accountId)
                put("amount", part.amount); put("currency", part.currency)
            }) }
            val prepared = f.prepare(files, choices(swapped)); f.submit(prepared, "reorder")
            val after = f.snapshot().facts
            assertEquals("draft", after.cashTransactions.single().state)
            assertEquals(old.map { it.id }.toSet(), after.cashTransactionComponents.map { it.id }.toSet())
            for (part in after.cashTransactionComponents) assertEquals(1 - old.single { it.id == part.id }.ordinal.toInt(), part.ordinal.toInt())
        }
    }

    @Test fun aNewFileAliasChangesOnlyBindingsAndAnIdenticalReimportKeepsTheCanonicalEntry() = workspace { f ->
        f.submit(f.prepare(listOf(file())), "original-file")
        val before = f.snapshot()
        val files = listOf(file("another_file", 'b'))
        val prepared = f.prepare(files); f.submit(prepared, "new-alias")
        val after = f.snapshot()
        assertEquals(before.facts.cashTransactions, after.facts.cashTransactions)
        assertEquals(before.facts.cashTransactionComponents, after.facts.cashTransactionComponents)
        assertEquals(before.facts.cashEntries, after.facts.cashEntries)
        assertEquals(before.facts.cashEntryMembers, after.facts.cashEntryMembers)
        assertTrue(after.facts.cashSourceBindings.size > before.facts.cashSourceBindings.size)
        assertEquals(before.ledgerSeq.toLong() + 1, after.ledgerSeq.toLong())
        val repeated = f.prepare(files); val receipt = f.submit(repeated, "same-alias")
        assertEquals(JsonPrimitive("no_changes"), receipt["status"]); assertEquals(after, f.snapshot())
        val saved = f.state(); assertEquals(receipt, f.submit(repeated, "same-alias")); assertEquals(saved, f.state())
    }
}
