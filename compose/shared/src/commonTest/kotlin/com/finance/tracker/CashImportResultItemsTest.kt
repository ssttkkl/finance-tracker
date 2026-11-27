package com.finance.tracker

import com.finance.tracker.business.contracts.CashMutationResultDto
import com.finance.tracker.business.contracts.CashMutationResultPageDto
import com.finance.tracker.business.contracts.CashMutationProjectionRefDto
import com.finance.tracker.business.contracts.CashWriteSetTransactionResultDto
import com.finance.tracker.business.contracts.CashWriteSetIdentityDto
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.*

internal fun localCashImportServerResultItemsFixture() = listOf(
    CashMutationResultDto("1", "row", "row:0", "created", transactionId = "9007199254740993"))

class CashImportResultItemsTest {
    private val credentials = ApiCredentialSnapshot("original-token", "https://original.test")
    private val headers get() = headersOf(HttpHeaders.ContentType, "application/json")
    private class Store : TokenStore {
        var reads = 0; var clears = 0
        override suspend fun get(): String { reads++; return "later-token" }
        override suspend fun set(value: String) = Unit
        override suspend fun clear() { clears++ }
    }
    private fun page(items: List<CashMutationResultDto>, next: String? = null) =
        Json.encodeToString(CashMutationResultPageDto(items, next,
            listOf(CashMutationProjectionRefDto("999", "current-projection-must-not-be-retained")), 900))

    @Test fun allPagesKeepOriginalCredentialsAndExactIdentitiesWithoutRetainingCurrentProjections() = runTest {
        val original = localCashWriteSetReceiptFixture()
        val receipt = original.copy(receiptId = "receipt/+?&", writeSetResult = original.writeSetResult!!.copy(
            transactions = (0..200).map { CashWriteSetTransactionResultDto((9007199254740993L + it).toString(), "2", emptyList()) },
            relations = listOf(CashWriteSetIdentityDto("42", "1"))))
        val rows = receipt.writeSetResult!!.transactions.mapIndexed { index, identity ->
            CashMutationResultDto((9007199254740993L + index).toString(), "row", "row:$index", "unchanged", transactionId = identity.id)
        }
        val edge = CashMutationResultDto("9007199254741993", "relation", "relation:0", "duplicate", relationId = "42")
        val store = Store(); val cursors = mutableListOf<String>(); var checks = 0
        val api = FinanceApiClient({ "invalid later origin" }, store, cashImportMockEngine { request ->
            assertEquals(HttpMethod.Get, request.method); assertEquals("original.test", request.url.host)
            assertEquals("Bearer original-token", request.headers[HttpHeaders.Authorization])
            assertEquals(listOf("工作区/+?&"), request.url.parameters.getAll("workspace_id"))
            assertEquals("200", request.url.parameters["limit"])
            assertTrue(request.url.encodedPath.contains("receipt%2F%2B%3F%26"), request.url.encodedPath)
            val after = request.url.parameters["after"]!!; cursors += after
            respond(if (request.url.encodedPath.endsWith("relations")) page(listOf(edge))
                else if (after == "0") page(rows.take(200), rows[199].resultId) else page(rows.drop(200)), headers = headers)
        })
        try {
            val items = api.readCashImportResultItems("工作区/+?&", receipt, credentials) { checks++ }
            assertEquals(rows + edge, items); assertEquals(listOf("0", rows[199].resultId, "0"), cursors)
            assertEquals(6, checks); assertEquals(0, store.reads); assertEquals(0, store.clears)
        } finally { api.close() }
    }

    @Test fun missingDuplicateWrongIdentityRolePositionOrOutcomeCannotBecomeACompleteArchive() = runTest {
        val row = localCashImportServerResultItemsFixture().single()
        val cases = listOf(
            emptyList(), listOf(row, row.copy(resultId = "2")), listOf(row.copy(transactionId = "42")),
            listOf(row.copy(resultKind = "relation")), listOf(row.copy(itemRef = "row:1")), listOf(row.copy(itemRef = "row:00")),
            listOf(row.copy(outcome = "confirmed")), listOf(row.copy(resultId = "0")), listOf(row.copy(resultId = "01")),
            listOf(row.copy(relationId = "42")), listOf(row.copy(accountId = "10")),
        )
        for (items in cases) {
            val api = FinanceApiClient({ "invalid" }, Store(), cashImportMockEngine { respond(page(items), headers = headers) })
            try { assertFailsWith<Exception> { api.readCashImportResultItems("workspace", localCashWriteSetReceiptFixture(), credentials) {} } }
            finally { api.close() }
        }
    }

    @Test fun invalidCursorsAndNonAdvancingResultIdsStopWithoutAnotherRequest() = runTest {
        val row = localCashImportServerResultItemsFixture().single()
        for ((items, cursor) in listOf(emptyList<CashMutationResultDto>() to "1", listOf(row) to "2", listOf(row) to "1", listOf(row) to "0", listOf(row) to "01")) {
            var requests = 0
            val api = FinanceApiClient({ "invalid" }, Store(), cashImportMockEngine { requests++; respond(page(items, cursor), headers = headers) })
            try {
                assertFailsWith<Exception> { api.readCashImportResultItems("workspace", localCashWriteSetReceiptFixture(), credentials) {} }
                assertEquals(1, requests)
            } finally { api.close() }
        }
    }

    @Test fun transportFailureAndCancellationKeepTheOriginalFailureAndDoNotRetry() = runTest {
        for (fault in listOf(IOException("synthetic disconnected result read"), CancellationException("synthetic cancel"))) {
            var requests = 0; val store = Store()
            val api = FinanceApiClient({ "invalid" }, store, cashImportMockEngine { requests++; throw fault })
            try {
                val thrown = assertFailsWith<Exception> { api.readCashImportResultItems("workspace", localCashWriteSetReceiptFixture(), credentials) {} }
                assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === fault })
                assertEquals(1, requests); assertEquals(0, store.clears)
            } finally { api.close() }
        }
        var requests = 0
        val api = FinanceApiClient({ "invalid" }, Store(), cashImportMockEngine { requests++; respond(page(localCashImportServerResultItemsFixture()), headers = headers) })
        try {
            var checks = 0
            assertFailsWith<CancellationException> {
                api.readCashImportResultItems("workspace", localCashWriteSetReceiptFixture(), credentials) { if (++checks == 2) throw CancellationException("owner changed") }
            }
            assertEquals(1, requests)
        } finally { api.close() }
    }

    @Test fun genuineUnauthorizedReadPreservesRejectedOriginalTokenAndNeverClearsLaterLogin() = runTest {
        val store = Store()
        val api = FinanceApiClient({ "invalid" }, store, cashImportMockEngine { respond("broken response", HttpStatusCode.Unauthorized, headers) })
        try {
            val failure = assertFailsWith<ApiFailure> { api.readCashImportResultItems("workspace", localCashWriteSetReceiptFixture(), credentials) {} }
            assertEquals(401, failure.status); assertEquals("original-token", failure.rejectedToken)
            assertEquals(0, store.reads); assertEquals(0, store.clears)
        } finally { api.close() }
    }

    @Test fun anExplicitlyEmptyCommandHasNoMissingOutcomesAndRequiresNoNetwork() = runTest {
        val original = localCashWriteSetReceiptFixture()
        val receipt = original.copy(writeSetResult = original.writeSetResult!!.copy(transactions = emptyList(), relations = emptyList()))
        val api = FinanceApiClient({ "invalid" }, Store(), cashImportMockEngine { error("no result items exist") })
        try { assertEquals(emptyList(), api.readCashImportResultItems("workspace", receipt, credentials) {}) }
        finally { api.close() }
    }
}
