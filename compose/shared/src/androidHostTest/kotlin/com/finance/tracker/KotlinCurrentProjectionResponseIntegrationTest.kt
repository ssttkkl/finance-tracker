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

class KotlinCurrentProjectionResponseIntegrationTest {
    @Test fun actualKtorCurrentGroupsUseSharedMembersAndTheExistingRepository() = runTest {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")))
        val captures = Json.parseToJsonElement(Files.readString(root.resolve("current-projection-responses.json"))).jsonObject
        assertTrue(captures.getValue("backend").jsonPrimitive.content in setOf("sqlite", "postgresql"))
        val seenStates = mutableSetOf<String>()
        var sawLongRevision = false
        var sawLongVersion = false
        for (raw in captures.getValue("responses").jsonArray) {
            val source = raw.jsonObject
            val body = source.getValue("body").jsonPrimitive.content
            val detail = Json.parseToJsonElement(body).jsonObject
            val projection = detail.getValue("projection").jsonObject
            val id = projection.getValue("projection_id").jsonPrimitive.content
            val members = detail.getValue("members").jsonArray
            val engine = MockEngine { actual ->
                assertEquals(HttpMethod.Get, actual.method)
                assertEquals(source.getValue("path").jsonPrimitive.content, actual.url.encodedPath)
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
            try {
                val actual = KtorCashMaintenanceRepository(client).currentGroup(id)
                assertEquals(detail.getValue("projection_version").jsonPrimitive.long, actual.projectionVersion)
                assertEquals(members.size, actual.members.size)
                for ((index, member) in actual.members.withIndex()) {
                    val expected = members[index].jsonObject
                    assertEquals(expected.getValue("transaction_id").jsonPrimitive.content, member.transactionId)
                    assertEquals(expected.getValue("row_revision").jsonPrimitive.long, member.rowRevision)
                    assertEquals(expected.getValue("confirmation_status").jsonPrimitive.content, member.confirmationStatus)
                    seenStates += member.confirmationStatus
                    sawLongRevision = sawLongRevision || member.rowRevision == Long.MAX_VALUE
                }
                sawLongVersion = sawLongVersion || actual.projectionVersion == Long.MAX_VALUE
            } finally { client.close() }
        }
        assertEquals(setOf("pending", "confirmed"), seenStates)
        assertTrue(sawLongRevision)
        assertTrue(sawLongVersion)
    }
}
