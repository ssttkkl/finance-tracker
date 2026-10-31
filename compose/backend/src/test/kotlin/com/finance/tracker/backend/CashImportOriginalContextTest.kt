package com.finance.tracker.backend

import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes
import kotlin.test.*
import kotlinx.serialization.json.*

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
        application { financeModule(f.base.database, cashImports = f.service) }
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

    @Test fun actualBankBytesUseDefaultParserAndPreserveIncompleteRows() {
        val f = fixture("bank"); val before = f.base.snapshot()
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_CONTEXT_CONTRACT_DIR"))).resolve("bank")
        val service = CashImportHttpService(f.base.database, f.base.store)
        val view = service.create(f.base.token, listOf(upload("ccb", "misleading.csv", root.resolve("ccb.bin").readBytes()),
            upload("asia", "misleading.xls", root.resolve("asia.bin").readBytes()), upload("bad", content = "unrecognized".encodeToByteArray())))
        val session = f.base.store.get(view.getValue("session_id") as String, f.base.workspace, f.base.user)
        assertEquals(listOf("parsed", "parsed", "failed"), session.files.map { it.status })
        assertEquals(listOf("amount_unreadable"), session.files[0].document!!.rows[1].issueCodes)
        val asia = session.files[1].document!!
        val values = assertNotNull(asia.rows[0].fields, "${asia.sourceType} ${asia.rows[0].errorCode} ${asia.rows[0].issueCodes}")
        assertEquals("-100.50", values["amount"])
        assertEquals(1, asia.observedCount); assertEquals(1L, asia.declaredCount); assertEquals(9, asia.rows.single().position)
        assertEquals("  原始備註\n  ", (values["source_payload"] as Map<*, *>)["備註"])
        assertEquals(session, f.base.store.get(session.sessionId, f.base.workspace, f.base.user))
        assertNull(session.files[0].document!!.declaredCount); assertEquals(before, f.base.snapshot())
    }

    @Test fun asiaQuotedTabsQuotesAndNewlinesRemainOneLogicalRecordAndBadQuotesFailClosed() {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_CONTEXT_CONTRACT_DIR"))).resolve("bank")
        val raw = BankStatementDocumentReader.readBankDocument(root.resolve("asia-quoted.bin").readBytes())
        assertEquals(1, raw.rows.size); assertEquals(1, raw.observedCount); assertEquals(1L, raw.declaredCount)
        assertEquals(9, raw.rows.single().position); assertEquals("a\t\"quoted\"\nrest", raw.rows.single().payload!!["備註"])
        assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure> {
            BankStatementDocumentReader.readBankDocument(root.resolve("asia-bad-quote.bin").readBytes())
        }.code)
    }

    @Test fun realEncryptedPdfRetryUsesEachNewUploadAndNeverStagesPassword() {
        val f = fixture("pdf"); val before = f.base.snapshot()
        val source = Json.parseToJsonElement(javaClass.getResourceAsStream("/python-icbc-pdf-documents.json")!!.bufferedReader().use { it.readText() })
            .jsonArray.single { it.jsonObject.apiText("name") == "password-ok" }.jsonObject
        val content = Base64.getDecoder().decode(source.apiText("content")); val password = source.apiText("password")
        val observed = mutableListOf<String?>()
        val service = CashImportHttpService(f.base.database, f.base.store) { upload, bytes ->
            observed += upload.password; assertContentEquals(content, bytes)
            CashStatementRowNormalization.parseCashStatement(bytes, upload.sourceHint, upload.password)
        }
        val name = "工行（密码$password）.pdf"
        val firstView = service.create(f.base.token, listOf(upload(filename = name, content = content)))
        val first = f.base.store.get(firstView.getValue("session_id") as String, f.base.workspace, f.base.user)
        assertEquals("needs_password", first.files[0].status); assertEquals("password_required", first.files[0].errorCode)
        assertFalse(password in first.files[0].filename)
        service.replaceFile(f.base.token, first.sessionId, first.files[0].fileRef, upload(filename = name, content = content, password = "wrong"), 1)
        val wrong = f.base.store.get(first.sessionId, f.base.workspace, f.base.user)
        assertEquals("needs_password", wrong.files[0].status); assertEquals("password_incorrect", wrong.files[0].errorCode)
        service.replaceFile(f.base.token, first.sessionId, wrong.files[0].fileRef, upload(filename = name, content = content, password = password), 2)
        val finished = f.base.store.get(first.sessionId, f.base.workspace, f.base.user)
        assertEquals("parsed", finished.files[0].status); assertEquals(3, finished.revision.toInt()); assertEquals(first.expiresAt, finished.expiresAt)
        assertEquals(listOf(null, "wrong", password), observed)
        assertEquals(finished, f.base.store.get(first.sessionId, f.base.workspace, f.base.user))
        assertFalse(password in finished.toString()); assertFalse(password in f.base.store.raw(first.sessionId).decodeToString())
        assertEquals(before, f.base.snapshot())
    }

    @Test fun parsingDoesNotHoldWorkspaceLock() {
        val f = fixture("parse_lock"); val before = f.base.snapshot()
        f.parser = {
            val entered = CountDownLatch(1); val executor = Executors.newSingleThreadExecutor()
            try {
                val other = executor.submit { f.base.database.write { connection ->
                    assertTrue(connection.lockWorkspace(f.base.database.sqlite, f.base.workspace)); entered.countDown()
                } }
                assertTrue(entered.await(2, TimeUnit.SECONDS), "heavy parsing must be outside the workspace boundary")
                other.get(2, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
            f.document
        }
        assertEquals("parsed", created(f).files[0].status); assertEquals(before, f.base.snapshot())
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

    @Test fun wechatScopeUsesCurrentRevisionAndDurableConsumptionBoundary() {
        val f = fixture("scope_revision"); f.parser = { f.document.copy(sourceType = "wechat") }
        val first = created(f)
        assertFailsWith<ParsedImportRevisionConflict> { f.service.setFileSourceScope(f.base.token, first.sessionId, first.files[0].fileRef, "工作", 999) }
        consume(f, first.sessionId)
        assertEquals("session_committed", assertFailsWith<ReceiptConflict> {
            f.service.setFileSourceScope(f.base.token, first.sessionId, first.files[0].fileRef, "工作", 1)
        }.code)
        assertNotNull(f.service.view(f.base.token, first.sessionId)["receipt_id"])
    }

    @Test fun wechatScopeRechecksCurrentWritePermissionWithoutChangingSession() {
        val f = fixture("scope_permission"); f.parser = { f.document.copy(sourceType = "wechat") }
        val first = created(f); f.base.role("viewer")
        assertFailsWith<AccessFailure> { f.service.setFileSourceScope(f.base.token, first.sessionId, first.files[0].fileRef, "工作", 1) }
        assertEquals(first, f.base.store.get(first.sessionId, f.base.workspace, f.base.user))
        assertEquals(first.sessionId, f.service.view(f.base.token, first.sessionId)["session_id"])
    }
}
