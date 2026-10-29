package com.finance.tracker

import com.finance.tracker.data.*
import com.finance.tracker.domain.StructuredObject
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class KotlinRecordDetailResponseIntegrationTest {
    @Test fun actualDetailDecodesFieldsEntriesIssuesRelationsAndSourcePayload() = runTest {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")))
        val captures = Json.parseToJsonElement(Files.readString(root.resolve("detail-responses.json"))).jsonObject
        assertTrue(captures.getValue("backend").jsonPrimitive.content in setOf("sqlite", "postgresql"))
        var seenDraft = false
        var seenEntry = false
        var seenRelation = false
        var seenProjection = false
        var seenAmbiguousCandidate = false
        var seenSourcePayload = false
        for (raw in captures.getValue("responses").jsonArray) {
            val capture = raw.jsonObject
            val body = capture.getValue("body").jsonPrimitive.content
            val expected = Json.parseToJsonElement(body).jsonObject
            val transaction = expected.getValue("transaction_id").jsonPrimitive.content
            val engine = MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/api/v1/cash-records/$transaction", request.url.encodedPath)
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
            try {
                val detail = client.readCurrentCashRecord(transaction)
                assertEquals(transaction, detail.transactionId)
                assertEquals(expected.getValue("state").jsonPrimitive.content, detail.state)
                assertEquals(expected.getValue("row_revision").jsonPrimitive.long, detail.rowRevision)
                assertEquals(expected.getValue("confirmation_status").jsonPrimitive.content, detail.confirmationStatus)
                assertEquals(expected.getValue("record_origin").jsonPrimitive.content, detail.recordOrigin)
                assertEquals(expected.getValue("editable_fields").jsonArray.map { it.jsonPrimitive.content }, detail.editableFields)
                assertEquals(expected.getValue("fields").jsonObject, detail.fields)
                val fields = detail.fields
                assertEquals(expected.getValue("fields").jsonObject.getValue("amount"), fields["amount"])
                assertEquals(expected.getValue("fields").jsonObject.getValue("occurred_at"), fields["occurred_at"])
                assertEquals(expected.getValue("fields").jsonObject.getValue("components"), fields["components"])
                if (fields["source_payload"] is JsonObject) {
                    seenSourcePayload = true
                    assertIs<StructuredObject>(fields["source_payload"]!!.toStructuredValue())
                }
                if (expected.getValue("state").jsonPrimitive.content == "draft") seenDraft = true
                expected["entry"]?.takeIf { it != JsonNull }?.jsonObject?.let { entry ->
                    seenEntry = true
                    val actual = assertNotNull(detail.entry)
                    assertEquals(entry.getValue("entry_id").jsonPrimitive.content, actual.entryId)
                    assertEquals(entry.getValue("revision").jsonPrimitive.long, actual.revision)
                    assertEquals(entry.getValue("manual_fields").jsonArray.map { it.jsonPrimitive.content }, actual.manualFields)
                    assertEquals(entry.getValue("editable_fields").jsonArray.map { it.jsonPrimitive.content }, actual.editableFields)
                    assertEquals(entry.getValue("supports_transfer_edit").jsonPrimitive.boolean, actual.supportsTransferEdit)
                    assertEquals(entry.getValue("fields").jsonObject, actual.fields)
                }
                val issues = expected.getValue("issues").jsonArray
                assertEquals(issues.size, detail.issues.size)
                issues.forEachIndexed { index, item ->
                    val issue = item.jsonObject
                    val actual = detail.issues[index]
                    assertEquals(issue.getValue("issue_id").jsonPrimitive.content, actual.issueId)
                    assertEquals(issue.getValue("revision").jsonPrimitive.long, actual.revision)
                    assertEquals(issue.getValue("allowed_resolutions").jsonArray.map { it.jsonPrimitive.content }, actual.allowedResolutions)
                    assertEquals(issue.getValue("pending_fields").jsonObject, actual.pendingFields)
                    assertEquals(issue["pending_source_payload_present"], actual.pendingSourcePayloadPresent?.let(::JsonPrimitive) ?: JsonNull)
                    val candidates = issue["candidates"]?.jsonArray ?: JsonArray(emptyList())
                    assertEquals(candidates.size, actual.candidates.size)
                    if (candidates.isNotEmpty()) {
                        seenAmbiguousCandidate = true
                        actual.candidates.forEachIndexed { position, candidate ->
                            val expectedCandidate = candidates[position].jsonObject
                            assertEquals(expectedCandidate.getValue("transaction_id").jsonPrimitive.content, candidate.transactionId)
                            assertEquals(expectedCandidate.getValue("row_revision").jsonPrimitive.long, candidate.rowRevision)
                            assertEquals(expectedCandidate.getValue("state").jsonPrimitive.content, candidate.state)
                        }
                    }
                }
                val relations = expected.getValue("relations").jsonArray
                assertEquals(relations.size, detail.relations.size)
                if (relations.isNotEmpty()) seenRelation = true
                relations.forEachIndexed { index, item ->
                    val relation = item.jsonObject
                    val actual = detail.relations[index]
                    assertEquals(relation.getValue("relation_id").jsonPrimitive.content, actual.relationId)
                    assertEquals(relation.getValue("kind").jsonPrimitive.content, actual.kind)
                    assertEquals(relation.getValue("state").jsonPrimitive.content, actual.state)
                    assertEquals(relation.getValue("active").jsonPrimitive.boolean, actual.active)
                    assertEquals(relation.getValue("row_revision").jsonPrimitive.long, actual.rowRevision)
                    assertEquals(relation["decision_group_id"], actual.decisionGroupId?.let(::JsonPrimitive) ?: JsonNull)
                }
                expected["projection_ref"]?.takeIf { it != JsonNull }?.jsonObject?.let { projection ->
                    seenProjection = true
                    val actual = assertNotNull(detail.projectionRef)
                    assertEquals(projection.getValue("projection_id").jsonPrimitive.content, actual.projectionId)
                    assertEquals(projection.getValue("projection_version").jsonPrimitive.long, actual.projectionVersion)
                    assertEquals(projection.getValue("attention_count").jsonPrimitive.long, actual.attentionCount)
                    // 来源读取链路要求成员详情与证据使用同一投影与版本。
                    assertEquals(projection.getValue("projection_id").jsonPrimitive.content, "cash:$transaction")
                }
                val domain = detail.toDomain()
                assertEquals(detail.transactionId, domain.transactionId)
                assertEquals(detail.rowRevision, domain.rowRevision)
                assertEquals(detail.fields, domain.fields.toJsonObject())
                assertEquals(detail.relations.map { it.relationId }, domain.relations.map { it.relationId })
                assertEquals(detail.relations.map { it.state }, domain.relations.map { it.state })
                assertEquals(detail.issues.map { it.allowedResolutions }, domain.issues.map { it.allowedResolutions })
                assertEquals(detail.issues.map { it.candidates.map { candidate -> candidate.transactionId } },
                    domain.issues.map { issue -> issue.candidates.map { it.transactionId } })
                assertEquals(detail.entry?.entryId, domain.entry?.entryId)
                assertEquals(detail.entry?.editableFields, domain.entry?.editableFields)
                assertEquals(detail.projectionRef?.projectionId, domain.projectionRef?.projectionId)
                assertEquals(detail.projectionRef?.projectionVersion, domain.projectionRef?.projectionVersion)
                detail.issues.zip(domain.issues).forEach { (actual, mapped) ->
                    val body = actual.pendingSourcePayload
                    if (body != null) assertEquals(body, mapped.pendingSourcePayload?.toJsonObject())
                }
            } finally { client.close() }
        }
        assertTrue(seenDraft); assertTrue(seenEntry); assertTrue(seenRelation)
        assertTrue(seenProjection); assertTrue(seenAmbiguousCandidate); assertTrue(seenSourcePayload)
    }

    @Test fun existingSourcesConsumerCrossChecksEvidenceAndCurrentDetail() = runTest {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")))
        val captures = Json.parseToJsonElement(Files.readString(root.resolve("detail-responses.json"))).jsonObject
        val detail = captures.getValue("responses").jsonArray.map { it.jsonObject }
            .first { it.getValue("path").jsonPrimitive.content == "/api/v1/cash-records/1001" }
        val detailBody = detail.getValue("body").jsonPrimitive.content
        val expected = Json.parseToJsonElement(detailBody).jsonObject
        val revision = expected.getValue("row_revision").jsonPrimitive.long
        val evidence = buildJsonObject {
            put("projection_version", 7)
            put("projection", buildJsonObject {
                put("projection_id", "cash:1001"); put("occurred_at", "2026-07-03T09:00:00+00:00")
                put("amount", "12.5"); put("currency", "CNY"); put("economic_type", "expense")
                put("record_id", "wechat_1"); put("transaction_id", "1001"); put("member_count", 1)
                put("visible", true)
            })
            put("members", JsonArray(listOf(buildJsonObject {
                put("id", "1001"); put("row_revision", revision)
                put("occurred_at", "2026-07-03T09:00:00+00:00"); put("amount", "12.5"); put("currency", "CNY")
                put("counterparty", "咖啡店"); put("note", "原备注"); put("account_name", "日常账户")
                put("components", JsonArray(listOf(buildJsonObject {
                    put("id", "2001"); put("amount", "12.5"); put("currency", "CNY"); put("account_name", "日常账户")
                })))
            })))
        }
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/api/v1/evidence/cash-projections/cash%3A1001" ->
                    respond(evidence.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath == "/api/v1/cash-records/1001" ->
                    respond(detailBody, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                else -> error("unexpected ${request.url.encodedPath}")
            }
        }
        val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
        try {
            val sources = KtorCashMaintenanceRepository(client).sources("cash:1001")
            assertEquals(7, sources.projectionVersion)
            val source = sources.members.single()
            assertEquals("1001", source.transactionId)
            assertEquals("日常账户", source.accountName)
            assertIs<StructuredObject>(source.sourcePayload)
            assertEquals(expected.getValue("relations").jsonArray.size, source.relations.size)
            assertEquals(expected.getValue("fields").jsonObject, source.fields.toJsonObject())
        } finally { client.close() }
    }
}
