package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.*
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class CashPlanJsonInputOriginalTest {
    @Test fun actualStreamBudgetRejectsOverflowDespiteFalseLengthBeforeValidation() = testApplication {
        var validations = 0
        application { routing { post("/bounded-json") {
            assertEquals("1", call.request.headers[HttpHeaders.ContentLength])
            try {
                CashImportProtocol.jsonInput(call, 40) { validations++; CashImportProtocol.commitInput(it) }
                call.respondText("accepted")
            } catch (_: CashImportProtocolError) { call.respondText("rejected", status = HttpStatusCode.UnprocessableEntity) }
        } } }
        val response = client.post("/bounded-json") {
            setBody(object : OutgoingContent.ByteArrayContent() {
                override val contentLength = 1L
                override val contentType = ContentType.Application.Json
                override fun bytes() = ("""{"expected_session_revision":1}""" + " ".repeat(50)).encodeToByteArray()
            })
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText()); assertEquals(0, validations)
    }
    @Test fun nestedNonfinitePatchNumbersRemainProtocolErrors() {
        for (value in listOf("NaN", "Infinity", "1e999", "-1e999")) {
            val raw = """{"expected_session_revision":1,"previous_plan_id":null,"account_selections":[],"relation_choices":[],"row_choices":[{"row_ref":"file_a:r1","action":"apply","patch":{"source_payload":{"value":$value}}}]}"""
            assertFailsWith<CashImportProtocolError>(value) { CashImportProtocol.preview(raw) }
        }
    }
    @Test fun validationAllowsOtherRequestsOnSameRequestDispatcher() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { requestDispatcher ->
            withTimeout(5000) { withContext(requestDispatcher) {
                val caller = Thread.currentThread().id
                val entered = CompletableDeferred<Unit>(); val released = CountDownLatch(1)
                var validator: Long? = null
                try {
                    val parsed = async {
                        CashImportProtocol.jsonInput(ByteReadChannel("{}".encodeToByteArray())) {
                            validator = Thread.currentThread().id; entered.complete(Unit); released.await(500, TimeUnit.MILLISECONDS)
                        }
                    }
                    val other = async { entered.await(); released.countDown() }
                    assertTrue(parsed.await()); other.await(); assertNotEquals(caller, validator)
                } finally { released.countDown() }
            } }
        }
    }
    @Test fun rawJsonEncodingAndNonAsciiValuesUseOriginalByteDecoder() = runBlocking {
        val raw = """{"expected_session_revision":1,"previous_plan_id":null,"account_selections":[],"relation_choices":[],"row_choices":[{"row_ref":"file_a:r1","action":"apply","patch":{"note":"账单原文"}}]}"""
        val expected = CashImportProtocol.preview(raw)
        val encodings = listOf("UTF-8" to byteArrayOf(), "UTF-8" to byteArrayOf(-17, -69, -65),
            "UTF-16LE" to byteArrayOf(), "UTF-16BE" to byteArrayOf(-2, -1),
            "UTF-32LE" to byteArrayOf(), "UTF-32BE" to byteArrayOf(0, 0, -2, -1))
        for ((charset, bom) in encodings) {
            val bytes = bom + raw.toByteArray(Charset.forName(charset))
            assertEquals(expected, CashImportProtocol.jsonInput(ByteReadChannel(bytes)) { CashImportProtocol.preview(it) }, charset)
        }
    }
    @Test fun mutationCommitBodyKeepsOnlyStrictPositiveRevision() {
        for (revision in listOf("1", Long.MAX_VALUE.toString())) assertEquals(revision.toLong(),
            CashImportProtocol.mutationCommitInput("""{"expected_plan_revision":$revision}""".encodeToByteArray()))
        for (raw in listOf("{}", "[]", """{"expected_plan_revision":1,"unknown":true}""",
            """{"expected_plan_revision":1,"expected_plan_revision":2}""")) assertFailsWith<CashImportProtocolError>(raw) {
            CashImportProtocol.mutationCommitInput(raw.encodeToByteArray())
        }
        for (revision in listOf("\"1\"", "true", "null", "0", "-1", "1.0", "9223372036854775808")) assertFailsWith<CashImportProtocolError>(revision) {
            CashImportProtocol.mutationCommitInput("""{"expected_plan_revision":$revision}""".encodeToByteArray())
        }
    }
}
