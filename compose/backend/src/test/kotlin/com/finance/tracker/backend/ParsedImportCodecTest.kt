package com.finance.tracker.backend

import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class ParsedImportCodecTest {
    private val now = Instant.parse("2026-10-21T12:00:00Z")
    private fun file() = ParsedImportFile("f1", "a".repeat(64), "parsed",
        ParsedCashDocument("alipay", "csv:0", "native-v1", listOf(ParsedCashRow(8,
            mapOf("amount" to "-20.00", "note" to "原始说明", "source_payload" to mapOf("金额" to "20.00")),
            "fixture-bank", "001")), "fixture-owner", "fixture-owner",
            CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), 1L, 1),
        clientFileRef = "client-f1", filename = "synthetic.csv", size = 100, sourceType = "alipay")
    private fun session() = ParsedImportSession("z".repeat(43), "workspace-a", "user-a", now, now.plusSeconds(60),
        java.math.BigInteger.ONE, listOf(file()))

    @Test fun completeWireRoundTripKeepsOnlyParsedContentAndIsDetached() {
        val codec = ParsedImportCodec()
        val original = session()
        val body = codec.encode(original)
        assertFalse(body.decodeToString().contains(original.sessionId))
        val wire = Json.parseToJsonElement(body.decodeToString()).jsonObject
        assertEquals(tokenDigest(original.sessionId), wire.getValue("session_digest").jsonPrimitive.content)
        val savedFile = wire.getValue("files").jsonArray.single().jsonObject
        assertFalse("content" in savedFile); assertFalse("password" in savedFile)
        assertEquals(original, codec.decode(original.sessionId, body))
        val mutable = mutableMapOf<String, Any?>("nested" to mutableListOf("before"))
        val input = original.copy(plan = mutable)
        val encoded = codec.encode(input)
        (mutable.getValue("nested") as MutableList<*>).clear()
        assertEquals(mapOf("nested" to listOf("before")), codec.decode(original.sessionId, encoded).plan)
    }

    @Test fun fileMetadataScopeAndDocumentProofSurviveRoundTrip() {
        val codec = ParsedImportCodec()
        val file = file()
        val coverage = file.document!!.coverage.copy(documentKey = "f".repeat(64), pageTotal = 3,
            pageFingerprints = mapOf("2" to "e".repeat(64)))
        val row = file.document.rows.single().copy(documentPosition = "p2:t0:r1", sourceAccountIdentifier = "owner-row",
            sourceExplanation = mapOf("summary" to "", "_is_refund" to false))
        val current = session().copy(files = listOf(file.copy(document = file.document.copy(coverage = coverage, rows = listOf(row)))))
        val restored = codec.decode(current.sessionId, codec.encode(current))
        assertEquals(current, restored)
        assertEquals(coverage, restored.files.single().document!!.coverage)
        assertEquals(row, restored.files.single().document!!.rows.single())
    }

    @Test fun oldOptionalFieldsRestoreTheirOriginalDefaults() {
        val codec = ParsedImportCodec()
        val current = session()
        val root = Json.parseToJsonElement(codec.encode(current).decodeToString()).jsonObject.toMutableMap()
        root.remove("workflow_kind"); root.remove("context_data")
        val file = root.getValue("files").jsonArray.single().jsonObject.toMutableMap()
        file.remove("is_subset"); file.remove("last_modified_at_ms"); file.remove("diagnostic")
        val document = file.getValue("document").jsonObject.toMutableMap()
        document["rows"] = JsonArray(document.getValue("rows").jsonArray.map { JsonObject(it.jsonObject - "source_explanation") })
        file["document"] = JsonObject(document)
        root["files"] = JsonArray(listOf(JsonObject(file)))
        assertEquals(current, codec.decode(current.sessionId, JsonObject(root).toString().encodeToByteArray()))
    }

    @Test fun independentParsedAndFullBudgetsRejectEscapedOverflow() {
        val codec = ParsedImportCodec(maxBytes = 12000, maxParsedBytes = 3000)
        val current = session()
        val accepted = current.copy(plan = mapOf("synthetic" to "x".repeat(5000)))
        assertEquals(accepted, codec.decode(current.sessionId, codec.encode(accepted)))
        for (plan in listOf("x".repeat(13000), "\"".repeat(7000))) {
            assertEquals("import_session_draft_too_large", assertFailsWith<ParsedImportDataError> {
                codec.encode(current.copy(plan = mapOf("synthetic" to plan)))
            }.code)
        }
        val file = current.files.single()
        val large = file.copy(document = file.document!!.copy(rows = listOf(file.document.rows.single().copy(
            fields = mapOf("note" to "x".repeat(4000))))))
        assertEquals("import_session_draft_too_large", assertFailsWith<ParsedImportDataError> {
            codec.encode(current.copy(files = listOf(large)))
        }.code)
    }

    @Test fun malformedNestedValuesNeverBecomeValidJsonByStringConversion() {
        val codec = ParsedImportCodec()
        val current = session()
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, ByteArray(1), now, Any(), "\uD800", mapOf(1 to "value"))) {
            assertEquals("import_session_json_invalid", assertFailsWith<ParsedImportDataError> {
                codec.encode(current.copy(plan = mapOf("value" to bad)))
            }.code)
        }
        assertEquals("import_session_json_invalid", assertFailsWith<ParsedImportDataError> {
            codec.encode(current.copy(plan = mapOf("\uD800" to "value")))
        }.code)
        for (dictionary in listOf(false, true)) {
            var value: Any = "正常字段"
            repeat(62) { value = if (dictionary) mapOf("value" to value) else listOf(value) }
            val accepted = current.copy(plan = mapOf("nested" to value))
            assertEquals(accepted, codec.decode(current.sessionId, codec.encode(accepted)))
            value = if (dictionary) mapOf("value" to value) else listOf(value)
            assertEquals("import_session_json_invalid", assertFailsWith<ParsedImportDataError> {
                codec.encode(current.copy(plan = mapOf("nested" to value)))
            }.code)
        }
    }

    @Test fun invalidFilesAndSessionMetadataAreRejectedBeforeEncoding() {
        val codec = ParsedImportCodec()
        val current = session()
        val file = file()
        val row = file.document!!.rows.single()
        val badFiles = listOf(file.copy(filename = "../private.csv"), file.copy(clientFileRef = "bad!"),
            file.copy(size = -1), file.copy(lastModifiedAtMs = -1), file.copy(sourceScopeLabel = "named"),
            file.copy(document = file.document.copy(rows = listOf(row, row))),
            file.copy(document = file.document.copy(rows = listOf(row.copy(fields = null)))),
            file.copy(document = file.document.copy(rows = listOf(row.copy(sourceExplanation = mapOf("secret" to "x"))))),
            file.copy(document = file.document.copy(rows = listOf(row.copy(sourceAccountIdentifier = "")))))
        badFiles.forEach { assertFailsWith<ParsedImportDataError> { codec.encode(current.copy(files = listOf(it))) } }
        for (bad in listOf(current.copy(revision = java.math.BigInteger.ZERO), current.copy(workflowKind = "failed_subset"),
            current.copy(workflowKind = "mutation"), current.copy(expiresAt = now.plusSeconds(1801))))
            assertFailsWith<ParsedImportDataError> { codec.encode(bad) }
        assertFailsWith<ParsedImportDataError> { codec.encode(current.copy(files = listOf(file, file))) }
        assertFailsWith<ParsedImportStorageUnavailable> { codec.decode("b".repeat(43), codec.encode(current)) }
        assertFailsWith<ParsedImportStorageUnavailable> { codec.decode(current.sessionId, "{}".encodeToByteArray()) }
    }

    @Test fun pythonStoredBytesAndBudgetBoundariesRemainCompatible() {
        val codec = ParsedImportCodec()
        val data = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-parsed-import-codec.json"))
            .bufferedReader().use { it.readText() }).jsonObject
        for (raw in data.getValue("sessions").jsonArray) {
            val item = raw.jsonObject
            val body = item.getValue("body").jsonPrimitive.content
            val restored = codec.decode("z".repeat(43), body.encodeToByteArray())
            assertEquals(body, codec.encode(restored).decodeToString(), item.getValue("name").jsonPrimitive.content)
        }
        fun value(input: JsonElement): Any? = when (input) {
            JsonNull -> null
            is JsonArray -> input.map(::value)
            is JsonObject -> input.mapValues { value(it.value) }
            is JsonPrimitive -> when {
                input.isString -> input.content
                input.booleanOrNull != null -> input.boolean
                input.content.matches(Regex("-?[0-9]+")) -> java.math.BigInteger(input.content)
                else -> input.double
            }
        }
        for ((index, raw) in data.getValue("budgets").jsonArray.withIndex()) {
            val item = raw.jsonObject
            val result = runCatching { codec.budget(value(item.getValue("value")), item.getValue("maximum").jsonPrimitive.long,
                item.getValue("depth").jsonPrimitive.int) }
            if (item["error"] != JsonNull) assertEquals(item.getValue("error").jsonPrimitive.content,
                (result.exceptionOrNull() as? ParsedImportDataError)?.code, "budget $index")
            else assertEquals(item.getValue("remaining").jsonPrimitive.long, result.getOrThrow(), "budget $index")
        }
    }

    @Test fun rawJsonSyntaxAndDuplicateKeysFollowPython() {
        val codec = ParsedImportCodec()
        val current = session()
        val text = codec.encode(current).decodeToString()
        val duplicate = text.replace("\"revision\":1", "\"revision\":0,\"revision\":1")
        assertEquals(current, codec.decode(current.sessionId, duplicate.encodeToByteArray()))
        assertEquals(current, codec.decode(current.sessionId, text.replace("2026-10-21T12:", "2026-10-21Z12:").encodeToByteArray()))
        for (bad in listOf(text.replace("\"revision\":1", "\"revision\":01"),
            text.replace("\"revision\":1", "\"revision\":+1"),
            text.replace("原始说明", "raw\ncontrol"), text.replace("\"cancelled\":false", "\"cancelled\":False")))
            assertFailsWith<ParsedImportStorageUnavailable> { codec.decode(current.sessionId, bad.encodeToByteArray()) }
    }

    @Test fun requiredNullableFieldsAndPlanMetadataFailClosed() {
        val codec = ParsedImportCodec()
        val current = session()
        val root = Json.parseToJsonElement(codec.encode(current).decodeToString()).jsonObject
        fun bytes(value: Map<String, JsonElement>) = JsonObject(value).toString().encodeToByteArray()
        val corrupt = listOf(root - "plan", root + ("session_digest" to JsonPrimitive("wrong")),
            root + ("revision" to JsonPrimitive(true)), root + ("workspace_id" to JsonNull),
            root + ("created_at" to JsonPrimitive("2026-10-21T12:00:00")), root + ("plan" to JsonArray(emptyList())),
            root + ("files" to JsonObject(emptyMap())))
        for (bad in corrupt) {
            assertFailsWith<ParsedImportStorageUnavailable> { codec.decode(current.sessionId, bytes(bad)) }
            assertFailsWith<ParsedImportStorageUnavailable> { codec.decodePlan(current.sessionId, bytes(bad)) }
        }
        val file = root.getValue("files").jsonArray.single().jsonObject
        assertFailsWith<ParsedImportStorageUnavailable> {
            codec.decode(current.sessionId, bytes(root + ("files" to JsonArray(listOf(JsonObject(file - "source_type"))))))
        }
        val document = file.getValue("document").jsonObject
        val row = document.getValue("rows").jsonArray.single().jsonObject
        val damaged = JsonObject(file + ("document" to JsonObject(document + ("rows" to JsonArray(listOf(
            JsonObject((row - "fields") + ("disposition" to JsonPrimitive("skipped")))))))))
        assertFailsWith<ParsedImportStorageUnavailable> {
            codec.decode(current.sessionId, bytes(root + ("files" to JsonArray(listOf(damaged)))))
        }
        val projection = codec.decodePlan(current.sessionId, bytes(root + ("files" to JsonArray(listOf(JsonObject(emptyMap())))) +
            ("plan" to JsonObject(mapOf("value" to JsonPrimitive("accepted"))))))
        assertTrue(projection.files.isEmpty()); assertEquals(mapOf("value" to "accepted"), projection.plan)
    }

    @Test fun unboundedRevisionsCountsAndCanonicalNumericBudgets() {
        val codec = ParsedImportCodec()
        val current = session()
        val huge = java.math.BigInteger.ONE.shiftLeft(63)
        val diagnostic = mapOf("error_id" to "a".repeat(32), "stage" to "request", "scope" to "row",
            "locations" to listOf(mapOf("row_number" to huge)), "total_locations" to huge)
        val file = current.files.single()
        val document = file.document!!
        val updated = current.copy(revision = huge, files = listOf(file.copy(diagnostic = diagnostic,
            document = document.copy(declaredCount = huge, observedCount = huge))))
        assertEquals(updated, codec.decode(current.sessionId, codec.encode(updated)))
        for (count in listOf(1.5, -0.0)) {
            val numeric = current.copy(files = listOf(file.copy(document = document.copy(declaredCount = count, observedCount = count))))
            assertEquals(numeric, codec.decode(current.sessionId, codec.encode(numeric)))
        }
        val raw = codec.encode(current.copy(files = listOf(file.copy(document = document.copy(rows = listOf(
            document.rows.single().copy(fields = mapOf("number" to 1000.0)))))))).decodeToString().replace("1000.0", "1e3")
        val parsedSize = Json.parseToJsonElement(raw).jsonObject.getValue("files").toString().encodeToByteArray().size
        assertFailsWith<ParsedImportStorageUnavailable> {
            ParsedImportCodec(maxParsedBytes = parsedSize).decode(current.sessionId, raw.encodeToByteArray())
        }
        assertFailsWith<ParsedImportDataError> { codec.encode(current.copy(expiresAt = current.createdAt.plusNanos(1))) }
    }
}
