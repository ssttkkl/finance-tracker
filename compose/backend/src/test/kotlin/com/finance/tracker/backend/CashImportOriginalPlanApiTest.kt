package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportOriginalPlanApiTest {
    private fun root(plan: JsonObject) = "/api/v1/cash-import/plans/${plan.apiText("plan_id")}"
    private fun receiptRoot(receipt: JsonObject) = "/api/v1/cash-mutations/receipts/${receipt.apiText("receipt_id")}"
    private fun revised(view: JsonObject, plan: JsonObject) = JsonObject(view + ("session_revision" to plan.getValue("session_revision")))
    private fun repreview(plan: JsonObject, vararg values: Pair<String, JsonElement>) =
        JsonObject(mapOf("previous_plan_id" to plan.getValue("plan_id")) + values)
    private suspend fun atomic(f: CashImportOriginalApiFixture, action: suspend () -> HttpResponse) {
        val before = f.snapshot(); val response = action(); val body = response.apiBody()
        assertEquals(503, response.status.value, body.toString()); assertEquals("commit_failed", body.apiError())
        assertEquals("not_committed", body.apiText("commit_state"))
        assertFalse("private" in body.toString()); assertFalse("SQL" in body.toString()); assertTrue(f.faultCount > 0)
        assertEquals(before, f.snapshot())
    }

    @Test fun previewCommitPagesAndReceiptReplaySurviveTemporaryCleanup() = testApplication {
        val f = CashImportOriginalApiFixture("lifecycle")
        application { financeModule(f.database, cashImports = f.service) }
        val view = f.upload(client); val before = f.count(); val plan = f.plan(client, view)
        assertEquals(2, plan.apiNumber("session_revision")); assertEquals(JsonPrimitive(true), plan["can_commit"])
        assertEquals(3, plan.apiArray("items").size)
        assertEquals(buildJsonObject { put("confirmed", 3); put("pending", 0) }, plan["item_counts"])
        assertTrue(setOf("operations", "relation_groups", "source_candidate_sets", "detail_values").intersect(plan.keys).isEmpty())
        assertEquals(before, f.count()); assertEquals(plan, f.get(client, root(plan)).apiBody())
        val first = f.get(client, root(plan) + "/items?limit=1").apiBody()
        assertEquals(1, first.apiArray("items").size); assertNotEquals(JsonNull, first["next_cursor"])
        assertEquals(2, f.get(client, root(plan) + "/items?limit=2&cursor=${first.apiText("next_cursor")}").apiBody().apiArray("items").size)
        val detail = f.get(client, root(plan) + "/items/${first.apiArray("items")[0].jsonObject.apiText("preview_item_id")}")
        assertEquals(200, detail.status.value); assertEquals(1, detail.apiBody().apiArray("members").size)
        assertFalse("fields_ref" in detail.bodyAsText()); assertFalse("preview_components" in detail.bodyAsText())
        assertEquals(JsonArray(emptyList()), f.get(client, root(plan) + "/processing-details").apiBody()["items"])
        val committed = f.commit(client, plan); assertEquals(200, committed.status.value, committed.bodyAsText())
        val receipt = committed.apiBody(); assertEquals(JsonPrimitive(true), receipt["committed"])
        assertEquals(3, receipt.getValue("summary").jsonObject.apiNumber("created_posted"))
        assertTrue(setOf("request_digest", "session_digest", "retry_context_id", "row_results", "affected_transaction_ids").intersect(receipt.keys).isEmpty())
        assertEquals(receipt, f.get(client, receiptRoot(receipt)).apiBody())
        assertEquals(receipt, f.get(client, "/api/v1/cash-mutations/receipts/by-key/synthetic-api-confirm").apiBody())
        val page = f.get(client, receiptRoot(receipt) + "/rows?limit=1").apiBody()
        assertEquals(1, page.apiArray("items").size); assertTrue(page.getValue("next_after").jsonPrimitive.isString)
        assertEquals(1, page.apiArray("projection_refs").size)
        assertEquals(2, f.get(client, receiptRoot(receipt) + "/rows?limit=2&after=${page.apiText("next_after")}").apiBody().apiArray("items").size)
        assertEquals(JsonArray(emptyList()), f.get(client, receiptRoot(receipt) + "/relations").apiBody()["items"])
        f.store.clear(); assertEquals(receipt, f.commit(client, plan).apiBody())
        assertEquals("session_committed", f.commit(client, plan, "another-key").apiBody().apiError())
        val resolved = f.resolve(client, plan); assertEquals(200, resolved.status.value)
        assertEquals(buildJsonObject { put("status", "committed"); put("receipt", receipt) }, resolved.apiBody())
        assertEquals(before + 3, f.count())
    }

    @Test fun repreviewUsesCompleteInputsAndOldPlanCannotCommit() = testApplication {
        val f = CashImportOriginalApiFixture("repreview")
        application { financeModule(f.database, cashImports = f.service) }
        val view = f.upload(client); val first = f.plan(client, view)
        val detail = f.get(client, root(first) + "/items/${first.apiArray("items")[0].jsonObject.apiText("preview_item_id")}").apiBody()
        val row = detail.apiArray("members")[0].jsonObject.apiText("row_ref")
        val choices = buildJsonArray { add(buildJsonObject { put("row_ref", row); put("action", "apply"); put("patch", buildJsonObject { put("note", "人工备注") }) }) }
        val changed = f.plan(client, revised(view, first), repreview(first, "row_choices" to choices))
        assertEquals("人工备注", changed.apiArray("items")[0].jsonObject.getValue("display").jsonObject.apiText("note"))
        assertEquals("plan_changed", f.preview(client, view).apiBody().apiError())
        assertEquals(409, f.commit(client, first).status.value); assertEquals(200, f.commit(client, changed).status.value)
        f.database.read { connection ->
            val sql = connection.jooq(f.database.sqlite)
            val entry = sql.selectFrom(CASH_ENTRIES).where(CASH_ENTRIES.WORKSPACE_ID.eq(f.workspace).and(CASH_ENTRIES.NOTE.eq("人工备注"))).fetchSingle()
            assertTrue("note" in entry.manualFields!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(0, sql.fetchCount(CASH_TRANSACTIONS, CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace).and(CASH_TRANSACTIONS.NOTE.eq("人工备注"))))
        }
    }

    @Test fun knownRollbackAndLostCommitResponseHaveDistinctRecovery() = testApplication {
        val f = CashImportOriginalApiFixture("outcome")
        application { financeModule(f.database, cashImports = f.service) }
        val plan = f.plan(client, f.upload(client)); f.mode = "receipt"
        atomic(f) { f.commit(client, plan) }; f.mode = null
        assertEquals(404, f.get(client, "/api/v1/cash-mutations/receipts/by-key/synthetic-api-confirm").status.value)
        assertEquals(buildJsonObject { put("status", "retry_original") }, f.resolve(client, plan).apiBody())
        f.mode = "commit_after"; val response = f.commit(client, plan); f.mode = null
        assertEquals(503, response.status.value); val body = response.apiBody()
        assertEquals("unknown", body.apiText("commit_state")); assertEquals("commit_state_unknown", body.apiError())
        assertFalse("private" in response.bodyAsText())
        val resolved = f.resolve(client, plan).apiBody(); assertEquals("committed", resolved.apiText("status"))
        assertEquals(resolved["receipt"], f.commit(client, plan).apiBody())
    }

    private fun replayFailure(phase: String) = testApplication {
        val f = CashImportOriginalApiFixture(phase)
        application { financeModule(f.database, cashImports = f.service) }
        val plan = f.plan(client, f.upload(client)); val receipt = f.commit(client, plan).apiBody()
        assertEquals(JsonPrimitive(true), receipt["committed"])
        f.mode = phase; val response = f.commit(client, plan); f.mode = null
        assertEquals(503, response.status.value); assertEquals("unknown", response.apiBody().apiText("commit_state"))
        assertFalse("private" in response.bodyAsText()); assertTrue(f.faultCount > 0)
        assertEquals(receipt, f.commit(client, plan).apiBody())
    }
    @Test fun committedReplayQueryFailureCannotClaimNotCommitted() = replayFailure("replay_query")
    @Test fun committedReplayExitFailureCannotClaimNotCommitted() = replayFailure("replay_exit")

    @Test fun fullRefundZeroRemainsReviewableAndHasOneCurrentResult() = testApplication {
        val f = CashImportOriginalApiFixture("refund")
        application { financeModule(f.database, cashImports = f.service) }
        val plan = f.plan(client, f.upload(client)); assertEquals(1, plan.apiArray("items").size)
        val item = plan.apiArray("items")[0].jsonObject
        assertEquals(0, BigDecimal.ZERO.compareTo(item.getValue("display").jsonObject.apiText("amount").toBigDecimal()))
        assertEquals(JsonPrimitive(true), item["detail_available"])
        val receipt = f.commit(client, plan).apiBody()
        assertEquals(4, receipt.getValue("summary").jsonObject.apiNumber("created_posted"))
        assertEquals(1, receipt.getValue("summary").jsonObject.apiNumber("relations_confirmed"))
        val rows = f.get(client, receiptRoot(receipt) + "/rows").apiBody(); assertEquals(4, rows.apiArray("items").size)
        assertEquals(1, rows.apiArray("projection_refs").map { it.jsonObject.apiText("projection_id") }.toSet().size)
    }

    @Test fun planQueryErrorsAreSafeForAllOriginalParameters() = testApplication {
        val f = CashImportOriginalApiFixture("query")
        application { financeModule(f.database, cashImports = f.service) }
        val plan = f.plan(client, f.upload(client))
        for (query in listOf("limit=0", "limit=201", "limit=1.0", "limit=1&limit=2", "other=private", "confirmation_status=invalid",
            "cursor=private-invalid", "start_index=-1", "start_index=1.5", "cursor=old&start_index=0", "start_index=0&start_index=1")) {
            val response = f.get(client, root(plan) + "/items?$query")
            assertEquals(422, response.status.value, query); assertFalse("private" in response.bodyAsText())
        }
    }

    @Test fun absolutePagesReturnFilteredTotal() = testApplication {
        val f = CashImportOriginalApiFixture("absolute")
        application { financeModule(f.database, cashImports = f.service) }
        val plan = f.plan(client, f.upload(client)); val response = f.get(client, root(plan) + "/items?start_index=0&limit=10&confirmation_status=pending")
        assertEquals(200, response.status.value); val page = response.apiBody()
        assertEquals(0, page.apiNumber("start_index")); assertEquals(plan.getValue("item_counts").jsonObject["pending"], page["total_count"])
        assertTrue(page.apiArray("items").all { it.jsonObject.apiText("confirmation_status") == "pending" })
    }

    @Test fun failedSourceWriteIsAtomicAndOriginalRequestCanRetry() = testApplication {
        val f = CashImportOriginalApiFixture("atomic")
        application { financeModule(f.database, cashImports = f.service) }
        val plan = f.plan(client, f.upload(client)); f.mode = "source"
        atomic(f) { f.commit(client, plan) }; f.mode = null
        val response = f.commit(client, plan); assertEquals(200, response.status.value, response.bodyAsText())
        assertFalse(response.apiBody().keys.any { it.startsWith("retry_context") })
    }

    @Test fun failedRelationWriteRollsBackEntireCommand() = testApplication {
        val f = CashImportOriginalApiFixture("relation_failure")
        application { financeModule(f.database, cashImports = f.service) }
        val plan = f.plan(client, f.upload(client)); assertEquals(1, plan.getValue("item_counts").jsonObject.apiNumber("confirmed"))
        f.mode = "relation"; atomic(f) { f.commit(client, plan) }; f.mode = null
        val response = f.commit(client, plan); assertEquals(200, response.status.value, response.bodyAsText())
        assertFalse(response.apiBody().keys.any { it.startsWith("retry_context") })
    }

    @Test fun unselectedAccountsSaveDraftsAndInvalidRowPatchDoesNotPublish() = testApplication {
        val f = CashImportOriginalApiFixture("draft")
        application { financeModule(f.database, cashImports = f.service) }
        val view = f.upload(client); val noAccounts = JsonArray(emptyList())
        val draft = f.plan(client, view, buildJsonObject { put("account_selections", noAccounts) })
        assertEquals(2, draft.getValue("item_counts").jsonObject.apiNumber("pending"))
        assertTrue(draft.apiArray("items").all { it.jsonObject["statistics_eligible"] == JsonPrimitive(false) })
        val row = draft.apiArray("items")[0].jsonObject.apiArray("member_refs")[0].jsonObject.apiText("row_ref")
        val invalid = f.preview(client, revised(view, draft), repreview(draft, "account_selections" to noAccounts,
            "row_choices" to buildJsonArray { add(buildJsonObject {
                put("row_ref", row); put("action", "apply"); put("patch", buildJsonObject { put("manual_overrides", buildJsonObject { put("note", true) }) })
            }) }))
        assertEquals(422, invalid.status.value); assertEquals("plan_input_invalid", invalid.apiBody().apiError())
        val checked = f.plan(client, revised(view, draft), repreview(draft, "account_selections" to noAccounts))
        assertEquals(2, checked.getValue("summary").jsonObject.apiNumber("create_draft"))
        val receipt = f.commit(client, checked).apiBody()
        assertEquals(2, receipt.getValue("summary").jsonObject.apiNumber("saved_draft"))
        assertEquals(0, receipt.getValue("summary").jsonObject.apiNumber("skipped"))
    }

    @Test fun newRowCannotKeepExistingAndDefaultCanRetry() = testApplication {
        val f = CashImportOriginalApiFixture("new_keep")
        application { financeModule(f.database, cashImports = f.service) }
        val view = f.upload(client)
        val response = f.preview(client, view, buildJsonObject { put("row_choices", buildJsonArray {
            add(buildJsonObject { put("row_ref", "file_a:r1"); put("action", "keep_existing") })
        }) })
        assertEquals(422, response.status.value); assertEquals("plan_input_invalid", response.apiBody().apiError())
        assertEquals(1, f.plan(client, view).getValue("summary").jsonObject.apiNumber("create_posted"))
    }

    @Test fun previewStructureIsStrictForAllOriginalTypesAndNeverEchoesInput() = testApplication {
        val f = CashImportOriginalApiFixture("structure")
        application { financeModule(f.database, cashImports = f.service) }
        val view = f.upload(client)
        val changes = listOf(
            buildJsonObject { put("expected_session_revision", true) },
            buildJsonObject { put("expected_session_revision", 1.0) },
            buildJsonObject { put("expected_session_revision", Json.parseToJsonElement("9223372036854775808")) },
            buildJsonObject { put("operations", buildJsonArray { add(buildJsonObject { put("private", "input") }) }) },
            buildJsonObject { put("row_choices", buildJsonArray { add(buildJsonObject { put("row_ref", "a"); put("action", "apply"); put("patch", JsonArray(emptyList())) }) }) },
            buildJsonObject { put("account_selections", buildJsonArray { add(buildJsonObject { put("source_account_ref", "a"); put("account_id", "101"); put("expected_account_revision", true) }) }) })
        for (change in changes) {
            val response = f.preview(client, view, change)
            assertEquals(422, response.status.value, change.toString()); assertFalse("private" in response.bodyAsText())
        }
    }

    @Test fun missingFullArrayDuplicateJsonAndMissingKeyAreRejected() = testApplication {
        val f = CashImportOriginalApiFixture("missing")
        application { financeModule(f.database, cashImports = f.service) }
        val view = f.upload(client); val body = f.input(view); val path = "/api/v1/cash-import/sessions/${view.apiText("session_id")}/plans"
        assertEquals(422, f.post(client, path, JsonObject(body - "row_choices").toString()).status.value)
        val duplicate = body.toString().dropLast(1) + ",\"expected_session_revision\":1}"
        assertEquals(422, f.post(client, path, duplicate).status.value)
        assertEquals(422, f.commit(client, f.plan(client, view), key = null).status.value)
    }

    @Test fun oldCursorAndOtherOperatorCannotReadOrCommit() = testApplication {
        val f = CashImportOriginalApiFixture("foreign")
        application { financeModule(f.database, cashImports = f.service) }
        val plan = f.plan(client, f.upload(client)); val cursor = f.get(client, root(plan) + "/items?limit=1").apiBody().apiText("next_cursor")
        assertEquals(422, f.get(client, root(plan) + "/processing-details?cursor=$cursor").status.value)
        assertEquals(422, f.get(client, root(plan) + "/items?cursor=$cursor&confirmation_status=pending").status.value)
        f.role("editor", "original-import-other")
        assertEquals(404, f.get(client, root(plan), "original-import-other").status.value)
        assertEquals(404, f.commit(client, plan, token = "original-import-other").status.value)
        val receipt = f.commit(client, plan).apiBody()
        assertEquals(404, f.get(client, receiptRoot(receipt), "original-import-other").status.value)
        assertEquals(404, f.get(client, "/api/v1/cash-mutations/receipts/by-key/synthetic-api-confirm", "original-import-other").status.value)
    }

    private fun invalidated(cancelled: Boolean) = testApplication {
        val f = CashImportOriginalApiFixture(if (cancelled) "cancelled" else "expired")
        application { financeModule(f.database, cashImports = f.service) }
        val view = f.upload(client); val plan = f.plan(client, view)
        if (cancelled) {
            val response = client.delete("/api/v1/cash-import/sessions/${view.apiText("session_id")}") { header(HttpHeaders.Authorization, "Bearer ${f.token}") }
            assertEquals(200, response.status.value)
        } else f.now = f.now.plusSeconds(61)
        assertEquals(if (cancelled) 409 else 410, f.get(client, root(plan)).status.value)
        assertEquals(if (cancelled) "plan_changed" else "plan_expired", f.commit(client, plan).apiBody().apiError())
        assertEquals(buildJsonObject { put("status", "not_committed") }, f.resolve(client, plan).apiBody())
    }
    @Test fun cancellationResolvesToNotCommitted() = invalidated(true)
    @Test fun expiryResolvesToNotCommitted() = invalidated(false)

    @Test fun independentWriterMakesResolvePendingUntilLockIsReleased() = testApplication {
        val f = CashImportOriginalApiFixture("pending")
        application { financeModule(f.database, cashImports = f.service) }
        val plan = f.plan(client, f.upload(client)); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val writer = executor.submit { f.database.write { connection ->
            assertTrue(connection.lockWorkspace(f.database.sqlite, f.workspace)); entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
        } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); val response = f.resolve(client, plan)
            assertEquals(200, response.status.value); assertEquals(buildJsonObject { put("status", "pending") }, response.apiBody())
        } finally { release.countDown(); writer.get(5, TimeUnit.SECONDS); executor.shutdownNow() }
        assertEquals(buildJsonObject { put("status", "retry_original") }, f.resolve(client, plan).apiBody())
    }

    @Test fun duplicateOnlyCanConfirmAndFixedAmountConflictIsFailedWithoutBusinessWrites() = testApplication {
        val f = CashImportOriginalApiFixture("duplicate")
        application { financeModule(f.database, cashImports = f.service) }
        val first = f.commit(client, f.plan(client, f.upload(client))).apiBody()
        assertEquals(1, first.getValue("summary").jsonObject.apiNumber("created_posted"))
        val duplicate = f.plan(client, f.upload(client)); assertEquals(JsonArray(emptyList()), duplicate["items"])
        assertEquals(JsonPrimitive(true), duplicate["can_commit"])
        val receipt = f.commit(client, duplicate, "duplicate-confirm").apiBody()
        assertEquals("no_changes", receipt.apiText("status")); assertEquals(1, receipt.getValue("summary").jsonObject.apiNumber("duplicate"))
        val conflict = f.plan(client, f.upload(client, "conflict")); assertEquals(JsonArray(emptyList()), conflict["items"])
        assertEquals(1, conflict.getValue("summary").jsonObject.apiNumber("skip"))
        val failed = f.commit(client, conflict, "conflict-confirm").apiBody()
        assertEquals("failed", failed.apiText("status")); assertEquals(1, failed.getValue("summary").jsonObject.apiNumber("skipped"))
        assertEquals(buildJsonObject { put("has_new_failures", false); put("has_business_writes", false); put("reason_codes", JsonArray(emptyList())) }, failed["commit_outcome"])
        assertEquals("fixed_field_conflict", f.get(client, receiptRoot(failed) + "/rows").apiBody().apiArray("items")[0].jsonObject.apiText("code"))
    }

    @Test fun permissionsAreRecheckedBeforeRepreviewAndReceiptReplay() = testApplication {
        val f = CashImportOriginalApiFixture("permission")
        application { financeModule(f.database, cashImports = f.service) }
        val view = f.upload(client); val plan = f.plan(client, view); val receipt = f.commit(client, plan).apiBody()
        f.role("viewer")
        assertEquals(403, f.commit(client, plan).status.value); assertEquals(403, f.preview(client, view).status.value)
        assertEquals(200, f.get(client, receiptRoot(receipt)).status.value)
    }

    @Test fun fileStatusAndUnknownCountMatchTheUploadedSession() = testApplication {
        val f = CashImportOriginalApiFixture("file_status")
        application { financeModule(f.database, cashImports = f.service) }
        val view = f.upload(client); val plan = f.plan(client, view); assertEquals(JsonPrimitive(true), plan["can_commit"])
        val files = view.apiArray("files"); val shown = plan.apiArray("file_results"); assertEquals(files.size, shown.size)
        for (index in files.indices) for (key in listOf("file_ref", "status", "recognized_row_count")) {
            assertEquals(files[index].jsonObject.getValue(key), shown[index].jsonObject.getValue(key), "$index $key")
        }
        assertEquals(JsonNull, shown[1].jsonObject["recognized_row_count"])
    }

    @Test fun authenticationAndViewerDenialKeepSafePublicEnvelope() = testApplication {
        val f = CashImportOriginalApiFixture("auth")
        application { financeModule(f.database, cashImports = f.service) }
        val view = f.upload(client); val path = "/api/v1/cash-import/sessions/${view.apiText("session_id")}"
        val denied = f.get(client, path, "invalid"); assertEquals(401, denied.status.value)
        val error = denied.apiBody().getValue("error").jsonObject
        assertEquals(JsonArray(emptyList()), error["field_errors"]); assertEquals(JsonPrimitive(false), error["retryable"])
        f.role("viewer"); val viewer = f.post(client, "$path/plans", "private body"); assertEquals(403, viewer.status.value)
        val viewerError = viewer.apiBody().getValue("error").jsonObject
        assertEquals(JsonArray(emptyList()), viewerError["field_errors"]); assertEquals(JsonPrimitive(false), viewerError["retryable"])
        assertFalse("private" in viewer.bodyAsText())
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { path ->
            val directory = Path.of(path); require(directory.isAbsolute); Files.createDirectories(directory)
            Files.writeString(directory.resolve("cash-import-viewer-error.json"), buildJsonObject {
                put("backend", if (f.database.sqlite) "sqlite" else "postgresql")
                put("session_id", view.getValue("session_id")); put("revision", view.getValue("session_revision"))
                put("status", viewer.status.value); put("body", viewer.apiBody())
            }.toString())
        }
    }

    @Test fun emptyEvidenceFailureReturnsKnownRollback() = testApplication {
        val f = CashImportOriginalApiFixture("empty")
        application { financeModule(f.database, cashImports = f.service) }
        val plan = f.plan(client, f.upload(client)); f.mode = "empty"
        atomic(f) { f.commit(client, plan) }; f.mode = null
    }

    @Test fun duplicatesPlusNewFailureRollBackWithoutNewReceipt() = testApplication {
        val f = CashImportOriginalApiFixture("mixed")
        application { financeModule(f.database, cashImports = f.service) }
        assertEquals(200, f.commit(client, f.plan(client, f.upload(client))).status.value)
        val plan = f.plan(client, f.upload(client, "more")); f.mode = "source"
        atomic(f) { f.commit(client, plan, "mixed-confirm") }; f.mode = null
    }

    @Test fun publicReadsNeverRestoreUnrelatedParsedDocuments() = testApplication {
        val f = CashImportOriginalApiFixture("reads")
        application { financeModule(f.database, cashImports = f.service) }
        val view = f.upload(client); val plan = f.plan(client, view); f.store.poisonDocuments = true
        assertFailsWith<ParsedImportStorageUnavailable> { f.store.get(view.apiText("session_id"), f.workspace, f.user) }
        for (suffix in listOf("", "/items", "/processing-details", "/items/${plan.apiArray("items")[0].jsonObject.apiText("preview_item_id")}")) {
            val response = f.get(client, root(plan) + suffix)
            assertEquals(200, response.status.value, response.bodyAsText())
        }
    }

    @Test fun cachedPagesRetainPlanIdentityAndCompletePaginationParameters() = testApplication {
        val f = CashImportOriginalApiFixture("cache")
        application { financeModule(f.database, cashImports = f.service) }
        val plan = f.plan(client, f.upload(client)); val path = root(plan)
        val first = f.get(client, "$path/items?limit=1").apiBody(); assertEquals(1, first.apiArray("items").size)
        assertEquals(first, f.get(client, "$path/items?limit=1").apiBody())
        assertEquals(2, f.get(client, "$path/items?limit=2").apiBody().apiArray("items").size)
        assertEquals(JsonArray(emptyList()), f.get(client, "$path/items?limit=1&confirmation_status=pending").apiBody()["items"])
        val second = f.get(client, "$path/items?limit=1&cursor=${first.apiText("next_cursor")}").apiBody()
        assertNotEquals(first.apiArray("items")[0], second.apiArray("items")[0])
        val forged = plan.apiText("plan_id").substringBefore('.') + "." + "x".repeat(16)
        val response = f.get(client, "/api/v1/cash-import/plans/$forged/items?limit=1")
        assertEquals(409, response.status.value); assertEquals("plan_changed", response.apiBody().apiError())
        assertEquals(200, f.commit(client, plan).status.value)
        val consumed = f.get(client, "$path/items?limit=1")
        assertEquals(409, consumed.status.value); assertEquals("session_committed", consumed.apiBody().apiError())
    }

    @Test fun failedSubsetRetryEndpointStaysRemoved() = testApplication {
        val f = CashImportOriginalApiFixture("removed")
        application { financeModule(f.database, cashImports = f.service) }
        val receipt = f.commit(client, f.plan(client, f.upload(client))).apiBody()
        val response = f.post(client, "/api/v1/cash-import/receipts/${receipt.apiText("receipt_id")}/retry-sessions",
            "{\"row_refs\":null}", key = "synthetic-removed-retry")
        assertEquals(404, response.status.value); assertFalse(receipt.keys.any { it.startsWith("retry_context") })
    }
}
