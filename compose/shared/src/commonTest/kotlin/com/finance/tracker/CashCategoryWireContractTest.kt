package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class CashCategoryWireContractTest {
    @Test fun successfulCategoryWritesDecodeBeforeTheExistingDirectoryRefresh() = runTest {
        val methods = mutableListOf<String>()
        val item = """{"id":"root","parent_id":null,"name":"生活","description":null,"path":["生活"],"depth":1,"sort_order":1,"revision":8}"""
        val engine = MockEngine { request ->
            methods += request.method.value
            respond(if (request.method.value == "GET") """{"revision":8,"items":[$item]}""" else item,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
        try {
            assertEquals("root", client.createCashCategory("生活", null, "", 7).id)
            assertEquals("生活", client.fetchCashCategories().toDomain().items.single().name)
            assertEquals("root", client.updateCashCategory("root", "生活", null, "", 8).id)
            assertEquals("生活", client.fetchCashCategories().toDomain().items.single().name)
            assertEquals("root", client.reorderCashCategory("root", "before", 8).id)
            assertEquals("生活", client.fetchCashCategories().toDomain().items.single().name)
            assertEquals(listOf("POST", "GET", "PATCH", "GET", "POST", "GET"), methods)
        } finally { client.close() }
    }

    @Test fun directoryNamePathsResolveRealAncestorIdsAndPreserveLongRevisions() = runTest {
        val engine = MockEngine {
            respond("""{"revision":2147483648,"items":[
                {"id":"child","parent_id":"root","name":"日常","description":null,"path":["生活","日常"],"depth":2,"sort_order":1,"revision":2147483648},
                {"id":"root","parent_id":null,"name":"生活","description":"","path":["生活"],"depth":1,"sort_order":1,"revision":2147483648},
                {"id":"other","parent_id":null,"name":"日常","description":null,"path":["日常"],"depth":1,"sort_order":2,"revision":2147483648}
            ]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
        try {
            val directory = client.fetchCashCategories().toDomain()
            assertEquals(2147483648L, directory.revision.toLong())
            val child = directory.items.first { it.id == "child" }
            assertEquals(listOf("root"), child.path.map { it.id })
            assertEquals(listOf("生活"), child.path.map { it.name })
            assertEquals(2147483648L, child.revision.toLong())
            assertEquals("", directory.items.first { it.id == "root" }.description)
            assertEquals(emptyList(), directory.items.last().path.map { it.id })
        } finally { client.close() }
    }
}

internal class CategoryTokenStore : TokenStore {
    override suspend fun get(): String = "synthetic-admin"
    override suspend fun set(value: String) = Unit
    override suspend fun clear() = Unit
}
