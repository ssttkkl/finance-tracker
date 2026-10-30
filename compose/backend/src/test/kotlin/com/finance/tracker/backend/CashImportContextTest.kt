package com.finance.tracker.backend

import java.math.BigInteger
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportContextTest {
    private val workspace = "workspace-1"
    private val user = "user-1"

    private fun document(source: String = "alipay"): ParsedCashDocument = ParsedCashDocument(source, "csv:0", "fixture-v1",
        listOf(ParsedCashRow(8, linkedMapOf("amount" to "-20.00", "currency" to "CNY",
            "occurred_at" to "2026-08-15T02:00:00+00:00", "note" to "原始文本",
            "source_payload" to mapOf("金额" to "20.00")), paymentMethod = "fixture-bank", providerId = "001")),
        "fixture-owner", "fixture-owner", CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), 1, 1)

    private fun wechat() = document("wechat").copy(sourceAccountIdentifier = null, sourceAccountLabel = "昵称")

    private fun upload(clientRef: String = "client-1", filename: String = "synthetic.csv",
        content: ByteArray = "synthetic-original".encodeToByteArray(), password: String? = null,
        sourceHint: String? = null, sourceScopeLabel: String? = null, lastModifiedAtMs: Long? = null) =
        CashImportUpload(clientRef, filename, content, password, sourceHint, sourceScopeLabel, lastModifiedAtMs)

    private fun service(store: ParsedImportStore = InMemoryParsedImportStore(),
        parser: (CashImportUpload, ByteArray) -> ParsedCashDocument = { _, _ -> document() },
        maxUploadBytes: Int = CashImportContext.MAX_UPLOAD_BYTES) =
        CashImportContextService(store, workspace, user, parser, maxUploadBytes)

    private fun sessionOf(preview: Map<String, Any?>, revision: Long) = ParsedImportSession("session",
        workspace, user, Instant.EPOCH, Instant.EPOCH.plusSeconds(60), BigInteger.valueOf(revision), emptyList(), preview)

    private fun golden(): JsonObject = Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-import-context.json")!!
        .bufferedReader().use { it.readText() }).jsonObject

    @Test fun displayFilenameMatchesPythonContracts() {
        val cases = golden().getValue("filenames").jsonArray
        assertEquals(20, cases.size)
        for ((index, raw) in cases.withIndex()) {
            val item = raw.jsonObject
            val password = item["password"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            assertEquals(item.getValue("expected").jsonPrimitive.content,
                CashImportContext.displayFilename(item.getValue("filename").jsonPrimitive.content, password), "case $index")
        }
    }

    @Test fun selectionInvalidationMatchesPythonContracts() {
        val cases = golden().getValue("invalidations").jsonArray
        assertEquals(5, cases.size)
        for ((index, raw) in cases.withIndex()) {
            val item = raw.jsonObject
            val preview = (cashSourceValue(item.getValue("preview_input")) as Map<*, *>).entries
                .associate { it.key as String to it.value }
            val session = sessionOf(preview, item.getValue("revision").jsonPrimitive.long)
            val refs = item.getValue("obsolete_refs").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val result = service().invalidateInput(session, refs)
            @Suppress("UNCHECKED_CAST")
            val expected = cashSourceValue(item.getValue("expected")) as Map<String, Any?>
            assertEquals(expected, result, "case $index")
        }
    }

    @Test fun eachFileParsedOnceAndOnlyParsedRowsAreKept() {
        val calls = mutableListOf<String>()
        val store = InMemoryParsedImportStore()
        val created = service(store, parser = { upload, _ -> calls += upload.clientFileRef; document() })
            .create(listOf(upload(password = "private-password"), upload("client-2")))
        assertEquals(listOf("client-1", "client-2"), calls)
        val restored = service(store, parser = { _, _ -> fail("must not parse again") }).get(created.sessionId)
        assertEquals(created, restored)
        assertEquals(listOf("client-1", "client-2"), calls)
        assertEquals("原始文本", created.files[0].document!!.rows[0].fields!!["note"])
        assertFalse(created.toString().contains("private-password"))
        assertFalse(created.toString().contains("synthetic-original"))
    }

    @Test fun parserFailureAndPasswordRequestAreIsolated() {
        val store = InMemoryParsedImportStore()
        val created = service(store, parser = { upload, _ ->
            when (upload.clientFileRef) {
                "bad" -> throw StatementDocumentFailure("statement_header_invalid")
                "password" -> throw StatementPdfPasswordFailure(required = true)
                else -> document()
            }
        }).create(listOf(upload("bad"), upload("good"), upload("password")))
        assertEquals(listOf("failed", "parsed", "needs_password"), created.files.map { it.status })
        assertEquals(listOf("statement_header_invalid", null, "password_required"), created.files.map { it.errorCode })
        assertNotNull(created.files[0].diagnostic)
        assertEquals("file_read", created.files[0].diagnostic!!["stage"])
        assertNull(created.files[2].document)
        val fixed = service(store, parser = { _, _ -> document() }).replaceFile(created.sessionId,
            created.files[0].fileRef, upload("bad"), created.revision)
        assertNull(fixed.files[0].diagnostic)
        assertEquals("parsed", fixed.files[0].status)
    }

    @Test fun unexpectedInternalParserErrorStopsTheWholeOperation() {
        val store = InMemoryParsedImportStore()
        val calls = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            CashImportContextService(store, workspace, user, { upload, _ ->
                calls += upload.clientFileRef
                throw IllegalStateException("private provider detail")
            }).create(listOf(upload("first"), upload("second")))
        }
        assertEquals(listOf("first"), calls)
    }

    @Test fun replaceReparsesOnlyUploadedFileAndChangesRowNamespace() {
        val calls = mutableListOf<String>()
        val store = InMemoryParsedImportStore()
        val service = service(store, parser = { upload, _ -> calls += upload.clientFileRef; document() })
        val created = service.create(listOf(upload(), upload("client-2")))
        val oldRef = created.files[0].fileRef
        store.replace(created.sessionId, workspace, user, created.revision, created.files,
            mapOf("row_choices" to listOf(mapOf("row_ref" to "$oldRef:r8"), mapOf("row_ref" to "${created.files[1].fileRef}:r8"))),
            mapOf("plan_id" to "obsolete"))
        val planned = service.get(created.sessionId)
        val updated = service.replaceFile(created.sessionId, oldRef, upload(password = "one-use"), planned.revision)
        assertEquals(listOf("client-1", "client-2", "client-1"), calls)
        assertEquals(planned.revision + BigInteger.ONE, updated.revision)
        assertNull(updated.plan)
        assertNotEquals(oldRef, updated.files[0].fileRef)
        assertEquals(created.files[1], updated.files[1])
        assertEquals(listOf(mapOf("row_ref" to "${created.files[1].fileRef}:r8")), updated.previewInput["row_choices"])
        assertFalse(updated.toString().contains("one-use"))
    }

    @Test fun wechatScopeChangeInvalidatesOnlyItsChoicesWithoutParsingOrExtendingTtl() {
        val calls = mutableListOf<String>()
        val store = InMemoryParsedImportStore()
        val service = service(store, parser = { upload, _ -> calls += upload.clientFileRef; wechat() })
        val created = service.create(listOf(upload(), upload("client-2")))
        val first = created.files[0]
        val second = created.files[1]
        store.replace(created.sessionId, workspace, user, created.revision, created.files,
            mapOf("previous_plan_id" to "previous", "row_choices" to listOf(
                mapOf("row_ref" to "${first.fileRef}:r8", "action" to "apply"),
                mapOf("row_ref" to "${second.fileRef}:r8", "action" to "defer"))),
            mapOf("plan_id" to "previous"))
        val planned = service.get(created.sessionId)
        val changed = service.setFileSourceScope(created.sessionId, first.fileRef, "  工作微信  ", planned.revision)
        assertEquals(planned.revision + BigInteger.ONE, changed.revision)
        assertNull(changed.plan)
        assertEquals("工作微信", changed.files[0].sourceScopeLabel)
        assertNull(changed.files[1].sourceScopeLabel)
        assertEquals(first.document, changed.files[0].document)
        assertEquals(listOf("client-1", "client-2"), calls)
        assertEquals(created.expiresAt, changed.expiresAt)
        assertEquals(listOf(mapOf("row_ref" to "${second.fileRef}:r8", "action" to "defer")), changed.previewInput["row_choices"])
        assertEquals(changed, service.get(created.sessionId))
    }

    @Test fun scopeSettingIsOnlyForWechatAndInvalidValuesLeaveSessionUnchanged() {
        val alipayStore = InMemoryParsedImportStore()
        val alipayService = service(alipayStore)
        val alipay = alipayService.create(listOf(upload()))
        assertFailsWith<CashSourceScopeError> {
            alipayService.setFileSourceScope(alipay.sessionId, alipay.files[0].fileRef, "工作", alipay.revision)
        }
        assertEquals(alipay, alipayService.get(alipay.sessionId))
        val service = service(parser = { _, _ -> wechat() })
        val created = service.create(listOf(upload()))
        val invalid = assertFailsWith<CashSourceScopeError> {
            service.setFileSourceScope(created.sessionId, created.files[0].fileRef, "invalid\n", created.revision)
        }
        assertEquals("source_scope_invalid", invalid.code)
        assertEquals(created, service.get(created.sessionId))
    }

    @Test fun replacementPreservesWechatScopeWithoutReusingItForOtherChannels() {
        val service = service(parser = { upload, _ ->
            if (upload.filename == "other.csv") document("alipay") else wechat()
        })
        val created = service.create(listOf(upload(sourceScopeLabel = "工作微信")))
        assertEquals("工作微信", created.files[0].sourceScopeLabel)
        val replaced = service.replaceFile(created.sessionId, created.files[0].fileRef, upload(), created.revision)
        assertEquals("工作微信", replaced.files[0].sourceScopeLabel)
        val cleared = service.setFileSourceScope(created.sessionId, replaced.files[0].fileRef, null, replaced.revision)
        assertNull(cleared.files[0].sourceScopeLabel)
        val named = service.setFileSourceScope(created.sessionId, cleared.files[0].fileRef, "工作微信", cleared.revision)
        val other = service.replaceFile(created.sessionId, named.files[0].fileRef, upload(filename = "other.csv"), named.revision)
        assertNull(other.files[0].sourceScopeLabel)
        assertEquals("parsed", other.files[0].status)
    }

    @Test fun replacementFailurePreservesSelectedWechatSourceForLaterRetry() {
        for (failure in listOf("password.xlsx", "unreadable.xlsx")) {
            val service = service(parser = { upload, _ ->
                when (upload.filename) {
                    "password.xlsx" -> throw StatementPdfPasswordFailure(required = true)
                    "unreadable.xlsx" -> throw StatementDocumentFailure("statement_unreadable")
                    else -> wechat()
                }
            })
            val created = service.create(listOf(upload(sourceScopeLabel = "工作微信")))
            val failed = service.replaceFile(created.sessionId, created.files[0].fileRef, upload(filename = failure), created.revision)
            assertNull(failed.files[0].document)
            assertEquals("工作微信", failed.files[0].sourceScopeLabel)
            val retried = service.replaceFile(created.sessionId, failed.files[0].fileRef, upload(), failed.revision)
            assertEquals("parsed", retried.files[0].status)
            assertEquals("工作微信", retried.files[0].sourceScopeLabel)
            assertEquals(created.expiresAt, retried.expiresAt)
        }
    }

    @Test fun invalidReplacementScopeKeepsTheConfirmedWechatSource() {
        val service = service(parser = { _, _ -> wechat() })
        val created = service.create(listOf(upload(sourceScopeLabel = "工作微信")))
        val replaced = service.replaceFile(created.sessionId, created.files[0].fileRef,
            upload(sourceScopeLabel = "invalid\n"), created.revision)
        assertEquals("工作微信", replaced.files[0].sourceScopeLabel)
        assertEquals("wechat", replaced.files[0].sourceType)
        assertEquals("source_scope_invalid", replaced.files[0].errorCode)
    }

    @Test fun addRemoveAndStaleRevisionDoNotRereadOriginalFiles() {
        val calls = mutableListOf<String>()
        val service = service(parser = { upload, _ -> calls += upload.clientFileRef; document() })
        val first = service.create(listOf(upload()))
        val added = service.addFiles(first.sessionId, listOf(upload("client-2")), first.revision)
        val removed = service.removeFile(first.sessionId, first.files[0].fileRef, added.revision)
        assertEquals(listOf("client-1", "client-2"), calls)
        assertEquals(listOf(added.files[1]), removed.files)
        assertFailsWith<ParsedImportRevisionConflict> {
            service.addFiles(first.sessionId, listOf(upload("stale")), first.revision)
        }
        assertEquals(listOf("client-1", "client-2"), calls)
    }

    @Test fun oversizedFileKeepsObservedSizeAndDoesNotInvokeParser() {
        val calls = mutableListOf<CashImportUpload>()
        val created = service(parser = { upload, _ -> calls += upload; document() }, maxUploadBytes = 5)
            .create(listOf(upload(content = "123456".encodeToByteArray())))
        assertTrue(calls.isEmpty())
        assertEquals(6, created.files[0].size)
        assertEquals("failed", created.files[0].status)
        assertEquals("statement_file_too_large", created.files[0].errorCode)
        assertNull(created.files[0].document)
    }

    @Test fun aggregateBudgetMarksNewFileFailedAndKeepsExistingFile() {
        val store = InMemoryParsedImportStore(codec = ParsedImportCodec(maxBytes = 10_000, maxParsedBytes = 10_000))
        val service = service(store, parser = { upload, _ ->
            if (upload.clientFileRef == "large") document().copy(rows = listOf(document().rows[0].let { row ->
                row.copy(fields = row.fields!! + ("note" to "x".repeat(20_000)))
            })) else document()
        })
        val first = service.create(listOf(upload()))
        val added = service.addFiles(first.sessionId, listOf(upload("large")), first.revision)
        assertEquals(first.files[0], added.files[0])
        assertEquals("failed", added.files[1].status)
        assertEquals("statement_rows_too_large", added.files[1].errorCode)
    }

    @Test fun cancelClearsBodyAndCannotBeReopened() {
        val service = service()
        val first = service.create(listOf(upload()))
        val cancelled = service.cancel(first.sessionId)
        assertTrue(cancelled.cancelled)
        assertTrue(cancelled.files.isEmpty())
        assertEquals(cancelled, service.cancel(first.sessionId))
        assertFailsWith<ParsedImportClosed> {
            service.addFiles(first.sessionId, listOf(upload("later")), cancelled.revision)
        }
    }

    @Test fun privateContextIsNotAPublicImport() {
        val store = InMemoryParsedImportStore()
        val service = service(store)
        val private = store.create(workspace, user, emptyList(), workflowKind = "mutation")
        assertFailsWith<ParsedImportNotFound> { service.get(private.sessionId) }
        assertFailsWith<ParsedImportNotFound> { service.cancel(private.sessionId) }
        assertFailsWith<ParsedImportNotFound> { service.addFiles(private.sessionId, listOf(upload("extra")), private.revision) }
    }

    @Test fun sourceCanBeDetectedAndConvertedWithoutASecondReader() {
        val csv = "交易时间,收/支,金额,交易对方,商品说明,交易订单号\n2026-08-15 10:00:00,支出,20.00,测试商户,原始说明,001\n"
        val service = CashImportContextService(InMemoryParsedImportStore(), workspace, user)
        val created = service.create(listOf(upload(content = csv.encodeToByteArray())))
        assertEquals("alipay", created.files[0].sourceType)
        assertEquals("-20.00", created.files[0].document!!.rows[0].fields!!["amount"])
    }
}
