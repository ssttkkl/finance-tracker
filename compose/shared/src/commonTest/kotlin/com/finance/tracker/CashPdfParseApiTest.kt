package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CashPdfParseApiTest {
    private class Pdf(override val name:String="synthetic.pdf",override val size:Long?=null,
        private val onRead:()->ByteArray={ byteArrayOf(0,1,127,-1) }):BoundedFileSource {
        override val mediaType:String?=null
        override suspend fun read():ByteArray=error("unbounded read")
        override suspend fun readBounded(maxBytes:Int):ByteArray { assertEquals(100*1024*1024,maxBytes); return onRead() }
    }
    private fun api(handler:suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData)->io.ktor.client.request.HttpResponseData)=
        FinanceApiClient({"https://api.example.test"},object:TokenStore {
            override suspend fun get()="synthetic-token"
            override suspend fun set(value:String)=Unit
            override suspend fun clear()=Unit
        },MockEngine(handler))
    private val wire=CashPdfParseResultDto("cash-rules-v1",NormalizedCashDocumentDto("icbc","icbc_pdf","v1",emptyList(),null,null,
        CashSourceCoverage(null,null,false,"",emptyMap(),"v1"),null,0,null))

    @Test fun onePdfUsesOnlyStatelessEndpointAndKeepsPasswordInMultipart()=runTest {
        var requests=0
        val client=api { request ->
            requests++
            assertEquals("/api/v1/cash-import/pdf/parse",request.url.encodedPath)
            assertEquals("",request.url.encodedQuery)
            assertEquals("Bearer synthetic-token",request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["X-FT-Statement-Password"])
            val body=request.body.toByteArray()
            assertTrue(body.decodeToString().contains("synthetic-secret"))
            assertTrue(body.decodeToString().contains("file:pdf-a"))
            assertTrue(body.toList().windowed(4).any { it==listOf<Byte>(0,1,127,-1) })
            respond(Json.encodeToString(wire),headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        try {
            assertEquals(wire,client.parseCashPdf(CashImportUploadInput("pdf-a",Pdf(),"synthetic-secret","icbc")))
            assertEquals(1,requests)
        } finally { client.close() }
    }

    @Test fun invalidTypeMetadataAndOversizeNeverReadOrSend()=runTest {
        var reads=0
        val client=api { error("must not send") }
        try {
            for ((input,code) in listOf(
                CashImportUploadInput("bad?",Pdf(onRead={reads++;byteArrayOf()})) to "import_payload_invalid",
                CashImportUploadInput("a",Pdf("synthetic.csv",onRead={reads++;byteArrayOf()})) to "import_file_unsupported_type",
                CashImportUploadInput("a",Pdf(size=100L*1024*1024+1,onRead={reads++;byteArrayOf()})) to "import_file_too_large",
                CashImportUploadInput("a",Pdf(),password="x".repeat(1025)) to "import_payload_invalid"))
                assertEquals(code,assertFailsWith<ApiFailure> { client.parseCashPdf(input) }.code)
            assertEquals(0,reads)
            assertEquals("import_file_too_large",assertFailsWith<ApiFailure> {
                client.parseCashPdf(CashImportUploadInput("a",Pdf { throw FileReadLimitExceeded() }))
            }.code)
            assertFailsWith<CancellationException> { client.parseCashPdf(CashImportUploadInput("a",Pdf { throw CancellationException("cancel") })) }
            assertFailsWith<IllegalStateException> { client.parseCashPdf(CashImportUploadInput("a",Pdf { error("synthetic bug") })) }
        } finally {client.close()}
    }

    @Test fun parserRejectionAndTemporaryFailureRemainAccurateApiErrors()=runTest {
        for ((code,status) in listOf("password_incorrect" to HttpStatusCode.UnprocessableEntity,"import_unavailable" to HttpStatusCode.ServiceUnavailable)) {
            val client=api { respond("""{"error":{"code":"$code","message":"synthetic","field_errors":[],"retryable":${status.value==503}}}""",status,
                headers=headersOf(HttpHeaders.ContentType,"application/json")) }
            try {
                val failure=assertFailsWith<ApiFailure> { client.parseCashPdf(CashImportUploadInput("a",Pdf())) }
                assertEquals(code,failure.code);assertEquals(status.value,failure.status)
            } finally {client.close()}
        }
    }
    @Test fun lateUnauthorizedParseCannotClearCredentialsFromANewerLogin()=runTest {
        var token:String?="old-login"
        val store=object:TokenStore {
            override suspend fun get()=token
            override suspend fun set(value:String) {token=value}
            override suspend fun clear() {token=null}
        }
        val client=FinanceApiClient({"https://api.example.test"},store,MockEngine { request ->
            assertEquals("Bearer old-login",request.headers[HttpHeaders.Authorization])
            store.set("new-login")
            respond("""{"error":{"code":"authentication_required"}}""",HttpStatusCode.Unauthorized,
                headers=headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            assertEquals(401,assertFailsWith<ApiFailure> {client.parseCashPdf(CashImportUploadInput("a",Pdf()))}.status)
            assertEquals("new-login",store.get())
        } finally {client.close()}
    }

}
