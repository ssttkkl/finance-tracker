package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

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

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
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
