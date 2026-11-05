package com.finance.tracker

import com.finance.tracker.data.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class KotlinProjectionListResponseIntegrationTest {
    @Test fun actualLedgerPageUsesExistingIndexedClientAndDomainMapping() = runTest {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")))
        val capture = Json.parseToJsonElement(Files.readString(root.resolve("list-response.json"))).jsonObject
        assertTrue(capture.getValue("backend").jsonPrimitive.content in setOf("sqlite", "postgresql"))
        var calls = 0
        val engine = MockEngine { request ->
            calls++; assertEquals(HttpMethod.Get, request.method); assertEquals("/api/v1/cash-projections", request.url.encodedPath)
            assertEquals("0", request.url.parameters["start_index"]); assertEquals("1", request.url.parameters["limit"])
            assertNotNull(request.url.parameters["timezone"])
            respond(capture.getValue("body").toString(), HttpStatusCode.fromValue(capture.getValue("status").jsonPrimitive.int), headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
        try {
            val page = client.fetchCashPage(CashFiltersDto(), startIndex = 0, limit = 1)
            val item = page.items.single().toDomain()
            assertEquals("cash:1003", item.projectionId); assertEquals("1003", item.transactionId); assertEquals("cash-003", item.recordId)
            assertEquals("-12.5", item.amount); assertEquals("餐饮", item.category?.name)
            assertEquals(3, page.totalCount); assertEquals(0, page.startIndex); assertEquals(0, page.itemsBefore); assertEquals(2, page.itemsAfter)
            assertEquals(listOf("CNY"), page.filterOptions.currencies)
            assertEquals(listOf("日用", "餐饮", "收入", "转账"), page.filterOptions.categories.map { it.name })
            assertEquals("2026-07", page.monthPositions.single().month); assertEquals(3, page.monthPositions.single().endIndex)
            assertEquals("2000", page.monthlySummaries.single().currencies.single().income)
            assertEquals("-112.5", page.monthlySummaries.single().currencies.single().expense)
            assertNull(page.nextCursor); assertEquals(1, calls)
        } finally { client.close() }
    }
}
