package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CashSourceIdentityGoldenTest {
    private fun cases(): JsonObject = Json.parseToJsonElement(requireNotNull(
        javaClass.getResourceAsStream("/python-cash-source-identity.json")).bufferedReader().use { it.readText() }).jsonObject

    private fun coverage(element: JsonObject): CashSourceCoverage = CashSourceCoverage(
        element["start"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
        element["end"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
        element.getValue("complete").jsonPrimitive.boolean,
        element.getValue("evidence").jsonPrimitive.content,
        element.getValue("filters").jsonObject.entries.associate { it.key to it.value.jsonPrimitive.content },
        element.getValue("strategy_version").jsonPrimitive.content,
        element.getValue("start_inclusive").jsonPrimitive.boolean,
        element.getValue("end_inclusive").jsonPrimitive.boolean,
        element.getValue("time_precision").jsonPrimitive.content,
        element["document_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
        element["page_total"]?.takeIf { it != JsonNull }?.jsonPrimitive?.intOrNull,
        element.getValue("page_fingerprints").jsonObject.entries.associate { it.key to it.value.jsonPrimitive.content })

    private fun String?.contentOrNull(): String? = this

    @Test fun occurrenceAndScopeKeysMatchPython() {
        val data = cases()
        for (raw in data.getValue("occurrences").jsonArray) {
            val item = raw.jsonObject
            val input = item.getValue("input").jsonObject
            val fields = input.getValue("fields").jsonObject.entries.associate { it.key to it.value.jsonPrimitive.content }
            lateinit var occurrence: SourceOccurrence
            val result = runCatching {
                occurrence = SourceOccurrence(
                    input.getValue("source_type").jsonPrimitive.content,
                    input["source_account_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    input.getValue("file_digest").jsonPrimitive.content,
                    input.getValue("parser_version").jsonPrimitive.content,
                    input.getValue("table_key").jsonPrimitive.content,
                    input.getValue("position").jsonPrimitive.int, fields,
                    input["coverage"]?.takeIf { it != JsonNull }?.jsonObject?.let(::coverage)
                        ?: CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1", endInclusive = false),
                    input["provider_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    input["stable_evidence"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    input["document_position"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull)
            }
            val expectedError = item["error"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            if (expectedError != null) {
                assertIs<IllegalArgumentException>(result.exceptionOrNull(), item.getValue("name").jsonPrimitive.content)
                assertEquals(expectedError, result.exceptionOrNull()?.message)
                continue
            }
            result.getOrElse { throw AssertionError(item.getValue("name").jsonPrimitive.content, it) }
            assertEquals(item.getValue("occurrence_key").jsonPrimitive.content, occurrence.occurrenceKey)
            assertEquals(item.getValue("file_scope_key").jsonPrimitive.content, occurrence.fileScopeKey)
            assertEquals(item["document_scope_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                occurrence.documentScopeKey)
            assertEquals(item["document_occurrence_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                occurrence.documentOccurrenceKey)
            assertEquals(item.getValue("observation_scope_key").jsonPrimitive.content, occurrence.observationScopeKey)
            assertEquals(item["document_binding_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                occurrence.documentBindingKey)
            assertEquals(item["document_page"]?.takeIf { it != JsonNull }?.jsonArray
                ?.map { it.jsonPrimitive.content }?.let { it[0] to it[1] }, occurrence.documentPage)
            assertEquals(item["document_page_scope_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                occurrence.documentPageScopeKey)
            assertEquals(item.getValue("file_binding_key").jsonPrimitive.content, occurrence.fileBindingKey)
            assertEquals(item["provider_identity"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                occurrence.providerIdentity)
            assertEquals(item["provider_binding_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                occurrence.providerBindingKey)
            assertEquals(item["stable_evidence_binding_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                occurrence.stableEvidenceBindingKey)
            assertEquals(item.getValue("binding_keys").jsonArray.map { it.jsonPrimitive.content }, occurrence.bindingKeys)
            assertEquals(item.getValue("binding_specs").jsonArray.map {
                val spec = it.jsonObject
                listOf(spec.getValue("source_type").jsonPrimitive.content, spec.getValue("scope_kind").jsonPrimitive.content,
                    spec.getValue("scope_key").jsonPrimitive.content, spec.getValue("identity_kind").jsonPrimitive.content,
                    spec.getValue("identity_key").jsonPrimitive.content, spec.getValue("key").jsonPrimitive.content)
            }, occurrence.bindingSpecs.map { listOf(it.sourceType, it.scopeKind, it.scopeKey, it.identityKind, it.identityKey, it.key) })
        }
        for (raw in data.getValue("scopes").jsonArray) {
            val item = raw.jsonObject
            val source = item.getValue("source_type").jsonPrimitive.content
            val owner = item["owner"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            val label = item["label"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            val result = runCatching { CashSourceScope.sourceAccountKey(source, owner, label) }
            val expectedError = item["error"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            if (expectedError != null) assertEquals(expectedError, (result.exceptionOrNull() as? CashSourceScopeError)?.code)
            else assertEquals(item["value"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull, result.getOrThrow())
        }
    }

    @Test fun documentCompletenessMatchesPython() {
        for (raw in cases().getValue("documents").jsonArray) {
            val item = raw.jsonObject
            val values = item.getValue("evidence").jsonArray.map { entry ->
                val value = entry.jsonObject
                SourceDocumentEvidence(value.getValue("source_type").jsonPrimitive.content,
                    value.getValue("parser_version").jsonPrimitive.content,
                    coverage(value.getValue("coverage").jsonObject))
            }
            val completeness = CashSourceDocuments.documentCompleteness(values)
            val expected = item.getValue("completeness").jsonArray.flatMap { pair ->
                val list = pair.jsonArray
                val namespace = list[0].jsonArray.map { it.jsonPrimitive.content }
                listOf(DocumentNamespace(namespace[0], namespace[1], namespace[2]) to list[1].jsonPrimitive.boolean)
            }.toMap()
            assertEquals(expected, completeness, item.getValue("name").jsonPrimitive.content)
            for ((index, value) in item.getValue("current").jsonArray.withIndex()) {
                val expectedItem = value.jsonObject
                val current = CashSourceDocuments.currentDocumentCoverage(values[index], completeness)
                assertEquals(expectedItem.getValue("complete").jsonPrimitive.boolean, current.complete)
                assertEquals(expectedItem.getValue("evidence").jsonPrimitive.content, current.evidence)
            }
        }
    }

    @Test fun rejectsInvalidDocumentEvidence() {
        assertFailsWith<IllegalArgumentException> {
            CashSourceCoverage("2026-07-31T16:00:00+00:00", "2026-08-31T16:00:00+00:00", true, "e",
                emptyMap(), "v", documentKey = "a".repeat(64), pageTotal = 0, pageFingerprints = mapOf("1" to "b".repeat(64)))
        }
        assertFailsWith<IllegalArgumentException> {
            CashSourceCoverage("2026-07-31T16:00:00+00:00", "2026-08-31T16:00:00+00:00", true, "e",
                emptyMap(), "v", documentKey = "a".repeat(64), pageTotal = 1, pageFingerprints = mapOf("2" to "b".repeat(64)))
        }
        assertFailsWith<IllegalArgumentException> {
            CashSourceCoverage("2026-07-31T16:00:00+00:00", "2026-08-31T16:00:00+00:00", true, "e",
                emptyMap(), "v", documentKey = "not-a-key", pageTotal = 1, pageFingerprints = mapOf("1" to "b".repeat(64)))
        }
    }

    @Test fun comparableAndContainsMatchPython() {
        for (raw in cases().getValue("comparables").jsonArray) {
            val item = raw.jsonObject
            val left = coverage(item.getValue("left").jsonObject)
            val right = coverage(item.getValue("right").jsonObject)
            val whenValue = item.getValue("when").jsonPrimitive.content
            assertEquals(item.getValue("contains").jsonPrimitive.boolean, left.contains(whenValue),
                item.getValue("name").jsonPrimitive.content)
            assertEquals(item.getValue("comparable").jsonPrimitive.boolean, left.comparable(right, whenValue),
                item.getValue("name").jsonPrimitive.content)
        }
    }

}
