package com.finance.tracker

import com.finance.tracker.data.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlinx.serialization.decodeFromString
import org.junit.Assume.assumeTrue

class KotlinCategoryResponseIntegrationTest {
    @Test fun actualKtorWriteResponsesDecodeBeforeTheExistingComposeDirectoryRefresh() = runTest {
        val root = System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")
        assumeTrue("Requires explicitly captured anonymous Ktor responses; a skip does not pass migration acceptance", root != null)
        val captured = Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(root), "category-command-responses.json"))).jsonObject
        val seen = mutableSetOf<String>()
        captured.getValue("responses").jsonArray.forEach { value ->
            val source = value.jsonObject
            val path = source.getValue("path").jsonPrimitive.content
            val method = source.getValue("method").jsonPrimitive.content
            val body = source.getValue("body").jsonPrimitive.content
            val expected = Json.decodeFromString<CashCategoryDirectoryItemDto>(body)
            var requests = 0
            val engine = MockEngine { request ->
                requests++
                assertEquals(if (requests == 1) path else "/api/v1/cash-categories", request.url.encodedPath)
                assertEquals(if (requests == 1) method else "GET", request.method.value)
                respond(if (requests == 1) body else source.getValue("directory").jsonPrimitive.content,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
            try {
                val result = when {
                    path.endsWith("/reorder") -> {
                        seen += "reorder"
                        client.reorderCashCategory(expected.id, "before", expected.revision)
                    }
                    method == "PATCH" -> {
                        seen += "update"
                        client.updateCashCategory(expected.id, expected.name, expected.parentId, expected.description.orEmpty(), expected.revision)
                    }
                    else -> {
                        seen += "create"
                        client.createCashCategory(expected.name, expected.parentId, expected.description.orEmpty(), expected.revision)
                    }
                }
                assertEquals(expected, result)
                val acknowledgement = result.toWriteResult()
                val refreshed = client.fetchCashCategories().toDomain()
                assertTrue(refreshed.items.any { it.id == acknowledgement.id })
                assertEquals(acknowledgement.revision, refreshed.revision)
                assertEquals(2, requests)
            } finally { client.close() }
        }
        assertEquals(setOf("create", "update", "reorder"), seen)
    }

    @Test fun actualKtorDirectoryResponsesDecodeThroughTheComposeApiAndDomainMapper() = runTest {
        val root = System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")
        assumeTrue("Requires explicitly captured anonymous Ktor responses; a skip does not pass migration acceptance", root != null)
        val captured = Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(root), "category-responses.json"))).jsonObject
        assertTrue(captured.getValue("backend").jsonPrimitive.content in setOf("sqlite", "postgresql"))
        val responses = captured.getValue("responses").jsonArray
        assertTrue(responses.isNotEmpty())
        var sawEmpty = false
        var sawNested = false
        responses.forEach { item ->
            val body = item.jsonObject.getValue("body").jsonPrimitive.content
            val engine = MockEngine { request ->
                assertEquals("/api/v1/cash-categories", request.url.encodedPath)
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
            try {
                val wire = client.fetchCashCategories()
                val directory = wire.toDomain()
                assertEquals(wire.revision, directory.revision)
                assertEquals(wire.items.size, directory.items.size)
                sawEmpty = sawEmpty || directory.items.isEmpty()
                wire.items.zip(directory.items).forEach { (source, category) ->
                    assertEquals(source.path, category.path.map { it.name } + category.name)
                    assertTrue(category.path.none { it.id == category.id })
                    sawNested = sawNested || category.path.isNotEmpty()
                }
            } finally { client.close() }
        }
        assertTrue(sawEmpty, "Actual response set must include an empty directory")
        assertTrue(sawNested, "Actual response set must include nested categories")
    }
}
