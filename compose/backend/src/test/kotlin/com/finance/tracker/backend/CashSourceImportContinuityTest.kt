package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.domain.scanLocalCashFunding
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CashSourceImportContinuityTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private inner class Fixture(val db: Database, val workspace: String, val account: Long, val token: String, val user: String) {
        var storageTime: Instant = at
        val store = InMemoryParsedImportStore(clock = { storageTime })
        val maintenance = MutationPlanService(db, now = { at }, staging = store)
        fun snapshot() = LedgerReadService(db, now = { at }).snapshot(token)
        fun state() = db.read { c -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table ->
            table.name to c.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            } }
        } }
        fun detail(id: String) = CashRecordDetailService(db, now = { at }).detail(token, id)
        fun balances() = db.read { CashSqlContext(it, workspace, db.sqlite, now = { at }).snapshot.load().value }
        fun bindings(id: String) = snapshot().facts.cashSourceBindings.filter { it.transactionId == id }
        fun submit(file: ParsedImportFile, action: String, key: String): JsonObject {
            val before = state()
            val prepared = currentCashImportTestPlan(db, token, workspace, listOf(file), at) { files ->
                sourceJson(mapOf("account_selections" to scanLocalCashFunding(files).groups.map { group ->
                    mapOf("source_account_ref" to group.sourceAccountRef, "account_id" to account.toString(), "expected_account_revision" to 1)
                }, "row_choices" to listOf(mapOf("row_ref" to "${file.fileRef}:r1", "action" to action)),
                    "relation_choices" to emptyList<Any>(), "entry_choices" to emptyList<Any>())).jsonObject
            }
            assertEquals(before, state(), "Local calculation cannot publish facts or receipts")
            return CashImportWriteSetSubmissionService(db, now = { at }).submit(token, workspace,
                Json.encodeToJsonElement(prepared.request).jsonObject, key)
        }
        fun resultId(receipt: JsonObject) = ReceiptReadService(db, now = { at }).results(token, receipt.apiText("receipt_id"),
            "row", ReceiptPage(0, 200)).getValue("items").jsonArray.single().jsonObject.apiText("transaction_id")
    }
    private fun workspace(action: (Fixture) -> Unit) = withCashWriteSetTestWorkspace(backend) { db, ws, account ->
        val user = UUID.randomUUID().toString(); val token = "source-continuity-$user"
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
        try { action(Fixture(db, ws, account, token, user)) } finally { db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(user)).execute()
            sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
            sql.deleteFrom(USERS).where(USERS.ID.eq(user)).execute()
        } }
    }
    private fun file(ref: String, digest: Char, provider: String = "001", counterparty: String = "合成商户",
        payload: Any? = mapOf("交易号" to provider, "备注" to "原始备注")): ParsedImportFile {
        val fields = mapOf("amount" to "-12.5", "currency" to "CNY", "occurred_at" to "2026-07-05T00:00:00Z",
            "record_type" to "consumption", "record_subtype" to "not_applicable", "counterparty" to counterparty,
            "counterparty_account" to "", "counterparty_account_attrs" to emptyList<String>(), "note" to "原始备注", "source_payload" to payload)
        val document = ParsedCashDocument("alipay", "table", "fixture-v1", listOf(ParsedCashRow(1, fields, "支付宝余额", provider)),
            "fixture-owner", "合成支付账户", CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), 1, 1)
        return ParsedImportFile(ref, digest.toString().repeat(64), "parsed", document, sourceType = "alipay")
    }

    private fun bankFile(ref: String, digest: Char): ParsedImportFile {
        val fields = mapOf("amount" to "-7.50", "currency" to "CNY", "occurred_at" to "2026-09-15T02:00:00Z",
            "record_type" to "consumption", "record_subtype" to "not_applicable", "counterparty" to "Synthetic merchant", "note" to "Original bank note")
        val document = ParsedCashDocument("ccb_debit", "bank-table", "fixture-v1", listOf(ParsedCashRow(1, fields,
            providerId = null, sourceExplanation = mapOf("source_display_name" to "Synthetic bank"))), "622200001234", null,
            CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), 1, 1)
        return ParsedImportFile(ref, digest.toString().repeat(64), "parsed", document, sourceType = "ccb_debit")
    }
    private fun ambiguous(f: Fixture, draftTarget: Boolean = false): Pair<String, String> {
        val target = f.resultId(f.submit(bankFile("bank_a", 'e'), if (draftTarget) "defer" else "apply", "bank-a"))
        assertEquals(JsonPrimitive(if (draftTarget) "draft" else "posted"), f.detail(target)["state"])
        val source = f.resultId(f.submit(bankFile("bank_b", 'f'), "apply", "bank-b"))
        assertNotEquals(target, source)
        val facts = f.snapshot().facts
        assertEquals(JsonPrimitive("draft"), f.detail(source)["state"])
        val issue = facts.cashTransactionIssues.single { it.transactionId == source }
        assertEquals("source_identity_ambiguous", issue.code)
        val candidate = facts.cashIssueCandidates.single { it.issueId == issue.id }
        assertEquals(target, candidate.candidateTransactionId)
        assertEquals(facts.cashTransactions.single { it.id == target }.rowRevision, candidate.expectedRowRevision)
        return target to source
    }
    private fun resolution(f: Fixture, source: String, finalize: Boolean = false, target: String? = null) = buildJsonObject {
        val issue = f.snapshot().facts.cashTransactionIssues.single { it.transactionId == source }
        put("intent", if (finalize) "finalize_draft" else "resolve_issue")
        put("transaction_id", source); put("expected_revision", f.detail(source).getValue("row_revision"))
        put("patch", buildJsonObject { if (target != null) put("note", "") })
        put("issue_resolutions", buildJsonArray { add(buildJsonObject {
            put("issue_id", issue.id); put("expected_issue_revision", issue.revision.toLong())
            put("resolution", if (target == null) "create_independent" else "merge_into")
            if (target != null) { put("target_transaction_id", target); put("expected_target_revision", f.detail(target).getValue("row_revision")) }
        }) })
        put("relation_choices", JsonArray(emptyList()))
    }
    private suspend fun ApplicationTestBuilder.post(f: Fixture, path: String, body: JsonObject, key: String? = null): JsonObject {
        val response = client.post(path) {
            bearerAuth(f.token); contentType(ContentType.Application.Json); setBody(body.toString())
            if (key != null) header("Idempotency-Key", key)
        }
        val text = response.bodyAsText(); assertEquals(200, response.status.value, text)
        return Json.parseToJsonElement(text).jsonObject
    }
    private suspend fun ApplicationTestBuilder.maintain(f: Fixture, input: JsonObject, key: String): Pair<JsonObject, JsonObject> {
        val before = f.state()
        val plan = post(f, "/api/v1/cash-mutations/plans", input)
        assertEquals(JsonPrimitive(true), plan["can_commit"])
        for (item in plan.getValue("items").jsonArray) {
            val response = client.get("/api/v1/cash-mutations/plans/${plan.apiText("plan_id")}/items/${item.jsonObject.apiText("preview_item_id")}") { bearerAuth(f.token) }
            assertEquals(200, response.status.value, response.bodyAsText())
        }
        assertEquals(before, f.state(), "Maintenance preview must not publish facts or receipts")
        return plan to post(f, "/api/v1/cash-mutations/plans/${plan.apiText("plan_id")}/commit",
            buildJsonObject { put("expected_plan_revision", plan.getValue("plan_revision")) }, key)
    }
    private suspend fun ApplicationTestBuilder.replayAfterCleanup(f: Fixture, plan: JsonObject, receipt: JsonObject, key: String) {
        val context = MutationPlanStore.contextId(plan.apiText("plan_id"))
        f.store.get(context, f.workspace, f.user)
        f.storageTime = at.plusSeconds(1900)
        assertTrue(f.store.sweepExpired().deleted > 0)
        assertEquals(0, f.store.sweepExpired().scanned)
        assertFailsWith<ParsedImportNotFound> { f.store.get(context, f.workspace, f.user) }
        val before = f.state()
        assertEquals(receipt, post(f, "/api/v1/cash-mutations/plans/${plan.apiText("plan_id")}/commit",
            buildJsonObject { put("expected_plan_revision", plan.getValue("plan_revision")) }, key))
        assertEquals(receipt, post(f, "/api/v1/cash-mutations/resolve",
            buildJsonObject { put("plan_id", plan.apiText("plan_id")); put("idempotency_key", key) })["receipt"])
        assertEquals(before, f.state(), "Permanent replay after cleanup must not write")
    }

    @Test fun independentIdentitySurvivesCleanupReplayAndCurrentReimport() {
        for (finalize in listOf(false, true)) workspace { f -> testApplication {
            val (target, source) = ambiguous(f)
            val before = f.detail(source); val bindings = f.bindings(source); val balance = f.balances()
            application { financeModule(f.db, clock = { at }, mutationPlans = f.maintenance) }
            val (plan, receipt) = maintain(f, resolution(f, source, finalize), "independent-confirm")
            assertEquals(JsonPrimitive("completed"), receipt["status"])
            assertEquals(JsonPrimitive(source), f.detail(source)["transaction_id"])
            assertEquals(JsonPrimitive(if (finalize) "posted" else "draft"), f.detail(source)["state"])
            assertTrue(f.detail(source).getValue("issues").jsonArray.isEmpty())
            assertEquals(bindings, f.bindings(source))
            if (!finalize) {
                assertEquals(before["row_revision"], f.detail(source)["row_revision"])
                assertEquals(before["fields"], f.detail(source)["fields"]); assertEquals(balance, f.balances())
            }
            replayAfterCleanup(f, plan, receipt, "independent-confirm")
            assertEquals(source, f.resultId(f.submit(bankFile("bank_b", 'f'), "apply", "independent-reimport")))
            assertEquals(JsonPrimitive("posted"), f.detail(source)["state"])
            assertTrue(f.detail(source).getValue("issues").jsonArray.isEmpty())
            assertEquals(JsonPrimitive("posted"), f.detail(target)["state"])
        } }
    }

    @Test fun mergedIdentityRetainsManualClearAndSurvivesCleanupReplayAndCurrentReimport() {
        for (draft in listOf(false, true)) workspace { f -> testApplication {
            val (target, source) = ambiguous(f, draft)
            val balance = f.balances(); val targetBindings = f.bindings(target); val sourceBindings = f.bindings(source)
            application { financeModule(f.db, clock = { at }, mutationPlans = f.maintenance) }
            val (plan, receipt) = maintain(f, resolution(f, source, target = target), "merge-confirm")
            assertEquals(JsonPrimitive("completed"), receipt["status"])
            assertEquals(JsonPrimitive(target), receipt["retained_transaction_id"])
            val current = f.detail(target)
            assertEquals(JsonPrimitive(if (draft) "draft" else "posted"), current["state"])
            assertEquals(JsonPrimitive(""), current.getValue("entry").jsonObject.getValue("fields").jsonObject["note"])
            assertTrue(JsonPrimitive("note") in current.getValue("entry").jsonObject.getValue("manual_fields").jsonArray)
            assertEquals(balance, f.balances())
            assertEquals("record_not_found", assertFailsWith<CashRecordDetailFailure> { f.detail(source) }.code)
            assertNotNull(f.snapshot().facts.cashTransactions.single { it.id == source }.deletedAt)
            assertTrue(f.bindings(source).isEmpty()); val transferred = f.bindings(target)
            assertTrue(transferred.containsAll(targetBindings))
            assertEquals((targetBindings + sourceBindings).map { it.id }.toSet(), transferred.map { it.id }.toSet())
            val row = ReceiptReadService(f.db, now = { at }).results(f.token, receipt.apiText("receipt_id"), "row", ReceiptPage(0, 200))
                .getValue("items").jsonArray.single().jsonObject
            assertEquals(JsonPrimitive(source), row["item_ref"]); assertEquals(JsonPrimitive(target), row["transaction_id"])
            assertEquals(JsonPrimitive("merged_draft"), row["outcome"])
            replayAfterCleanup(f, plan, receipt, "merge-confirm")
            assertEquals(target, f.resultId(f.submit(bankFile("bank_b", 'f'), "apply", "merge-reimport")))
            assertEquals(JsonPrimitive("posted"), f.detail(target)["state"])
            assertEquals(JsonPrimitive(""), f.detail(target).getValue("entry").jsonObject.getValue("fields").jsonObject["note"])
            assertTrue(f.detail(target).getValue("issues").jsonArray.isEmpty())
        } }
    }

    @Test fun newAliasKeepsEveryPendingBodyRevisionCandidateAndParentValue() {
        for (count in 1..2) workspace { f ->
            val id = f.resultId(f.submit(file("first", 'a'), "apply", "initial"))
            val candidate = f.resultId(f.submit(file("candidate", 'd', provider = "002"), "apply", "candidate"))
            val candidateRevision = f.snapshot().facts.cashTransactions.single { it.id == candidate }.rowRevision.toLong()
            // Prepare the service's stored pending state; this is not a test of matcher-generated issues.
            f.db.write { c ->
                check(c.lockWorkspace(f.db.sqlite, f.workspace))
                val writer = CashSourceWriteRepository(CashSqlContext(c, f.workspace, f.db.sqlite, now = { at }))
                for (index in 0 until count) {
                    val body = mapOf("备注" to "仍待采用的原文-$index")
                    val fields = mapOf("counterparty" to "仍待采用的名称-$index", "_source_payload_present" to true)
                    val origin = mapOf("file_digest" to "c".repeat(64), "position" to index + 1)
                    writer.saveIssue(id, "pending:original:$index", "source_update_pending", pendingSourcePayload = body,
                        pendingFields = fields + ("counterparty" to "Earlier value"), origin = origin, candidates = mapOf(candidate to candidateRevision))
                    assertEquals(2L, writer.saveIssue(id, "pending:original:$index", "source_update_pending", pendingSourcePayload = body,
                        pendingFields = fields, origin = origin, candidates = mapOf(candidate to candidateRevision)).revision)
                }
            }
            val initial = f.state(); val before = f.detail(id); val facts = f.snapshot().facts
            assertTrue(facts.cashSourceBindings.any { it.transactionId == id && it.sourceScopeKey == CashSourceScope.ALIPAY_TRANSACTION_SCOPE && it.identityKind == "provider_id" })
            assertEquals(count, facts.cashTransactionIssues.count { it.transactionId == id })
            assertEquals(count, facts.cashIssueCandidates.size)
            val proposal = file("file_b", 'b', counterparty = "本份选择不采用的更新")
            assertEquals(id, f.resultId(f.submit(proposal, "keep_existing", "keep-new-input-preserve-prior-pending")))
            val after = f.state(); assertEquals(initial["cash_transaction_issues"], after["cash_transaction_issues"])
            assertEquals(initial["cash_issue_candidates"], after["cash_issue_candidates"])
            assertEquals(before["row_revision"], f.detail(id)["row_revision"])
            assertEquals(before["fields"], f.detail(id)["fields"]); assertEquals(before["entry"], f.detail(id)["entry"])
            val actual = f.snapshot().facts
            assertEquals(facts.copy(cashSourceBindings = emptyList()), actual.copy(cashSourceBindings = emptyList()))
            assertTrue(actual.cashSourceBindings.containsAll(facts.cashSourceBindings))
            assertTrue(actual.cashSourceBindings.any { binding -> binding.transactionId == id && facts.cashSourceBindings.none { it.id == binding.id } })
            f.db.read { c ->
                val row = CashSourceRows.sourceBatchRows(listOf(proposal)).rows.values.single()
                val matches = CashSourceReadRepository(CashSqlContext(c, f.workspace, f.db.sqlite, now = { at }))
                    .forKeys(row.bindingSpecs, listOfNotNull(row.stableEvidenceBindingKey))
                assertTrue(matches.isNotEmpty()); assertEquals(setOf(id.toLong()), matches.map { it.transactionId }.toSet())
            }
        }
    }

    @Test fun deferredSourceClearRetainsExplicitPresenceAndOriginalIdentity() = workspace { f ->
        val id = f.resultId(f.submit(file("first", 'a'), "apply", "initial")); val before = f.detail(id)
        assertNotEquals(JsonNull, before.getValue("fields").jsonObject["source_payload"])
        assertTrue(f.snapshot().facts.cashTransactionIssues.isEmpty())
        assertEquals(id, f.resultId(f.submit(file("file_b", 'b', payload = null), "defer", "defer-source-null")))
        val detail = f.detail(id); assertEquals(before["fields"], detail["fields"])
        assertTrue(f.snapshot().facts.cashIssueCandidates.isEmpty())
        val issue = detail.getValue("issues").jsonArray.single { it.jsonObject["code"] == JsonPrimitive("source_update_pending") }.jsonObject
        assertEquals(JsonNull, issue["pending_source_payload"]); assertEquals(JsonPrimitive(true), issue["pending_source_payload_present"])
        f.db.read { c ->
            val row = c.jooq(f.db.sqlite).select(CASH_TRANSACTION_ISSUES.PENDING_FIELDS, CASH_TRANSACTION_ISSUES.PENDING_SOURCE_PAYLOAD)
                .from(CASH_TRANSACTION_ISSUES).where(CASH_TRANSACTION_ISSUES.WORKSPACE_ID.eq(f.workspace))
                .and(CASH_TRANSACTION_ISSUES.TRANSACTION_ID.eq(id.toLong())).and(CASH_TRANSACTION_ISSUES.CODE.eq("source_update_pending")).fetchSingle()
            assertEquals(JsonPrimitive(true), row.value1()!!.jsonObject["_source_payload_present"])
            assertEquals(JsonNull, row.value2() ?: JsonNull)
        }
    }
}
