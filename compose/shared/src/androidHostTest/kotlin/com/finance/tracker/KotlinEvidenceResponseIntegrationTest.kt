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

class KotlinEvidenceResponseIntegrationTest {
    @Test fun actualEvidenceRetainsSourceMembersAmountsRelationsAndDraftNulls() = runTest {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")))
        val captures = Json.parseToJsonElement(Files.readString(root.resolve("evidence-responses.json"))).jsonObject
        assertTrue(captures.getValue("backend").jsonPrimitive.content in setOf("sqlite", "postgresql"))
        var seenDraft = false
        var seenRelation = false
        var seenLargeRevision = false
        for (raw in captures.getValue("responses").jsonArray) {
            val capture = raw.jsonObject
            val body = capture.getValue("body").jsonPrimitive.content
            val evidence = Json.parseToJsonElement(body).jsonObject
            val projection = evidence.getValue("projection").jsonObject
            val id = projection.getValue("projection_id").jsonPrimitive.content
            val engine = MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals(capture.getValue("path").jsonPrimitive.content, request.url.encodedPath)
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
            try {
                val sources = client.readCashSourceEvidence(id)
                assertEquals(evidence.getValue("projection_version").jsonPrimitive.long, sources.projectionVersion)
                val members = evidence.getValue("members").jsonArray
                assertEquals(members.size, sources.members.size)
                sources.members.forEachIndexed { index, source ->
                    val expected = members[index].jsonObject
                    assertEquals(expected.getValue("id").jsonPrimitive.content, source.id)
                    assertEquals(expected.getValue("row_revision").jsonPrimitive.long, source.rowRevision)
                    assertEquals(expected.getValue("occurred_at").jsonPrimitive.contentOrNull, source.occurredAt)
                    assertEquals(expected.getValue("amount").jsonPrimitive.contentOrNull, source.amount)
                    val components = expected.getValue("components").jsonArray
                    assertEquals(components.size, source.components.size)
                    source.components.forEachIndexed { position, component ->
                        val part = components[position].jsonObject
                        assertEquals(part.getValue("id").jsonPrimitive.content, component.id)
                        assertEquals(part.getValue("amount").jsonPrimitive.contentOrNull, component.amount)
                        assertEquals(part.getValue("currency").jsonPrimitive.contentOrNull, component.currency)
                    }
                    seenLargeRevision = seenLargeRevision || source.rowRevision == Long.MAX_VALUE
                }
                if (members.any { it.jsonObject["occurred_at"] == JsonNull || it.jsonObject["amount"] == JsonNull }) {
                    seenDraft = true
                    assertEquals("request_failed", assertFailsWith<ApiFailure> { client.fetchEvidence(id) }.code)
                } else {
                    val value = client.fetchEvidence(id).toDomain()
                    assertEquals(evidence.getValue("root_record").jsonObject.getValue("id").jsonPrimitive.content, value.rootRecord.id)
                    assertEquals(sources.projectionVersion, value.projectionVersion)
                    assertEquals(members.map { it.jsonObject.getValue("id").jsonPrimitive.content }, value.members.map { it.id })
                    val relations = evidence.getValue("accepted_relations").jsonArray
                    assertEquals(relations.size, value.acceptedRelations.size)
                    value.acceptedRelations.forEachIndexed { index, relation ->
                        val expected = relations[index].jsonObject
                        assertEquals(expected.getValue("rule_id").jsonPrimitive.content, relation.ruleId)
                        assertEquals(expected.getValue("primary_record").jsonObject.getValue("id").jsonPrimitive.content, relation.primaryRecord?.id)
                        assertEquals(expected.getValue("secondary_record").jsonObject.getValue("amount").jsonPrimitive.content, relation.secondaryRecord?.amount)
                    }
                    seenRelation = seenRelation || relations.isNotEmpty()
                    assertEquals(evidence.getValue("refund_timeline").jsonArray.size, value.refundTimeline.size)
                }
            } finally { client.close() }
        }
        assertTrue(seenDraft); assertTrue(seenRelation); assertTrue(seenLargeRevision)
    }
}
