package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import java.io.IOException
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import java.util.concurrent.CancellationException

class CashMultipartOriginalTest {
    private fun part(name: String, content: ByteArray, filename: String? = null, ended: Boolean = true): ByteArray =
        ("--synthetic\r\nContent-Disposition: form-data; name=\"$name\"" +
            (filename?.let { "; filename=\"$it\"" } ?: "") + "\r\n\r\n").encodeToByteArray() + content +
            if (ended) "\r\n".encodeToByteArray() else byteArrayOf()
    private fun prefix() = part("metadata", "{\"files\": [{\"client_file_ref\": \"a\"}]}".encodeToByteArray())
    private fun file(ended: Boolean = true) = part("file:a", "one".encodeToByteArray(), "synthetic.csv", ended)
    private val end = "--synthetic--\r\n".encodeToByteArray()
    private class Disconnect : IOException("synthetic disconnect")

    private fun read(chunks: List<ByteArray>, failure: Throwable? = null,
        limit: Long = CashImportProtocol.MAX_REQUEST_BYTES, contentType: String = "multipart/form-data; boundary=synthetic",
        fileLimit: Int = CashImportProtocol.MAX_FILE_BYTES, expectFile: Boolean = true): Result<List<CashImportUpload>> = runBlocking {
        withTimeout(5000) {
            val input = ByteChannel(autoFlush = true)
            lateinit var resources: CashMultipartResources
            val reader = CashMultipartReader(limit, fileLimit) { resources = it }
            val writer = launch {
                try {
                    for ((index, chunk) in chunks.withIndex()) {
                        input.writeFully(chunk); input.flush()
                        if ((chunks.size > 1 && index == 0 && limit < 1000) || failure != null) {
                            while (resources.sinks.none { it.file }) yield()
                        }
                    }
                    input.close(failure)
                } catch (_: ClosedWriteChannelException) { }
            }
            val result = runCatching { CashImportProtocol.uploads(reader.read(input, contentType, null), false).second }
            try {
                assertTrue(resources.sinks.isNotEmpty(), "The real reader must allocate a sink")
                if (expectFile) assertTrue(resources.sinks.any { it.file }, "The original scenario must begin a real file body")
                assertTrue(resources.sinks.all { it.closed }, "Every allocated sink must be closed, including incomplete parts")
                assertTrue(resources.input.isClosedForRead, "original channel closed"); assertTrue(resources.forwarded.isClosedForRead, "forwarding channel closed")
                assertTrue(resources.pump.isCompleted)
            } finally { writer.cancelAndJoin() }
            result
        }
    }
    @Test fun truncatedMultipartRejectsBothOriginalParameters() {
        for (extra in listOf(false, true)) {
            val body = prefix() + file(extra) + if (extra) part("file:undeclared", "unfinished".encodeToByteArray(), "unfinished.csv", false) else byteArrayOf()
            assertIs<CashImportProtocolError>(read(listOf(body)).exceptionOrNull())
        }
    }
    @Test fun actualChunkedRequestLimitCountsAllBytes() {
        val first = prefix() + file(false)
        assertEquals(194, first.size)
        val error = assertIs<CashImportProtocolError>(read(listOf(first, ByteArray(50) { 'x'.code.toByte() }), limit = first.size + 8L).exceptionOrNull())
        assertEquals("upload_too_large", error.code)
    }
    @Test fun headerLimitAfterFirstFileRejects() {
        val body = prefix() + file() + part("file:b", "two".encodeToByteArray(), "x".repeat(17000)) + end
        assertIs<CashImportProtocolError>(read(listOf(body)).exceptionOrNull())
    }
    @Test fun transportInterruptionPreservesBothOriginalTypes() {
        val body = prefix() + part("file:a", "unfinished".encodeToByteArray(), "synthetic.csv", false)
        for (error in listOf(Disconnect(), CancellationException("synthetic cancellation"))) {
            val actual = read(listOf(body), error).exceptionOrNull()
            assertEquals(error.javaClass.name, actual?.javaClass?.name)
        }
    }
    @Test fun normalStreamReturnsOriginalBytes() {
        assertContentEquals("one".encodeToByteArray(), read(listOf(prefix() + file() + end)).getOrThrow().single().content)
    }
    @Test fun countAndTextLimitsRejectBothOriginalParameters() {
        val files = prefix() + (0..20).fold(byteArrayOf()) { bytes, index -> bytes + part("file:$index", "one".encodeToByteArray(), "synthetic.csv") }
        val metadata = file() + part("metadata", (" ".repeat(65537) + "{}").encodeToByteArray())
        for (body in listOf(files, metadata)) assertIs<CashImportProtocolError>(read(listOf(body + end)).exceptionOrNull())
    }
    @Test fun displayFilenameScrubsPasswordAndFullAccount() {
        val value = CashImportContext.displayFilename("银行_1234567890123456_密码=synthetic-secret.pdf", "synthetic-secret")
        assertFalse("1234567890123456" in value); assertFalse("synthetic-secret" in value)
        assertTrue("3456" in value); assertTrue(value.endsWith(".pdf"))
    }
    @Test fun lastFilenameSegmentCannotBypassRedaction() {
        for ((filename, secret) in listOf("银行账单.1234567890123456" to "1234567890123456", "银行账单.password=synthetic-secret" to "synthetic-secret")) {
            assertFalse(secret in CashImportContext.displayFilename(filename, null))
        }
    }
    @Test fun everyByteBoundaryAndFalseDelimiterPreserveBody() {
        val content = "one\r\n--syntheticX\r\n--synthe\r\nsecond\r\n".encodeToByteArray()
        val body = prefix() + part("file:a", content, "synthetic.csv") + end
        val result = read(body.map { byteArrayOf(it) }).getOrThrow().single()
        assertContentEquals(content, result.content)
    }
    @Test fun quotedBoundaryAndFilenameAndReversePartsRemainValid() {
        val body = part("file:a", byteArrayOf(), "银行;账单.csv") + prefix() + end
        val upload = read(listOf(body), contentType = "multipart/form-data; boundary=\"synthetic\"").getOrThrow().single()
        assertEquals("银行;账单.csv", upload.filename); assertContentEquals(byteArrayOf(), upload.content)
    }
    @Test fun originalCharsetFallbackAndLeadingNewlinesRemainAccepted() {
        val metadata = "{\"files\":[{\"client_file_ref\":\"a\",\"password\":\"café\"}]}".toByteArray(Charsets.ISO_8859_1)
        val file = "--synthetic\r\nContent-Disposition: form-data; name=\"file:a\"; filename=\"café.csv\"\r\n\r\none\r\n".toByteArray(Charsets.ISO_8859_1)
        for (charset in listOf("iso-8859-1", "UTF-8", "unknown-codec")) {
            val body = "\r\n".encodeToByteArray() + part("metadata", metadata) + file + end
            val upload = read(listOf(body), contentType = "multipart/form-data; boundary=synthetic; charset=$charset", expectFile = false).getOrThrow().single()
            assertEquals("café", upload.password); assertEquals("café.csv", upload.filename)
            assertContentEquals("one".encodeToByteArray(), upload.content)
        }
    }
    @Test fun partContentLengthCannotHideTruncatedOrAlterNormalBody() {
        val file = "--synthetic\r\nContent-Disposition: form-data; name=\"file:a\"; filename=\"x.csv\"\r\nContent-Length: 1\r\n\r\none".encodeToByteArray()
        assertContentEquals("one".encodeToByteArray(), read(listOf(prefix() + file + "\r\n".encodeToByteArray() + end)).getOrThrow().single().content)
        assertIs<CashImportProtocolError>(read(listOf(prefix() + file + "\r\n--synthetic-".encodeToByteArray())).exceptionOrNull())
    }
    @Test fun singleFileRetainsLimitPlusOneForApplicationRejection() {
        val content = ByteArray(40000) { (it % 256).toByte() }
        val result = read(listOf(prefix() + part("file:a", content, "x.csv") + end), fileLimit = 8).getOrThrow().single()
        assertContentEquals(content.copyOf(9), result.content)
    }
    @Test fun malformedHeaderAndInvalidMetadataUtf8AreProtocolErrors() {
        val malformed = prefix() + "--synthetic\r\nInvalid Header\r\n\r\none\r\n".encodeToByteArray() + end
        val invalidUtf8 = file() + part("metadata", byteArrayOf(0xc3.toByte(), 0x28)) + end
        assertIs<CashImportProtocolError>(read(listOf(malformed), expectFile = false).exceptionOrNull())
        assertIs<CashImportProtocolError>(read(listOf(invalidUtf8)).exceptionOrNull())
    }
    @Test fun originalDependencyHeaderCountAndLineLimitsReject() {
        val disposition = "Content-Disposition: form-data; name=\"file:b\"; filename=\"x.csv\""
        val extra = (1..8).joinToString("\r\n") { "X-$it: value" }
        val long = "X-Long: " + "x".repeat(4225)
        for (headers in listOf("$disposition\r\n$extra", "$disposition\r\n$long")) {
            val body = part("metadata", "{\"files\":[{\"client_file_ref\":\"a\"},{\"client_file_ref\":\"b\"}]}".encodeToByteArray()) + file().let { first ->
                first + "--synthetic\r\n$headers\r\n\r\ntwo\r\n".encodeToByteArray()
            } + end
            assertIs<CashImportProtocolError>(read(listOf(body)).exceptionOrNull())
        }
    }
    @Test fun lastContentDispositionHeaderDefinesOriginalPart() {
        val bad = "Content-Disposition: form-data; name=\"bad\""
        val good = "Content-Disposition: form-data; name=\"file:a\"; filename=\"x.csv\""
        fun body(headers: String) = prefix() + "--synthetic\r\n$headers\r\n\r\none\r\n".encodeToByteArray() + end
        assertContentEquals("one".encodeToByteArray(), read(listOf(body("$bad\r\n$good")), expectFile = false).getOrThrow().single().content)
        assertIs<CashImportProtocolError>(read(listOf(body("$good\r\n$bad")), expectFile = false).exceptionOrNull())
    }
    @Test fun realHttpUsesSameReaderAndReleasesResources() = testApplication {
        val states = mutableListOf<CashMultipartResources>()
        application {
            routing { post("/upload") {
                call.attributes.put(cashMultipartObserverKey) { states += it }
                try {
                    val uploads = CashImportProtocol.receive(call, false).second
                    call.respondText(uploads.single().content.decodeToString())
                } catch (error: CashImportProtocolError) { call.respond(HttpStatusCode.UnprocessableEntity, error.code) }
            } }
        }
        for ((body, expected) in listOf((prefix() + file() + end) to 200, (prefix() + file(false)) to 422)) {
            val response = client.post("/upload") { header(HttpHeaders.ContentType, "multipart/form-data; boundary=synthetic"); setBody(body) }
            assertEquals(expected, response.status.value)
        }
        assertEquals(2, states.size)
        assertTrue(states.all { state -> state.sinks.any { it.file } && state.sinks.all { it.closed } &&
            state.input.isClosedForRead && state.forwarded.isClosedForRead && state.pump.isCompleted })
    }
    @Test fun originalMultipartPreflightAllowsDynamicLocalOrigin() = testApplication {
        application {
            val database = Database("jdbc:sqlite:" + Path.of(System.getProperty("user.home"), ".cache", "finance-tracker", "kotlin-migration", "multipart-unused.db")) {
                error("CORS must not open a database")
            }
            financeModule(database, allowedOrigin = "http://127.0.0.1:4173")
        }
        val response = client.options("/api/v1/cash-import/sessions") {
            header(HttpHeaders.Origin, "http://127.0.0.1:5181")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "content-type")
        }
        assertEquals(200, response.status.value)
        assertEquals("http://127.0.0.1:5181", response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertTrue("content-type" in response.headers[HttpHeaders.AccessControlAllowHeaders]!!.lowercase())
    }
}
