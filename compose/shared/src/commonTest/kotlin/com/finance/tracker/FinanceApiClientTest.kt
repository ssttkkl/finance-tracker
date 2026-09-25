package com.finance.tracker

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class FinanceApiClientTest {
    @Test
    fun loginUsesExistingEndpointStoresNewTokenAndReturnsSession() = runTest {
        val store = MemoryTokenStore("old-token")
        var request: HttpRequestData? = null
        val engine = MockEngine { call ->
            request = call
            respondJson("""
                {"access_token":"new-token","user":{"email":"owner@example.com"},"active_workspace_id":"w-1","workspaces":[{"id":"w-1","name":"账本","role":"admin"}]}
            """.trimIndent())
        }
        val client = FinanceApiClient({ "https://api.example.com" }, store, engine)

        val session = client.login("owner@example.com", "a-very-long-password")

        assertEquals("/api/v1/auth/login", request?.url?.encodedPath)
        assertEquals("Bearer old-token", request?.headers?.get(HttpHeaders.Authorization))
        assertEquals("new-token", store.value)
        assertEquals("w-1", session.activeWorkspaceId)
        assertEquals(Role.ADMIN, session.workspaces.single().role)
        client.close()
    }

    @Test
    fun workspaceAndInvitationRequestsPreserveExistingJsonAndPathContracts() = runTest {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val engine = MockEngine { call ->
            paths += call.url.encodedPath
            bodies += (call.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            when {
                call.url.encodedPath.endsWith("/select") -> respondJson("""{"user":{"email":"owner@example.com"},"active_workspace_id":"team one","workspaces":[]}""")
                call.url.encodedPath.endsWith("/invitations/token%2Fone") -> respondJson("""{"workspace":{"name":"共享账本"},"role":"editor","valid":true}""")
                else -> error("Unexpected API request")
            }
        }
        val client = FinanceApiClient({ "https://api.example.com" }, MemoryTokenStore("token"), engine)

        client.selectWorkspace("team one")
        val preview = client.invitationPreview("token/one")

        assertTrue(paths[0].endsWith("/workspaces/team%20one/select"))
        assertTrue(paths[1].endsWith("/invitations/token%2Fone"))
        assertEquals("null", bodies[0])
        assertEquals("共享账本", preview.workspace.name)
        client.close()
    }

    @Test
    fun investmentPortfolioEventDecoderAcceptsOnlyCompletePortfolioEvents() {
        val streamData = """{"version":2,"portfolio":{"accounts":[{"name":"Broker","currency":"USD","positions":[]}],"total_market_value":"125.50"}}"""
        val portfolio = decodeInvestmentPortfolioEvent("portfolio", streamData)

        assertEquals("125.50", portfolio?.totalMarketValue)
        assertEquals("Broker", portfolio?.accounts?.single()?.name)
        assertNull(decodeInvestmentPortfolioEvent("refresh_error", streamData))
        assertNull(decodeInvestmentPortfolioEvent("portfolio", "not-json"))
    }

    @Test
    fun apiErrorsKeepOnlyServerCodeAndStatus() = runTest {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel("""{"error":{"code":"workspace_forbidden","message":"private backend text"}}"""),
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = FinanceApiClient({ "https://api.example.com" }, MemoryTokenStore(), engine)

        val failure = assertFailsWith<ApiFailure> { client.session() }

        assertEquals("workspace_forbidden", failure.code)
        assertEquals(403, failure.status)
        assertEquals("workspace_forbidden", failure.message)
        client.close()
    }

    @Test
    fun logoutClearsTokenEvenWhenTheServerRejectsTheRequest() = runTest {
        val store = MemoryTokenStore("token")
        val engine = MockEngine {
            respond(
                content = ByteReadChannel("{}"),
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = FinanceApiClient({ "https://api.example.com" }, store, engine)

        assertFailsWith<ApiFailure> { client.logout() }
        assertNull(store.value)
        client.close()
    }

    @Test
    fun unauthorizedSessionClearsAnExpiredToken() = runTest {
        val store = MemoryTokenStore("expired-token")
        val engine = MockEngine {
            respond(
                content = ByteReadChannel("not-json"),
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val client = FinanceApiClient({ "https://api.example.com" }, store, engine)

        assertFailsWith<ApiFailure> { client.restoreSession(retryDelayMillis = 0) }

        assertNull(store.value)
        client.close()
    }

    @Test
    fun sessionRestoreRetriesTemporaryFailuresAndStopsAfterSuccess() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            if (requests < 3) {
                respond(
                    content = ByteReadChannel("{}"),
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respondJson("""{"user":{"email":"owner@example.com"},"active_workspace_id":null,"workspaces":[]}""")
            }
        }
        val client = FinanceApiClient({ "https://api.example.com" }, MemoryTokenStore("saved-token"), engine)

        val session = client.restoreSession(retryDelayMillis = 0)

        assertEquals("owner@example.com", session.user.email)
        assertEquals(3, requests)
        client.close()
    }

    @Test
    fun sessionRestoreDoesNotRetryAuthenticationFailures() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(
                content = ByteReadChannel("{}"),
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = FinanceApiClient({ "https://api.example.com" }, MemoryTokenStore("expired"), engine)

        assertFailsWith<ApiFailure> { client.restoreSession(retryDelayMillis = 0) }

        assertEquals(1, requests)
        client.close()
    }

    @Test
    fun unavailableTokenStoreFailsClosedBeforeSendingRequests() = runTest {
        var requests = 0
        val store = MemoryTokenStore().apply { failReads = true }
        val engine = MockEngine {
            requests++
            respondJson("{}")
        }
        val client = FinanceApiClient({ "https://api.example.com" }, store, engine)

        val failure = assertFailsWith<ApiFailure> { client.session() }

        assertEquals("token_store_unavailable", failure.code)
        assertEquals(0, requests)
        client.close()
    }

    @Test
    fun cashImportKeepsExistingBatchJsonAndIdempotentConfirmationContracts() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { call ->
            requests += call
            when (call.url.encodedPath) {
                "/api/v1/cash-import/scan" -> respondJson("""
                    {"import_token":"import-1","contract":"cash-import-v1","channel":"bank","channel_label":"银行","ready":true,
                    "file":{"name":"statement.csv","digest":"sha1"},"digest":"sha1","groups":[],"accounts":[],"files":[]}
                """.trimIndent())
                "/api/v1/cash-import/preview" -> respondJson("""
                    {"import_token":"import-1","channel":"bank","channel_label":"银行","file":{"name":"statement.csv","digest":"sha1"},
                    "summary":{"total":1,"new":1,"existing":0,"unsupported":0},"items":[],"relations":[]}
                """.trimIndent())
                "/api/v1/cash-import/commit" -> respondJson("""
                    {"message":"ok","new_rows":1,"updated_rows":0,"channel":"bank","digest":"sha1"}
                """.trimIndent())
                else -> error("Unexpected path: ${call.url.encodedPath}")
            }
        }
        val client = FinanceApiClient({ "https://api.example.com" }, MemoryTokenStore("token"), engine)

        val scan = client.scanCashImport(listOf(MemoryFileSource("statement.csv", byteArrayOf(97))), passwords = mapOf("0" to "secret"))
        val preview = client.previewCashImport(scan.importToken!!, passwords = mapOf("0" to "secret"), batch = true)
        val committed = client.commitCashImport(
            importToken = preview.importToken!!,
            previewDigest = preview.file.digest,
            previewRelationDigest = "relations-v1",
            previewChannel = preview.channel,
            idempotencyKey = "cash-import-stable-key",
            batch = true,
        )

        val scanBody = (requests[0].body as io.ktor.http.content.TextContent).text
        assertTrue(scanBody.contains("statement.csv"))
        assertTrue(scanBody.contains("YQ=="))
        assertEquals("{\"0\":\"secret\"}", requests[0].headers["X-FT-Statement-Passwords"])
        assertTrue((requests[1].body as io.ktor.http.content.TextContent).text.contains("\"batch\":true"))
        assertEquals("cash-import-stable-key", requests[2].headers["Idempotency-Key"])
        assertTrue((requests[2].body as io.ktor.http.content.TextContent).text.contains("relations-v1"))
        assertEquals(1, committed.newRows)
        client.close()
    }

    @Test
    fun importFailuresRetainOnlySafeCodeStatusAndRetryToken() = runTest {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel("""{"error":{"code":"import_password_required"},"import_token":"retry-token"}"""),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = FinanceApiClient({ "https://api.example.com" }, MemoryTokenStore("token"), engine)

        val failure = assertFailsWith<ApiFailure> {
            client.scanCashImport(listOf(MemoryFileSource("statement.pdf", byteArrayOf(1))))
        }

        assertEquals("import_password_required", failure.code)
        assertEquals(400, failure.status)
        assertEquals("retry-token", failure.importToken)
        client.close()
    }

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}

private class MemoryFileSource(override val name: String, private val content: ByteArray) : FileSource {
    override val mediaType: String? = null
    override val size: Long = content.size.toLong()
    override suspend fun read(): ByteArray = content
}

private class MemoryTokenStore(initial: String? = null) : TokenStore {
    var value: String? = initial
        private set
    var failReads = false

    override suspend fun get(): String? {
        if (failReads) error("storage access details")
        return value
    }
    override suspend fun set(value: String) { this.value = value }
    override suspend fun clear() { value = null }
}
