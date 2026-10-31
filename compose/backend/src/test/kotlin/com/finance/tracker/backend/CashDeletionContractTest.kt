package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.time.Instant
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashDeletionContractTest {
    private class Store(at: Instant) : ParsedImportStore(clock = { at }) {
        private val values = linkedMapOf<String, ParsedImportObject>()
        override fun readRaw(id: String) = values[id] ?: throw ParsedImportNotFound()
        override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) {
            if (values[id]?.etag != expected) throw ParsedImportRevisionConflict()
            values[id] = ParsedImportObject(content.copyOf(), digest(content))
        }
        override fun deleteRaw(id: String) { values.remove(id) }
        override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean) = ParsedImportSweep(0, 0, null)
    }
    private fun directory(name: String) = Path.of(requireNotNull(System.getenv("FT_KOTLIN_DELETION_CONTRACT_DIR"))).resolve(name)
    private inner class Fixture(name: String) {
        private val config = Json.parseToJsonElement(directory(name).resolve("database.json").readText()).jsonObject
        val input = Json.parseToJsonElement(directory(name).resolve("input.json").readText()).jsonObject
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val user = config.getValue("user").jsonPrimitive.content
        val token = "original-import-owner"
        val identifiers get() = input.getValue("args").jsonArray.first()
        val version get() = input.getValue("kwargs").jsonObject["projection_version"]
        fun service(stage: (String) -> Unit = {}) = CashDeletionService(database, now = { Instant.parse("2026-11-01T00:00:00Z") }, afterStage = stage)
        fun invoke(service: CashDeletionService = service()): JsonObject = when (input.getValue("operation").jsonPrimitive.content) {
            "delete_record" -> service.deleteRecord(token, identifiers.jsonPrimitive.content, input.getValue("kwargs").jsonObject["mode"]?.jsonPrimitive?.content ?: "delete_current_dissolve")
            "preview_delete_projections" -> service.preview(token, identifiers, version)
            else -> service.deleteProjections(token, identifiers, version)
        }
        fun <T> read(block: (CashSqlContext) -> T): T = database.read { block(CashSqlContext(it, workspace, database.sqlite)) }
        fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.sortedBy { it.name }.associate { table ->
            table.name to connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                val metadata = rows.metaData
                buildList { while (rows.next()) add(JsonArray((1..metadata.columnCount).map { index -> rows.getString(index)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            } }
        } }
        fun version() = read { ProjectionStateRepository(it.connection, workspace, database.sqlite).status().getValue("projection_version").jsonPrimitive.long }
        fun facts() = read { context -> context.sql.selectFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).fetch().associate { row ->
            row[CASH_TRANSACTIONS.ID]!! to row.intoMap().filterKeys { it !in setOf("deleted_at", "deleted_by", "delete_reason") }.mapValues { it.value?.toString() }
        } }
        fun deleted(ids: Set<Long>) = read { context -> context.sql.select(CASH_TRANSACTIONS.ID).from(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace))
            .and(CASH_TRANSACTIONS.ID.`in`(ids)).and(CASH_TRANSACTIONS.DELETED_AT.isNotNull).fetch(CASH_TRANSACTIONS.ID).toSet() }
        fun relations() = read { context -> context.sql.selectFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace)).orderBy(TRANSACTION_RELATIONS.ID).fetch() }
        fun preserved(before: Map<Long, Map<String, String?>>, parts: List<String>) {
            assertEquals(before, facts()); assertEquals(parts, snapshot().getValue("cash_transaction_components"))
        }
    }
    private fun impact(projections: Int, transactions: Int, groups: Int) = buildJsonObject { put("projection_count", projections); put("transaction_count", transactions); put("relation_group_count", groups) }
    private fun ids(value: JsonObject) = value.getValue("deleted_fact_ids").jsonArray.map { it.jsonPrimitive.long }.toSet()
    private fun detail(f: Fixture, id: Long) = CashRecordDetailService(f.database).detail(f.token, id.toString())

    @Test fun originalRecordDeleteRemovesRelationAndKeepsOtherEndpoint() {
        val f = Fixture("record_transfer"); val facts = f.facts(); val parts = f.snapshot().getValue("cash_transaction_components")
        val relation = f.relations().single(); val endpoints = setOf(relation[TRANSACTION_RELATIONS.PRIMARY_TRANSACTION_ID]!!, relation[TRANSACTION_RELATIONS.SECONDARY_TRANSACTION_ID]!!)
        val result = f.invoke(); assertEquals(JsonPrimitive(true), result["deleted"]); assertEquals(JsonPrimitive(1), result["related_count"])
        assertEquals(ids(result), f.deleted(endpoints)); assertEquals(1, ids(result).size)
        val remaining = (endpoints - ids(result)).single(); assertNotNull(detail(f, remaining))
        assertEquals("record_not_found", assertFailsWith<CashRecordDetailFailure> { detail(f, ids(result).single()) }.code)
        assertTrue(f.relations().isEmpty()); f.preserved(facts, parts)
    }
    @Test fun originalDeleteCurrentDissolvesWithoutDeletingRemainingFact() {
        val f = Fixture("record_current"); val before = f.facts(); val parts = f.snapshot().getValue("cash_transaction_components")
        val target = f.identifiers.jsonPrimitive.long; val result = f.invoke()
        assertEquals(JsonPrimitive(true), result["deleted"]); assertEquals(setOf(target), ids(result)); assertTrue(f.relations().isEmpty())
        assertEquals(setOf(target), f.deleted(before.keys)); f.preserved(before, parts)
    }
    @Test fun originalDeleteAllRemovesBothMembersOfConfirmedGroup() {
        val f = Fixture("record_all"); val edge = f.relations().single()
        val expected = setOf(edge[TRANSACTION_RELATIONS.PRIMARY_TRANSACTION_ID]!!, edge[TRANSACTION_RELATIONS.SECONDARY_TRANSACTION_ID]!!)
        val result = f.invoke(); assertEquals(expected, ids(result)); assertEquals(expected, f.deleted(expected))
        assertEquals("record_not_found", assertFailsWith<CashRecordDetailFailure> { detail(f, expected.last()) }.code)
    }
    @Test fun originalTransferDeleteClearsProjectionRelationBeforeFact() {
        val f = Fixture("record_projection"); val edge = f.relations().single(); val target = f.identifiers.jsonPrimitive.long
        val remaining = setOf(edge[TRANSACTION_RELATIONS.PRIMARY_TRANSACTION_ID]!!, edge[TRANSACTION_RELATIONS.SECONDARY_TRANSACTION_ID]!!).minus(target).single()
        assertEquals(JsonPrimitive(true), f.invoke()["deleted"]); assertTrue(f.relations().isEmpty()); assertNotNull(detail(f, remaining))
    }
    @Test fun originalBatchImpactAndDeleteAreAtomicAndRemoveSelectedRecords() {
        val f = Fixture("bulk"); val before = f.snapshot(); val facts = f.facts(); val parts = before.getValue("cash_transaction_components")
        assertEquals(impact(2, 2, 0), f.invoke()); assertEquals(before, f.snapshot())
        val result = f.service().deleteProjections(f.token, f.identifiers, f.version)
        assertEquals(impact(2, 2, 0).toMap(), result.filterKeys { it in setOf("projection_count", "transaction_count", "relation_group_count") })
        assertEquals(setOf(1002L, 1003L), ids(result)); assertTrue(result.getValue("projection_version").jsonPrimitive.long > f.version!!.jsonPrimitive.long)
        assertEquals(setOf(1002L, 1003L), f.deleted(setOf(1001L, 1002L, 1003L)))
        val page = CashProjectionListService(f.database).list(f.token)
        assertEquals(listOf("cash:1001"), page.getValue("items").jsonArray.map { it.jsonObject.getValue("projection_id").jsonPrimitive.content })
        assertEquals("record_not_found", assertFailsWith<CashRecordDetailFailure> { detail(f, 1002) }.code); f.preserved(facts, parts)
    }
    @Test fun originalBatchRelatedSelectionDeletesWholeGroup() {
        val f = Fixture("bulk_group"); assertEquals(1, f.identifiers.jsonArray.size); assertEquals(impact(1, 3, 1), f.invoke())
        val result = f.service().deleteProjections(f.token, f.identifiers, f.version); assertEquals(JsonPrimitive(3), result["transaction_count"])
        assertEquals(3, ids(result).size); assertEquals(ids(result), f.deleted(ids(result)))
        for (id in ids(result)) assertEquals("record_not_found", assertFailsWith<CashRecordDetailFailure> { detail(f, id) }.code)
    }
    @Test fun originalStaleVersionRejectsWithoutMutatingFacts() {
        val f = Fixture("stale"); val before = f.snapshot()
        assertEquals("projection.version_conflict", assertFailsWith<CashDeletionFailure> { f.invoke() }.code)
        assertEquals(before, f.snapshot()); assertTrue(f.deleted(f.facts().keys).isEmpty())
    }
    @Test fun originalBatchFailureRollsBackFactAndProjection() {
        val f = Fixture("rollback"); val before = f.snapshot(); var reached = 0
        val service = f.service { if (it == "projection") { reached++; throw IllegalStateException("snapshot write failed") } }
        assertEquals("internal", assertFailsWith<CashDeletionFailure> { f.invoke(service) }.code)
        assertEquals(1, reached); assertEquals(before, f.snapshot()); assertNotNull(detail(f, 1003))
        assertTrue(CashProjectionListService(f.database).list(f.token).getValue("items").jsonArray.any { it.jsonObject["projection_id"] == JsonPrimitive("cash:1003") })
    }
    @Test fun originalEmptyHiddenAndCrossWorkspaceSelectionsReject() {
        val f = Fixture("invalid"); val before = f.snapshot()
        for ((selection, code) in listOf(JsonArray(emptyList()) to "projection.delete_required", JsonArray(listOf(JsonPrimitive("cash:not-visible"))) to "projection.version_conflict",
                JsonArray(listOf(JsonPrimitive("cash:other-workspace"))) to "projection.version_conflict"))
            assertEquals(code, assertFailsWith<CashDeletionFailure> { f.service().preview(f.token, selection, f.version) }.code)
        assertEquals(before, f.snapshot())
        f.database.write { it.jooq(f.database.sqlite).update(CASH_PROJECTION_STATES).set(CASH_PROJECTION_STATES.AVAILABILITY, "uninitialized")
            .set(CASH_PROJECTION_STATES.ACTIVE_DATASET_ID, null as String?).where(CASH_PROJECTION_STATES.WORKSPACE_ID.eq(f.workspace)).execute() }
        val unavailable = f.snapshot()
        assertEquals("projection.unavailable", assertFailsWith<CashDeletionFailure> { f.service().preview(f.token, JsonArray(listOf(JsonPrimitive("cash:1003"))), f.version) }.code)
        assertEquals(unavailable, f.snapshot())
        assertEquals(JsonPrimitive(true), f.service().deleteRecord(f.token, "1003")["deleted"])
        assertEquals(setOf(1003L), f.deleted(setOf(1001L, 1002L, 1003L)))
        assertEquals(0, f.read { it.sql.fetchCount(it.sql.selectOne().from(CASH_ENTRY_MEMBERS).where(CASH_ENTRY_MEMBERS.WORKSPACE_ID.eq(f.workspace)).and(CASH_ENTRY_MEMBERS.TRANSACTION_ID.eq(1003L))) })
    }
    @Test fun originalParentOnlyDraftDeletesActiveAndInactiveRelationsThroughBothOperations() {
        for (name in listOf("draft_active_record", "draft_inactive_record", "draft_active_projection", "draft_inactive_projection")) {
            val f = Fixture(name); val facts = f.facts(); val parts = f.snapshot().getValue("cash_transaction_components")
            assertEquals(JsonPrimitive(true), f.invoke()["deleted"]); assertEquals(setOf(1101L), f.deleted(setOf(1101L)))
            assertTrue(f.relations().isEmpty())
            assertEquals(if (name.endsWith("projection")) "delete_all" else "delete_current_dissolve", f.read { it.sql.select(CASH_TRANSACTIONS.DELETE_REASON).from(CASH_TRANSACTIONS)
                .where(CASH_TRANSACTIONS.ID.eq(1101L)).and(CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace)).fetchSingle().value1() })
            f.preserved(facts, parts)
        }
    }
    @Test fun originalUnrelatedConfirmedEdgesRemainInOldClosure() {
        val f = Fixture("retained"); val retained = f.relations().single { it[TRANSACTION_RELATIONS.KIND] == "refund_offset" }
        f.invoke(); val edge = f.relations().single(); assertEquals(retained.intoMap(), edge.intoMap())
        val first = detail(f, 1102); val second = detail(f, 1103)
        assertEquals(first.getValue("projection_ref").jsonObject["projection_id"], second.getValue("projection_ref").jsonObject["projection_id"])
        assertEquals(JsonPrimitive("confirmed"), first["confirmation_status"]); assertEquals(JsonPrimitive("confirmed"), second["confirmation_status"])
    }
    @Test fun originalJointDraftDeletionClearsUnconfirmedGroupAndKeepsConfirmedEdge() {
        val f = Fixture("joint"); val before = f.relations(); val unconfirmed = before.filter { it[TRANSACTION_RELATIONS.STATE] != "confirmed" }.map { it[TRANSACTION_RELATIONS.ID] }.toSet()
        f.invoke(); val retained = f.relations().single(); assertFalse(retained[TRANSACTION_RELATIONS.ID] in unconfirmed)
        assertEquals("refund_offset", retained[TRANSACTION_RELATIONS.KIND]); assertEquals("confirmed", retained[TRANSACTION_RELATIONS.STATE])
        assertEquals(JsonPrimitive("confirmed"), detail(f, 1103)["confirmation_status"])
    }
    @Test fun originalCandidateDeletionKeepsOtherProofsAndAdvancesCurrentRevisions() {
        for (name in listOf("candidate_record", "candidate_projection")) {
            val f = Fixture(name); val before = f.relations().single(); val snapshot = f.snapshot(); val facts = f.facts()
            val issue = f.read { it.sql.selectFrom(CASH_TRANSACTION_ISSUES).where(CASH_TRANSACTION_ISSUES.WORKSPACE_ID.eq(f.workspace))
                .and(CASH_TRANSACTION_ISSUES.TRANSACTION_ID.eq(1105L)).fetchSingle() }
            val expectedCandidates = f.read { it.sql.selectFrom(CASH_RELATION_CANDIDATES).where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(f.workspace))
                .and(CASH_RELATION_CANDIDATES.CANDIDATE_TRANSACTION_ID.eq(1105L)).fetch().map { it.intoMap() } }
            f.invoke(); val after = f.relations().single()
            assertEquals(before[TRANSACTION_RELATIONS.ID], after[TRANSACTION_RELATIONS.ID]); assertTrue(after[TRANSACTION_RELATIONS.ROW_REVISION]!! > before[TRANSACTION_RELATIONS.ROW_REVISION]!!)
            assertEquals("draft", after[TRANSACTION_RELATIONS.STATE])
            val candidates = f.read { it.sql.selectFrom(CASH_RELATION_CANDIDATES).where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(f.workspace)).fetch() }
            assertEquals(listOf(1105L), candidates.map { it[CASH_RELATION_CANDIDATES.CANDIDATE_TRANSACTION_ID] })
            assertEquals(expectedCandidates, candidates.map { it.intoMap() })
            val originalCandidates = snapshot.getValue("cash_relation_candidates"); assertTrue(originalCandidates.size > candidates.size)
            val current = f.read { it.sql.selectFrom(CASH_TRANSACTION_ISSUES).where(CASH_TRANSACTION_ISSUES.WORKSPACE_ID.eq(f.workspace))
                .and(CASH_TRANSACTION_ISSUES.ID.eq(issue[CASH_TRANSACTION_ISSUES.ID])).fetchSingle() }
            assertTrue(current[CASH_TRANSACTION_ISSUES.REVISION]!! > issue[CASH_TRANSACTION_ISSUES.REVISION]!!)
            assertEquals(issue.intoMap().filterKeys { it != "revision" }, current.intoMap().filterKeys { it != "revision" })
            assertEquals(listOf(1102L), f.read { it.sql.select(CASH_ISSUE_CANDIDATES.CANDIDATE_TRANSACTION_ID).from(CASH_ISSUE_CANDIDATES)
                .where(CASH_ISSUE_CANDIDATES.ISSUE_ID.eq(issue[CASH_TRANSACTION_ISSUES.ID])).and(CASH_ISSUE_CANDIDATES.WORKSPACE_ID.eq(f.workspace)).fetch().map { it.value1() } })
            assertEquals(0, f.read { it.sql.fetchCount(it.sql.selectOne().from(CASH_SOURCE_BINDINGS).where(CASH_SOURCE_BINDINGS.WORKSPACE_ID.eq(f.workspace)).and(CASH_SOURCE_BINDINGS.TRANSACTION_ID.eq(1101L))) })
            assertEquals(0, f.read { it.sql.fetchCount(it.sql.selectOne().from(CASH_TRANSACTION_ISSUES).where(CASH_TRANSACTION_ISSUES.WORKSPACE_ID.eq(f.workspace)).and(CASH_TRANSACTION_ISSUES.TRANSACTION_ID.eq(1101L))) })
            f.preserved(facts, snapshot.getValue("cash_transaction_components"))
        }
    }
    @Test fun originalFailuresAfterReferencesProjectionAndBalanceRollBackEveryTable() {
        for (stage in listOf("references", "projection", "balance")) {
            val f = Fixture("failure_$stage"); val before = f.snapshot(); var reached = 0
            val service = f.service { if (it == stage) { reached++; throw IllegalStateException("synthetic deletion publication failure") } }
            assertEquals("internal", assertFailsWith<CashDeletionFailure> { f.invoke(service) }.code)
            assertEquals(1, reached); assertEquals(before, f.snapshot())
        }
    }
    @Test fun originalSameGroupInOtherWorkspaceIsUntouched() {
        val f = Fixture("other_group"); val before = f.database.read { it.jooq(f.database.sqlite).selectFrom(TRANSACTION_RELATIONS)
            .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq("delete-other-workspace")).fetchSingle().intoMap() }
        f.invoke(); assertEquals(before, f.database.read { it.jooq(f.database.sqlite).selectFrom(TRANSACTION_RELATIONS)
            .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq("delete-other-workspace")).fetchSingle().intoMap() })
    }
    @Test fun originalParentOnlyRelationCountsInPreviewWithoutWrites() {
        val f = Fixture("parent_preview"); val before = f.snapshot(); assertEquals(impact(1, 1, 1), f.invoke()); assertEquals(before, f.snapshot())
    }
    @Test fun originalHttpPreviewConfirmationDeleteAndStaleErrors() = testApplication {
        val f = Fixture("api_original"); application { financeModule(f.database) }
        suspend fun request(path: String, body: JsonObject, method: HttpMethod) = client.request(path) {
            this.method = method; header(HttpHeaders.Authorization, "Bearer ${f.token}"); contentType(ContentType.Application.Json); setBody(body.toString())
        }
        val input = buildJsonObject { put("projection_ids", JsonArray(listOf(JsonPrimitive("cash:1003")))); put("projection_version", f.version!!) }
        val preview = request("/api/v1/cash-projections/delete-impact", input, HttpMethod.Post)
        assertEquals(200, preview.status.value); assertEquals(impact(1, 1, 0), Json.parseToJsonElement(preview.bodyAsText()))
        val missing = request("/api/v1/cash-projections", input, HttpMethod.Delete)
        assertEquals(400, missing.status.value); assertEquals("projection.confirmation_required", Json.parseToJsonElement(missing.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        val deleted = request("/api/v1/cash-projections", JsonObject(input + ("confirmed" to JsonPrimitive(true))), HttpMethod.Delete)
        assertEquals(200, deleted.status.value); val body = Json.parseToJsonElement(deleted.bodyAsText()).jsonObject
        assertEquals(JsonPrimitive(1), body["transaction_count"]); assertFalse("deleted_fact_ids" in body)
        val stale = request("/api/v1/cash-projections/delete-impact", JsonObject(input + ("projection_ids" to JsonArray(listOf(JsonPrimitive("cash:1002"))))), HttpMethod.Post)
        assertEquals(409, stale.status.value); assertEquals("projection.version_conflict", Json.parseToJsonElement(stale.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
    }
    @Test fun fullHttpRequestsResponsesPermissionsAndNoWriteErrorsMatchOriginal() = testApplication {
        val f = Fixture("api"); application { financeModule(f.database) }
        val cases = Json.parseToJsonElement(directory("api").resolve("http-cases.json").readText()).jsonArray
        assertTrue(cases.size >= 50)
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject; val before = f.snapshot(); val method = HttpMethod.parse(case.getValue("method").jsonPrimitive.content)
            val path = case.getValue("path").jsonPrimitive.content
            val response = client.request(path) {
                this.method = method; case.getValue("headers").jsonObject.forEach { (key, value) -> header(key, value.jsonPrimitive.content) }
                setBody(Base64.getDecoder().decode(case.getValue("raw_base64").jsonPrimitive.content))
            }
            val body = response.bodyAsText(); val label = "$index $method $path ${case["raw_base64"]}"
            assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, "$label $body")
            val expected = case.getValue("body")
            if (expected is JsonPrimitive && expected.isString) assertEquals(expected.content, body, label)
            else assertEquals(expected, Json.parseToJsonElement(body), label)
            if (!case.getValue("mutates").jsonPrimitive.boolean) assertEquals(before, f.snapshot(), label)
            if (response.status.value == 200 && (index == 0 || method == HttpMethod.Delete && path == "/api/v1/cash-projections")) {
                System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { output ->
                    val destination = Path.of(output); Files.createDirectories(destination)
                    Files.writeString(destination.resolve(if (index == 0) "deletion-impact-response.json" else "deletion-response.json"), buildJsonObject {
                        put("backend", if (f.database.sqlite) "sqlite" else "postgresql"); put("status", response.status.value); put("body", Json.parseToJsonElement(body))
                        put("request", Json.parseToJsonElement(Base64.getDecoder().decode(case.getValue("raw_base64").jsonPrimitive.content).decodeToString()))
                    }.toString())
                }
            }
        }
    }
    @Test fun postgresQueuedDeleteRechecksPermissionAfterActualWorkspaceLockWait() {
        val f = Fixture("lock"); org.junit.Assume.assumeFalse(f.database.sqlite)
        val before = f.snapshot(); val ready = CountDownLatch(1); val workerPid = AtomicInteger(); val pool = Executors.newSingleThreadExecutor()
        var future: java.util.concurrent.Future<Throwable?>? = null
        try {
            f.database.write { holder ->
                assertTrue(holder.lockWorkspace(false, f.workspace))
                val holderPid = holder.createStatement().use { it.executeQuery("select pg_backend_pid()").use { rows -> rows.next(); rows.getInt(1) } }
                val service = CashDeletionService(f.database, beforeWorkspaceLock = { connection ->
                    workerPid.set(connection.createStatement().use { it.executeQuery("select pg_backend_pid()").use { rows -> rows.next(); rows.getInt(1) } }); ready.countDown()
                })
                future = pool.submit<Throwable?> { try { service.deleteProjections(f.token, f.identifiers, f.version); null } catch (failure: Throwable) { failure } }
                assertTrue(ready.await(5, TimeUnit.SECONDS)); val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); var blocked = false
                while (!blocked && System.nanoTime() < deadline) {
                    blocked = f.database.read { connection -> connection.prepareStatement("select ? = any(pg_blocking_pids(?))").use {
                        it.setInt(1, holderPid); it.setInt(2, workerPid.get()); it.executeQuery().use { rows -> rows.next(); rows.getBoolean(1) }
                    } }
                    if (!blocked) Thread.sleep(10)
                }
                assertTrue(blocked, "must observe the real database workspace lock wait")
                holder.jooq(false).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer")
                    .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(f.workspace)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(f.user)).execute()
            }
            assertTrue(assertIs<AccessFailure>(future!!.get(10, TimeUnit.SECONDS)).forbidden)
            assertEquals(before.filterKeys { it != "workspace_memberships" }, f.snapshot().filterKeys { it != "workspace_memberships" })
        } finally { pool.shutdownNow() }
    }
    @Test fun originalDeletedSourceReimportsAsNewRecordWithAndWithoutProviderId() {
        for (name in listOf("reimport_provider", "reimport_missing")) {
            val f = Fixture(name); val old = f.identifiers.jsonPrimitive.long; val facts = f.facts(); val parts = f.snapshot().getValue("cash_transaction_components")
            f.invoke(); f.preserved(facts, parts)
            assertEquals("web", f.read { it.sql.select(CASH_TRANSACTIONS.DELETED_BY).from(CASH_TRANSACTIONS)
                .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace)).and(CASH_TRANSACTIONS.ID.eq(old)).fetchSingle().value1() })
            assertEquals(0, f.read { it.sql.fetchCount(it.sql.selectOne().from(CASH_SOURCE_BINDINGS).where(CASH_SOURCE_BINDINGS.WORKSPACE_ID.eq(f.workspace)).and(CASH_SOURCE_BINDINGS.TRANSACTION_ID.eq(old))) })
            CashProjectionService(f.database, f.workspace).rebuild()
            assertEquals(0, f.read { it.sql.fetchCount(it.sql.selectOne().from(CASH_PROJECTION_MEMBERS).where(CASH_PROJECTION_MEMBERS.WORKSPACE_ID.eq(f.workspace)).and(CASH_PROJECTION_MEMBERS.CASH_TRANSACTION_ID.eq(old))) })
            val input = Json.parseToJsonElement(directory(name).resolve("reimport-input.json").readText()).jsonObject
            val files = ParsedImportCodec().decode(input.getValue("session_id").jsonPrimitive.content, input.getValue("session").toString().encodeToByteArray()).files
            val at = Instant.parse("2026-10-01T09:00:00Z"); val store = Store(at); val staged = store.create(f.workspace, f.user, files)
            val choices = mapOf("account_selections" to CashImportFunding.scan(files).groups.map { mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to "101", "expected_account_revision" to 1) },
                "row_choices" to emptyList<Any>(), "relation_choices" to emptyList<Any>(), "entry_choices" to emptyList<Any>())
            val plan = CashImportHttpService(f.database, store).preview(f.token, staged.sessionId, 1, null, choices)
            val summary = plan.getValue("summary") as Map<*, *>
            assertEquals(1, summary["create_posted"].toString().toInt()); assertEquals(0, summary["create_draft"]?.toString()?.toInt() ?: 0)
            val receipt = CashImportCommandService(f.database, store, now = { at }).commit(f.token, plan.getValue("plan_id") as String,
                (plan.getValue("session_revision") as Number).toLong(), "synthetic-reimport-after-delete")
            val result = ReceiptReadService(f.database).results(f.token, receipt.getValue("receipt_id").jsonPrimitive.content, "row", ReceiptPage(0, 200)).getValue("items").jsonArray.single().jsonObject
            assertEquals(JsonPrimitive("created_posted"), result["outcome"]); assertNotEquals(old.toString(), result.getValue("transaction_id").jsonPrimitive.content)
        }
    }
    @Test fun fundingCleanupUsesComponentIdsAndPreservesInvestmentFactsAndOtherFunding() {
        val f = Fixture("funding"); val before = f.snapshot(); val facts = f.facts()
        val result = f.service().deleteRecord(f.token, "1003"); assertEquals(setOf(1003L), ids(result))
        assertEquals(listOf(58002L), f.read { it.sql.select(CASH_INVESTMENT_FUNDING_RELATIONS.ID).from(CASH_INVESTMENT_FUNDING_RELATIONS)
            .where(CASH_INVESTMENT_FUNDING_RELATIONS.WORKSPACE_ID.eq(f.workspace)).fetch().map { it.value1() } })
        val after = f.snapshot(); assertEquals(before.getValue("investment_events"), after.getValue("investment_events"))
        f.preserved(facts, before.getValue("cash_transaction_components"))
    }
    @Test fun previewRechecksActiveWorkspaceBeforeReturningAnyOldData() {
        val f = Fixture("revoke"); val before = f.snapshot(); var reached = 0
        val service = CashDeletionService(f.database, beforeReturn = {
            reached++; f.database.write { it.jooq(f.database.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, "other-workspace")
                .where(USER_SESSIONS.USER_ID.eq(f.user)).execute() }
        })
        assertTrue(assertFailsWith<AccessFailure> { service.preview(f.token, f.identifiers, f.version) }.forbidden)
        assertEquals(1, reached); assertEquals(before.filterKeys { it != "user_sessions" }, f.snapshot().filterKeys { it != "user_sessions" })
    }
}
