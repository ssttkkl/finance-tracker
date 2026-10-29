package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.FileSource
import com.finance.tracker.domain.BoundedFileSource
import com.finance.tracker.domain.FileReadLimitExceeded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.writeString
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportContextApiTest {
    @Test fun savesProgressBeforeReadingOrSendingNextFile() = runTest {
        val started = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        var requests = 0
        val client = client { requests++ }
        val second = ContextFile("two.csv", byteArrayOf(2))
        val uploading = async {
            client.createCashImportSession(listOf(CashImportUploadInput("a", ContextFile("one.csv", byteArrayOf(1))), CashImportUploadInput("b", second)), onSession = {
                if (requests == 1) { started.complete(Unit); saved.await() }
            })
        }
        started.await()
        assertEquals(1, requests)
        assertEquals(0, second.reads)
        saved.complete(Unit)
        assertNotNull(uploading.await().session)
        assertEquals(2, requests)
        client.close()
    }

    @Test fun failedProgressSaveStopsBatchWithoutCallingItAFileFailure() = runTest {
        var requests = 0
        val client = client { requests++ }
        val second = ContextFile("two.csv", byteArrayOf(2))
        assertFailsWith<IllegalStateException> {
            client.createCashImportSession(listOf(CashImportUploadInput("a", ContextFile("one.csv", byteArrayOf(1))), CashImportUploadInput("b", second)), onSession = { error("synthetic save failure") })
        }
        assertEquals(1, requests)
        assertEquals(0, second.reads)
        client.close()
    }

    @Test fun partialSessionRetainsExistingSelectionAndOpaqueIds() {
        val value = apiJson.decodeFromString<CashImportSessionDto>(SESSION)
        assertTrue(value.canPreview)
        assertEquals(listOf("ready", "password_required"), value.files.map { it.status })
        assertNull(value.files.last().recognizedRowCount)
        val group = value.sourceAccounts.single()
        assertNull(group.suggestion.accountId)
        assertNull(group.suggestion.expectedAccountRevision)
        assertEquals("none", group.suggestion.basis)
        assertEquals("昵称甲", group.sessionLabel)
        assertEquals("account-group-one", group.sessionGroupRef)
        assertEquals("102", group.selection?.accountId)
        assertEquals("人工备注", value.lastPreviewInput!!["row_choices"]!!.jsonArray.single().jsonObject["patch"]!!.jsonObject["note"]!!.jsonPrimitive.content)
    }

    @Test fun consumedSessionHasNullableRevisionAndPermanentResultReference() {
        val value = apiJson.decodeFromString<CashImportSessionDto>("""{"session_id":"s1","session_revision":null,"expires_at":null,"state":"committed","files":[],"source_accounts":[],"last_preview_input":null,"last_plan_id":null,"can_preview":false,"receipt_id":"receipt-one"}""")
        assertNull(value.sessionRevision)
        assertNull(value.expiresAt)
        assertEquals("receipt-one", value.receiptId)
        assertFalse(value.canPreview)
    }

    @Test fun uploadUsesRawBytesAndPasswordsOnlyInMultipart() = runTest {
        val first = ContextFile("one.csv", byteArrayOf(0, 1, 127, -1), lastModifiedAtMillis = 1725000000123)
        val second = ContextFile("two.pdf", "second-original".encodeToByteArray(), null)
        val wires = mutableListOf<ByteArray>()
        val calls = mutableListOf<HttpRequestData>()
        val client = client { call -> calls += call; wires += call.body.toByteArray() }
        val result = client.createCashImportSession(listOf(CashImportUploadInput("a", first, password = "synthetic-secret"), CashImportUploadInput("b", second)))
        assertTrue(assertNotNull(result.session).canPreview)
        assertTrue(result.localFailures.isEmpty())
        assertNull(result.interruption)
        val captured = calls.first()
        val wire = wires.first()
        assertEquals(listOf("/api/v1/cash-import/sessions", "/api/v1/cash-import/sessions/s1/files"), calls.map { it.url.encodedPath })
        assertEquals(3L, metadata(wires.last())["expected_session_revision"]!!.jsonPrimitive.long)
        assertEquals("/api/v1/cash-import/sessions", captured.url?.encodedPath)
        assertEquals("Bearer synthetic-token", captured.headers?.get(HttpHeaders.Authorization))
        assertTrue(captured.url?.encodedQuery.orEmpty().isEmpty())
        assertNull(captured.headers?.get("X-FT-Statement-Passwords"))
        assertNull(captured.headers?.get("X-FT-Statement-Password"))
        assertTrue(captured.body?.contentType.toString().startsWith("multipart/form-data; boundary="))
        val files = metadata(wire)["files"]!!.jsonArray
        assertEquals(listOf("a"), files.map { it.jsonObject["client_file_ref"]!!.jsonPrimitive.content })
        assertEquals("synthetic-secret", files.first().jsonObject["password"]!!.jsonPrimitive.content)
        assertEquals(1725000000123L, files.first().jsonObject["last_modified_at_ms"]!!.jsonPrimitive.long)
        assertFalse("last_modified_at_ms" in metadata(wires.last())["files"]!!.jsonArray.single().jsonObject)
        assertFalse("password" in metadata(wires.last())["files"]!!.jsonArray.single().jsonObject)
        assertTrue(wire.toList().windowed(4).any { it == listOf<Byte>(0, 1, 127, -1) })
        assertTrue("second-original" in wires.last().decodeToString())
        assertTrue("file:a" in wire.decodeToString())
        assertEquals(1, first.reads); assertEquals(1, second.reads)
        client.close()
    }

    @Test fun replacementUploadsOnlySelectedFileWithoutSourceScopeMetadata() = runTest {
        var body: JsonObject? = null
        var path: String? = null
        val client = client { call -> path = call.url.encodedPath; body = metadata(call.body.toByteArray()) }
        val file = ContextFile("one.pdf", byteArrayOf(5))
        client.addCashImportFiles("session/one", listOf(CashImportUploadInput("a", file, password = "replacement-secret")), 9, "file-server-one")
        assertEquals("/api/v1/cash-import/sessions/session%2Fone/files", path)
        val sent = assertNotNull(body)
        assertEquals(9L, sent["expected_session_revision"]!!.jsonPrimitive.long)
        assertEquals("file-server-one", sent["file_ref"]!!.jsonPrimitive.content)
        assertEquals(1, sent["files"]!!.jsonArray.size)
        assertFalse("source_scope_label" in sent["files"]!!.jsonArray.single().jsonObject)
        assertEquals(1, file.reads)
        client.close()
    }

    @Test fun uploadsNeverSendRemovedSourceScopeMetadata() = runTest {
        val received = mutableListOf<JsonObject>()
        val client = client { received += metadata(it.body.toByteArray())["files"]!!.jsonArray.single().jsonObject }
        repeat(2) {
            client.createCashImportSession(listOf(CashImportUploadInput("a", ContextFile("one.csv", byteArrayOf(1)))))
        }
        assertEquals(2, received.size)
        assertTrue(received.all { "source_scope_label" !in it })
        client.close()
    }

    @Test fun fileCommandsHaveNoSourceSettingPatchAndCancelTouchesOnlySession() = runTest {
        val calls = mutableListOf<HttpRequestData>()
        val client = client { calls += it }
        client.readCashImportSession("s1")
        client.removeCashImportFile("s1", "f1", 4)
        client.cancelCashImportSession("s1")
        assertEquals(listOf("GET", "DELETE", "DELETE"), calls.map { it.method.value })
        assertEquals("4", calls[1].headers["If-Match"])
        assertTrue(calls.all { it.url.encodedPath.startsWith("/api/v1/cash-import/sessions/") })
        assertEquals("/api/v1/cash-import/sessions/s1", calls.last().url.encodedPath)
        client.close()
    }

    @Test fun invalidCollectionsFailBeforeReadingAndAllOversizedReturnLocalResults() = runTest {
        var requests = 0
        val file = ContextFile("one.csv", byteArrayOf(1), 100L * 1024 * 1024 + 1)
        val client = client { requests++ }
        assertFailsWith<ApiFailure> { client.createCashImportSession(emptyList()) }
        assertFailsWith<ApiFailure> { client.createCashImportSession((0..20).map { CashImportUploadInput("f$it", file) }) }
        assertFailsWith<ApiFailure> { client.createCashImportSession(listOf(CashImportUploadInput("same", file), CashImportUploadInput("same", file))) }
        val result = client.createCashImportSession(listOf(CashImportUploadInput("a", file)))
        assertNull(result.session)
        assertEquals(listOf("import_file_too_large"), result.localFailures.map { it.code })
        assertFailsWith<ApiFailure> { client.addCashImportFiles("s1", listOf(CashImportUploadInput("a", file)), 0) }
        assertEquals(0, file.reads); assertEquals(0, requests)
        client.close()
    }

    @Test fun alreadyCommittedErrorKeepsSafeResultReferenceWithoutPrivateText() = runTest {
        val client = FinanceApiClient({ "https://api.example.test" }, ContextTokenStore(), MockEngine {
            respond("""{"error":{"code":"session_committed","message":"private financial text"},"receipt_id":"receipt-one"}""", HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val failure = assertFailsWith<ApiFailure> { client.readCashImportSession("s1") }
        assertEquals("session_committed", failure.code)
        assertEquals(409, failure.status)
        assertEquals("receipt-one", failure.receiptId)
        assertFalse("private" in failure.toString())
        client.close()
    }

    @Test fun malformedRecoveryReferencesAreNotRetained() = runTest {
        for (reference in listOf("123", "null", "\"private/path\"", "\"" + "a".repeat(37) + "\"")) {
            val client = FinanceApiClient({ "https://api.example.test" }, ContextTokenStore(), MockEngine {
                respond("{\"error\":{\"code\":\"session_committed\"},\"receipt_id\":" + reference + "}",
                    HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
            })
            val failure = assertFailsWith<ApiFailure> { client.readCashImportSession("s1") }
            assertNull(failure.receiptId)
            client.close()
        }
    }

    @Test fun unreadableFileDoesNotPreventGoodFileBeingUploaded() = runTest {
        var requests = 0
        val client = client { requests++ }
        val unreadable = object : BoundedFileSource {
            override val name = "unreadable.csv"
            override val mediaType: String? = null
            override val size: Long? = null
            override suspend fun read(): ByteArray = error("unbounded read forbidden")
            override suspend fun readBounded(maxBytes: Int): ByteArray = error("private local path")
        }
        val result = client.createCashImportSession(listOf(CashImportUploadInput("bad", unreadable), CashImportUploadInput("good", ContextFile("good.csv", byteArrayOf(1)))))
        assertEquals(listOf(CashImportLocalFileFailure("bad", "import_file_read_failed")), result.localFailures)
        assertFalse("private" in result.localFailures.toString())
        assertEquals(1, requests)
        client.close()
    }

    @Test fun oversizedFileDoesNotPreventGoodFileBeingUploaded() = runTest {
        var requests = 0
        val client = client { requests++ }
        val result = client.createCashImportSession(listOf(
            CashImportUploadInput("bad", ContextFile("huge.csv", byteArrayOf(1), 100L * 1024 * 1024 + 1)),
            CashImportUploadInput("good", ContextFile("good.csv", byteArrayOf(1)))))
        assertEquals(listOf(CashImportLocalFileFailure("bad", "import_file_too_large")), result.localFailures)
        assertEquals(1, requests)
        client.close()
    }

    @Test fun filesAreReadAndSentSequentiallyInsteadOfRetainingEntireBatch() = runTest {
        val events = mutableListOf<String>()
        val client = client { events += "send" }
        client.createCashImportSession(listOf(
            CashImportUploadInput("a", ContextFile("one.csv", byteArrayOf(1), onRead = { events += "read-a" })),
            CashImportUploadInput("b", ContextFile("two.csv", byteArrayOf(2), onRead = { events += "read-b" }))))
        assertEquals(listOf("read-a", "send", "read-b", "send"), events)
        client.close()
    }

    @Test fun requestInterruptionKeepsKnownSessionAndStopsBeforeReadingRemainingFiles() = runTest {
        var count = 0
        val progress = mutableListOf<CashImportSessionDto>()
        val client = FinanceApiClient({ "https://api.example.test" }, ContextTokenStore(), MockEngine {
            count++
            if (count == 1) respond(SESSION, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else respond("{\"error\":{\"code\":\"import_unavailable\",\"message\":\"private\"}}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val third = ContextFile("three.csv", byteArrayOf(3))
        val result = client.createCashImportSession(listOf(
            CashImportUploadInput("a", ContextFile("one.csv", byteArrayOf(1))),
            CashImportUploadInput("b", ContextFile("two.csv", byteArrayOf(2))),
            CashImportUploadInput("c", third)), onSession = { progress += it })
        assertEquals("s1", result.session?.sessionId)
        assertEquals(listOf("b", "c"), result.interruption?.unprocessedFileRefs)
        assertEquals("import_unavailable", result.interruption?.code)
        assertEquals(503, result.interruption?.status)
        assertEquals(1, progress.size)
        assertEquals(0, third.reads)
        assertEquals(2, count)
        client.close()
    }

    @Test fun missingBoundedReaderDoesNotFallBackToAnUnboundedRead() = runTest {
        var reads = 0
        val unknown = object : FileSource {
            override val name = "unknown.csv"
            override val mediaType: String? = null
            override val size: Long? = null
            override suspend fun read(): ByteArray { reads++; return byteArrayOf(1) }
        }
        val client = client { fail("No readable bounded file should be uploaded") }
        val result = client.createCashImportSession(listOf(CashImportUploadInput("unknown", unknown)))
        assertNull(result.session)
        assertEquals("import_file_size_unavailable", result.localFailures.single().code)
        assertEquals(0, reads)
        client.close()
    }

    @Test fun cancelledFileReadPropagatesAndDoesNotUploadOrClaimALocalFailure() = runTest {
        val cancelled = object : BoundedFileSource {
            override val name = "cancelled.csv"
            override val mediaType: String? = null
            override val size: Long? = null
            override suspend fun read(): ByteArray = error("unbounded read forbidden")
            override suspend fun readBounded(maxBytes: Int): ByteArray = throw CancellationException("synthetic cancellation")
        }
        val client = client { fail("Cancelled read must not upload") }
        assertFailsWith<CancellationException> { client.createCashImportSession(listOf(CashImportUploadInput("cancelled", cancelled))) }
        client.close()
    }

    @Test fun boundedSourceReadsOnlyLimitAndOneProbeByteAndAlwaysCloses() {
        val input = Buffer().apply { writeString("abcdefgh") }
        var consumed = 0L
        var closed = false
        val source = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = input.readAtMostTo(sink, byteCount).also { if (it > 0) consumed += it }
            override fun close() { closed = true }
        }
        assertFailsWith<FileReadLimitExceeded> { readBoundedImportSource(source, 4) }
        assertEquals(5L, consumed)
        assertEquals(3L, input.size)
        assertTrue(closed)
        closed = false
        val throwing = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = error("private read failure")
            override fun close() { closed = true }
        }
        assertFailsWith<IllegalStateException> { readBoundedImportSource(throwing, 4) }
        assertTrue(closed)
        assertContentEquals("1234".encodeToByteArray(), readBoundedImportSource(Buffer().apply { writeString("1234") }, 4))
    }

    @Test fun invalidPerFileSettingsDoNotSendARequestThatWouldBlockGoodFiles() = runTest {
        val slots = mutableListOf<String>()
        val client = client { slots += metadata(it.body.toByteArray())["files"]!!.jsonArray.single().jsonObject["client_file_ref"]!!.jsonPrimitive.content }
        val result = client.createCashImportSession(listOf(
            CashImportUploadInput("bad", ContextFile("one.csv", byteArrayOf(1)), sourceHint = "bad/source"),
            CashImportUploadInput("good", ContextFile("two.csv", byteArrayOf(2)))))
        assertEquals(listOf("good"), slots)
        assertEquals(listOf(CashImportLocalFileFailure("bad", "import_payload_invalid")), result.localFailures)
        client.close()
    }

    @Test fun invalidReadLimitStillClosesAnAlreadyOpenedSource() {
        var closed = false
        val source = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = fail("Invalid limit must not read")
            override fun close() { closed = true }
        }
        assertFailsWith<IllegalArgumentException> { readBoundedImportSource(source, -1) }
        assertTrue(closed)
    }

    @Test fun eachAppendUsesLatestSuccessfulSessionRevision() = runTest {
        var revision = 2L
        val sent = mutableListOf<Long>()
        val progress = mutableListOf<Long?>()
        val client = FinanceApiClient({ "https://api.example.test" }, ContextTokenStore(), MockEngine { call ->
            val meta = metadata(call.body.toByteArray())
            meta["expected_session_revision"]?.let { sent += it.jsonPrimitive.long }
            revision++
            respond(SESSION.replace("\"session_revision\":3", "\"session_revision\":" + revision), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client.createCashImportSession((1..3).map {
            CashImportUploadInput("f$it", ContextFile("file$it.csv", byteArrayOf(it.toByte())))
        }, onSession = { progress += it.sessionRevision })
        assertEquals(listOf(3L, 4L), sent)
        assertEquals(listOf<Long?>(3L, 4L, 5L), progress)
        assertEquals(5L, result.session?.sessionRevision)
        client.close()
    }

    private fun client(inspect: suspend (HttpRequestData) -> Unit) = FinanceApiClient({ "https://api.example.test" }, ContextTokenStore(), MockEngine { call ->
        inspect(call)
        respond(SESSION, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private fun metadata(body: ByteArray): JsonObject {
        val part = body.decodeToString().split("\r\n--").first { "name=metadata" in it || "name=\"metadata\"" in it }
        return apiJson.parseToJsonElement(part.substringAfter("\r\n\r\n")).jsonObject
    }
    companion object {
        private val SESSION = """{"session_id":"s1","session_revision":3,"expires_at":"2026-10-23T10:00:00Z","state":"open","files":[
          {"file_ref":"f1","client_file_ref":"a","display_name":"one.csv","source_type":"wechat","status":"ready","recognized_row_count":1,"issues":[],"can_retry":false},
          {"file_ref":"f2","client_file_ref":"b","display_name":"two.pdf","source_type":null,"status":"password_required","recognized_row_count":null,"issues":[{"code":"statement_password_required","message":"请补充密码","field_path":null,"blocking_scope":"file","next_action":"reselect_file"}],"can_retry":true}],
          "source_accounts":[{"source_account_ref":"source-one","source_type":"wechat","session_group_ref":"account-group-one","session_label":"昵称甲","display_name":"微信零钱","currencies":["CNY"],"row_count":1,"suggestion":{"account_id":null,"expected_account_revision":null,"basis":"none"},"selection":{"account_id":"102","expected_account_revision":5}}],
          "last_preview_input":{"account_selections":[],"row_choices":[{"row_ref":"f1:r1","action":"apply","patch":{"note":"人工备注"}}],"relation_choices":[]},"last_plan_id":"ip_latest","can_preview":true,"receipt_id":null}""".trimIndent()
    }
}
private class ContextTokenStore : TokenStore {
    override suspend fun get(): String? = "synthetic-token"
    override suspend fun set(value: String) = Unit
    override suspend fun clear() = Unit
}
private class ContextFile(override val name: String, private val bytes: ByteArray, private val reportedSize: Long? = bytes.size.toLong(), private val onRead: () -> Unit = {}, override val lastModifiedAtMillis: Long? = null) : BoundedFileSource {
    override val mediaType: String? = null
    override val size: Long? get() = reportedSize
    var reads = 0
    override suspend fun read(): ByteArray = error("unbounded read forbidden")
    override suspend fun readBounded(maxBytes: Int): ByteArray {
        onRead(); reads++
        if (bytes.size > maxBytes) throw FileReadLimitExceeded()
        return bytes
    }
}
