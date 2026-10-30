package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class MutationPlanCommitHttpContractTest {
    private fun financial(db: Database) = db.read { connection ->
        listOf("accounts", "cash_transactions", "cash_transaction_components", "cash_transaction_issues", "cash_entries",
            "cash_entry_members", "cash_projection_states", "cash_projection_datasets", "cash_projections", "cash_projection_members",
            "cash_projection_relations", "transaction_relations", "cash_investment_funding_relations", "ledger_snapshots",
            "cash_mutation_receipts", "cash_mutation_results").associateWith { table ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM $table WHERE workspace_id='commit-a'").use { rows ->
                    buildSet { while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) }) }
                }
            }
        }
    }
    private fun failCommit(connection: java.sql.Connection, active: java.util.concurrent.atomic.AtomicBoolean): java.sql.Connection {
        fun invoke(target: Any, method: java.lang.reflect.Method, arguments: Array<out Any?>?): Any? =
            try { method.invoke(target, *(arguments ?: emptyArray())) }
            catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
        return java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection::class.java.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
            if (method.name == "commit" && active.compareAndSet(true, false)) throw java.sql.SQLException("synthetic commit failure")
            val result = invoke(connection, method, args)
            if (method.name != "createStatement") result else java.lang.reflect.Proxy.newProxyInstance(java.sql.Statement::class.java.classLoader,
                arrayOf(java.sql.Statement::class.java)) { _, statementMethod, statementArgs ->
                if (statementMethod.name == "execute" && statementArgs?.firstOrNull() == "COMMIT" && active.compareAndSet(true, false))
                    throw java.sql.SQLException("synthetic commit failure")
                invoke(result!!, statementMethod, statementArgs)
            }
        } as java.sql.Connection
    }
    private fun database() = Json.parseToJsonElement(Path.of(requireNotNull(
        System.getenv("FT_KOTLIN_COMMIT_CONTRACT_DIR"))).resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    @Test fun editRecordCommitMatchesPythonReceiptAndState() = testApplication {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_COMMIT_CONTRACT_DIR")))
        val expected = Json.parseToJsonElement(root.resolve("commit.json").readText()).jsonObject
        val db = database()
        val objects = R2ParsedImportStoreTest.Client()
        val staging = R2ParsedImportStore(objects, "bucket")
        application { financeModule(db, mutationPlans = MutationPlanService(db, staging = staging)) }
        val created = client.post("/api/v1/cash-mutations/plans") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("intent", "edit_record"); put("transaction_id", "2001"); put("expected_revision", 1)
                put("patch", buildJsonObject { put("note", "提交备注") })
            }.toString())
        }
        assertEquals(200, created.status.value, created.bodyAsText())
        val plan = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        val planId = plan.getValue("plan_id").jsonPrimitive.content
        val early = client.post("/api/v1/cash-mutations/resolve") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("plan_id", planId); put("idempotency_key", "resolve-early") }.toString())
        }
        assertEquals(200, early.status.value)
        assertEquals("retry_original", Json.parseToJsonElement(early.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content)
        val before = db.read { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT note,revision FROM cash_entries WHERE id='entry-2001'").use { rows ->
                assertTrue(rows.next()); rows.getString(1) to rows.getLong(2)
            } }
        }
        val failing = java.util.concurrent.atomic.AtomicBoolean(true)
        val databaseInfo = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val failedDb = Database(databaseInfo.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")) { failCommit(it, failing) }
        val priorWrites = objects.writes.size
        val financialBefore = financial(db)
        assertFailsWith<StorageFailure> { MutationPlanService(failedDb, staging = staging).commit("synthetic-admin", planId, 1, "commit-edit-record") }
        assertFalse(failing.get()); assertEquals(priorWrites, objects.writes.size)
        assertEquals(financialBefore, financial(db))
        assertFalse(staging.get(MutationPlanStore.contextId(planId), "commit-a", "admin").cancelled)
        db.read { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT note,revision FROM cash_entries WHERE id='entry-2001'").use { rows ->
                    assertTrue(rows.next()); assertEquals(before, rows.getString(1) to rows.getLong(2))
                }
                s.executeQuery("SELECT count(*) FROM cash_mutation_receipts WHERE workspace_id='commit-a'").use { rows ->
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1))
                }
            }
        }
        // A failed cleanup after a committed receipt must leave the successful result readable.
        objects.failAfterPut = true
        var cleanupAfterCommit = false
        objects.afterPut = {
            db.read { c -> c.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM cash_mutation_receipts WHERE workspace_id='commit-a'").use { rows ->
                    assertTrue(rows.next()); assertEquals(1, rows.getInt(1))
                }
            } }
            cleanupAfterCommit = true
        }
        val committed = client.post("/api/v1/cash-mutations/plans/$planId/commit") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            header("Idempotency-Key", "commit-edit-record")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("expected_plan_revision", 1) }.toString())
        }
        assertEquals(200, committed.status.value, committed.bodyAsText())
        assertTrue(cleanupAfterCommit)
        val receipt = Json.parseToJsonElement(committed.bodyAsText()).jsonObject
        val privateContext = staging.get(MutationPlanStore.contextId(planId), "commit-a", "admin")
        assertTrue(privateContext.cancelled); assertTrue(privateContext.contextData.isEmpty()); assertNull(privateContext.plan)
        objects.failRead = true
        for (path in listOf("/api/v1/cash-mutations/plans/$planId", "/api/v1/cash-mutations/plans/$planId/items",
                "/api/v1/cash-mutations/plans/$planId/items/pi_1",
                "/api/v1/cash-mutations/edit-contexts/${privateContext.sessionId}/plan")) {
            val readConsumed = client.get(path) { header(HttpHeaders.Authorization, "Bearer synthetic-admin") }
            assertEquals(409, readConsumed.status.value, readConsumed.bodyAsText())
            assertEquals("edit_context_committed", errorCode(readConsumed.bodyAsText()))
        }
        val previewConsumed = client.post("/api/v1/cash-mutations/plans") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("intent", "edit_record"); put("transaction_id", "2001"); put("expected_revision", 1)
                put("edit_context_id", privateContext.sessionId); put("previous_plan_id", planId)
                put("expected_plan_revision", 1)
            }.toString())
        }
        assertEquals(409, previewConsumed.status.value)
        assertEquals("edit_context_committed", errorCode(previewConsumed.bodyAsText()))
        objects.failRead = false
        val expectedReceipt = expected.getValue("receipt").jsonObject
        for ((key, value) in expectedReceipt) assertEquals(value, receipt[key], key)
        assertEquals(planId, receipt.getValue("plan_id").jsonPrimitive.content)
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { output ->
            val directory = Path.of(output)
            java.nio.file.Files.createDirectories(directory)
            java.nio.file.Files.writeString(directory.resolve("mutation-receipt-client.json"), receipt.toString())
        }
        val state = db.read { connection ->
            val entry = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT note,revision,manual_fields FROM cash_entries WHERE workspace_id='commit-a' AND id='entry-2001'").use {
                    it.next(); Triple(it.getString(1), it.getLong(2), it.getString(3))
                }
            }
            val transaction = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT note,row_revision FROM cash_transactions WHERE workspace_id='commit-a' AND id=2001").use {
                    it.next(); it.getString(1) to it.getLong(2)
                }
            }
            val projection = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT projection_version,availability,member_count FROM cash_projection_states WHERE workspace_id='commit-a'").use {
                    it.next(); Triple(it.getLong(1), it.getString(2), it.getLong(3))
                }
            }
            Triple(entry, transaction, projection)
        }
        val expectedEntry = expected.getValue("entry").jsonObject
        assertEquals(expectedEntry.getValue("note").jsonPrimitive.content, state.first.first)
        assertEquals(expectedEntry.getValue("revision").jsonPrimitive.long, state.first.second)
        assertEquals(expectedEntry.getValue("manual_fields").jsonArray.size, Json.parseToJsonElement(state.first.third).jsonArray.size)
        assertEquals(expected.getValue("transaction").jsonObject.getValue("note").jsonPrimitive.content, state.second.first)
        assertEquals(expected.getValue("transaction").jsonObject.getValue("row_revision").jsonPrimitive.long, state.second.second)
        assertEquals(expected.getValue("projection").jsonObject.getValue("version").jsonPrimitive.long, state.third.first)
        assertEquals(expected.getValue("projection").jsonObject.getValue("availability").jsonPrimitive.content, state.third.second)
        assertEquals(expected.getValue("projection").jsonObject.getValue("member_count").jsonPrimitive.long, state.third.third)

        val resolved = client.post("/api/v1/cash-mutations/resolve") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("plan_id", planId); put("idempotency_key", "commit-edit-record") }.toString())
        }
        assertEquals(200, resolved.status.value)
        val resolvedBody = Json.parseToJsonElement(resolved.bodyAsText()).jsonObject
        assertEquals("committed", resolvedBody.getValue("status").jsonPrimitive.content)
        assertEquals(receipt.getValue("receipt_id"), resolvedBody.getValue("receipt").jsonObject.getValue("receipt_id"))
        val byContext = client.post("/api/v1/cash-mutations/resolve") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("plan_id", planId); put("idempotency_key", "resolve-new") }.toString())
        }
        assertEquals("committed", Json.parseToJsonElement(byContext.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content)
        val replay = client.post("/api/v1/cash-mutations/plans/$planId/commit") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            header("Idempotency-Key", "commit-edit-record")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("expected_plan_revision", 1) }.toString())
        }
        assertEquals(200, replay.status.value)
        assertEquals(receipt.getValue("receipt_id"), Json.parseToJsonElement(replay.bodyAsText())
            .jsonObject.getValue("receipt_id"))
        val consumed = client.post("/api/v1/cash-mutations/plans/$planId/commit") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            header("Idempotency-Key", "commit-edit-record-2")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("expected_plan_revision", 1) }.toString())
        }
        assertEquals(409, consumed.status.value)
        assertEquals("edit_context_committed", errorCode(consumed.bodyAsText()))
        val malformed = client.post("/api/v1/cash-mutations/resolve") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("plan_id", "bad"); put("idempotency_key", "resolve-bad") }.toString())
        }
        assertEquals(404, malformed.status.value)
        assertEquals("plan_not_found", errorCode(malformed.bodyAsText()))
        val missingResolveKey = client.post("/api/v1/cash-mutations/resolve") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("plan_id", planId) }.toString())
        }
        assertEquals(422, missingResolveKey.status.value)
        assertEquals("plan_input_invalid", errorCode(missingResolveKey.bodyAsText()))
        val missing = client.post("/api/v1/cash-mutations/plans/$planId/commit") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("expected_plan_revision", 1) }.toString())
        }
        assertEquals(422, missing.status.value)
        assertEquals("idempotency_key_required", errorCode(missing.bodyAsText()))
    }

    private fun errorCode(body: String): String? =
        (Json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
}
