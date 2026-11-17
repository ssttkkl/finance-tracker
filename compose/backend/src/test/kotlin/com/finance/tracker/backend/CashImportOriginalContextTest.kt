package com.finance.tracker.backend

import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashImportOriginalContextTest {
    private fun fixture(name: String) = CashImportOriginalSessionFixture(name, rootVariable = "FT_KOTLIN_IMPORT_CONTEXT_CONTRACT_DIR")
    private fun upload(ref: String = "client-1", filename: String = "synthetic.csv", content: ByteArray = "synthetic-original".encodeToByteArray(), password: String? = null) =
        CashImportUpload(ref, filename, content, password)
    private fun created(f: CashImportOriginalSessionFixture): ParsedImportSession {
        val view = f.service.create(f.base.token, listOf(upload()))
        return f.base.store.get(view.getValue("session_id") as String, f.base.workspace, f.base.user)
    }
    private fun consume(f: CashImportOriginalSessionFixture, id: String) = f.base.database.write { connection ->
        ReceiptRepository(connection, f.base.workspace, f.base.database.sqlite).create(user = f.base.user, key = "committed",
            requestDigest = "a".repeat(64), operation = "import", planId = "plan-1", sessionDigest = ParsedImportStore.digest(id.encodeToByteArray()),
            resultSummary = mapOf("status" to "no_changes"), results = emptyList<Any?>())
    }

    @Test fun privateEditContextIsNotPublicImport() = testApplication {
        val f = fixture("private"); val before = f.base.snapshot()
        application { financeModule(f.base.database) }
        created(f)
        val hidden = f.base.store.create(f.base.workspace, f.base.user, emptyList(), workflowKind = "mutation")
        val planId = "ip_${hidden.sessionId}." + "p".repeat(16)
        f.base.store.replace(hidden.sessionId, hidden.workspaceId, hidden.userId, 1, emptyList(), emptyMap(), mapOf("plan_id" to planId))
        for (operation in listOf<() -> Any?>({ f.service.view(f.base.token, hidden.sessionId) },
            { f.service.cancel(f.base.token, hidden.sessionId) },
            { f.service.addFiles(f.base.token, hidden.sessionId, listOf(upload("extra")), 2) })) assertFailsWith<ParsedImportNotFound> { operation() }
        for (operation in listOf<() -> Any?>({ f.service.preview(f.base.token, hidden.sessionId, 2, null, emptyMap()) },
            { f.service.plan(f.base.token, planId) }, { f.base.commands.commit(f.base.token, planId, 2, "synthetic-private-context") })) {
            assertEquals("plan_expired", assertFailsWith<CashImportPlanError> { operation() }.code)
        }
        assertEquals(buildJsonObject { put("status", "not_committed") }, f.base.commands.resolve(f.base.token, planId, "synthetic-private-context"))
        val response = f.base.post(client, "/api/v1/cash-mutations/resolve", buildJsonObject {
            put("plan_id", planId); put("idempotency_key", "synthetic-private-context")
        }.toString())
        assertEquals(200, response.status.value, response.bodyAsText())
        assertEquals(buildJsonObject { put("status", "not_committed") }, response.apiBody())
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { path ->
            val directory = Path.of(path); require(directory.isAbsolute); Files.createDirectories(directory)
            Files.writeString(directory.resolve("cash-import-private-resolve.json"), buildJsonObject {
                put("backend", if (f.base.database.sqlite) "sqlite" else "postgresql")
                put("plan_id", planId); put("status", response.status.value); put("body", response.apiBody())
            }.toString())
        }
        assertEquals(listOf("client-1"), f.calls); assertEquals(before, f.base.snapshot())
    }

    @Test fun revokedPermissionIsRecheckedAfterParsingBeforePublication() {
        val f = fixture("revoke")
        f.parser = { f.base.role("viewer"); f.document }
        assertFailsWith<AccessFailure> { created(f) }
        assertEquals(listOf("client-1"), f.calls)
        assertEquals(0, f.base.store.size())
    }

    @Test fun consumptionDuringParsingPreventsLatePublication() {
        val f = fixture("late_commit"); val first = created(f)
        f.parser = { consume(f, first.sessionId); f.document }
        assertEquals("session_committed", assertFailsWith<ReceiptConflict> {
            f.service.addFiles(f.base.token, first.sessionId, listOf(upload("late")), 1)
        }.code)
        assertEquals(first, f.base.store.get(first.sessionId, f.base.workspace, f.base.user))
    }

    @Test fun consumedSessionResolvesBeforeStagingAndCannotBeCancelled() {
        val f = fixture("consumed"); val first = created(f); val receipt = consume(f, first.sessionId)
        f.base.store.clear()
        val context = f.service.view(f.base.token, first.sessionId)
        assertEquals(receipt.apiText("receipt_id"), context["receipt_id"]); assertEquals("committed", context["state"])
        assertNull(context["session_revision"]); assertEquals(emptyList<Any?>(), context["files"])
        assertEquals("session_committed", assertFailsWith<ReceiptConflict> { f.service.cancel(f.base.token, first.sessionId) }.code)
    }
}
