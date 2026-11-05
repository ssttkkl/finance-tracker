package com.finance.tracker.business.domain

import kotlin.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class CashSourceIssueResolutionRulesTest {
    private fun pendingCashSourcePatch(issue: CashSourceIssue) = pendingLocalCashSourceIssuePatch(issue)
    private fun prepareCashSourceResolutions(current: Map<String, Any?>, issues: List<CashSourceIssue>,
        choices: List<CashSourceResolution>, patch: Map<String, Any?>, protected: Set<String>) =
        prepareLocalCashSourceIssueResolutions(encodeLocalCashValue(current).jsonObject, issues, choices,
            encodeLocalCashValue(patch).jsonObject, protected)
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun issue(fields: String = "{}", payload: String = "null", id: Long = 1) =
        CashSourceIssue(id, 2, "source_update_pending", null, json(fields), json(payload), json("{}"))
    private fun record(vararg values: Pair<String, Any?>) = linkedMapOf<String, Any?>(
        "state" to "posted", "record_origin" to "import", "amount" to CashAmount.parse("-20.00"),
        "currency" to "CNY", "occurred_at" to Instant.parse("2026-08-01T00:00:00Z"), "account_id" to "41",
        "record_type" to "consumption", "record_subtype" to "not_applicable", "cash_granularity" to "atomic",
        "note" to "原备注", "source_payload" to mapOf("备注" to "原备注"),
        "components" to listOf(mapOf("component_id" to "501", "account_id" to "41", "amount" to CashAmount.parse("-20.00"), "currency" to "CNY", "ordinal" to SourceJsonInteger(0))),
    ) + values
    private fun selected(id: Long = 1) = CashSourceResolution(id.toString(), 2, "apply_source")

    @Test fun missingBodyAndExplicitNullRemainDifferent() {
        assertFailsWith<CashPolicyError> { pendingCashSourcePatch(issue()) }
        assertEquals(json("""{"source_payload":null}"""), pendingCashSourcePatch(issue("""{"_source_payload_present":true}""")))
        assertEquals(json("""{"counterparty":"商户"}"""), pendingCashSourcePatch(issue("""{"counterparty":"商户"}""")))
        for (payload in listOf("null", "{}", """{"备注":"来源"}""")) {
            assertEquals(json("""{"source_payload":$payload}"""), pendingCashSourcePatch(issue("""{"source_payload":$payload}""")))
        }
        assertEquals(json("""{"source_payload":{}}"""), pendingCashSourcePatch(issue(payload = "{}")))
    }

    @Test fun invalidPrivateMetadataCannotAuthorizeSourceAdoption() {
        for (fields in listOf("[]", "null", "true", """{"_source_payload_present":"true"}""",
            """{"_source_payload_present":1}""", """{"_source_explanation":[]}""", """{"account_name":"x"}""",
            """{"record_id":"forged"}""", """{"workspace_id":"other"}""")) {
            assertEquals("issue_resolution_invalid", assertFailsWith<CashPolicyError> {
                pendingCashSourcePatch(issue(fields))
            }.code, fields)
        }
        assertFailsWith<CashPolicyError> { pendingCashSourcePatch(issue("""{"_source_payload_present":false}""", "{}")) }
        assertEquals(json("""{"note":"valid"}"""), pendingCashSourcePatch(issue("""{"note":"valid","_source_explanation":{}}""")))
    }

    @Test fun protectedMutableFieldsStillValidateAndFixedConflictsPrecedeExplicitPatch() {
        val bad = issue("""{"amount":"-21","note":"新备注"}""")
        val conflict = assertFailsWith<CashPolicyError> {
            prepareCashSourceResolutions(record(), listOf(bad), listOf(selected()), mapOf("amount" to "-20"), emptySet())
        }
        assertEquals("fixed_field_conflict", conflict.code)
        assertEquals(listOf("amount"), conflict.fields)
        assertFailsWith<CashPolicyError> {
            prepareCashSourceResolutions(record(), listOf(issue("""{"note":null}""")), listOf(selected()), emptyMap(), setOf("note"))
        }
        val result = prepareCashSourceResolutions(record(), listOf(issue("""{"note":"新备注"}""")), listOf(selected()), emptyMap(), setOf("note"))
        assertEquals("新备注", result.patch["note"])
    }

    @Test fun sourceComponentsMustRetainOwnedReferencesAndUniqueOrdinals() {
        for (parts in listOf(
            """[{"component_id":"other","ordinal":0}]""",
            """[{"component_id":"501","ordinal":0},{"component_id":"501","ordinal":1}]""",
            """[{"ordinal":0},{"ordinal":0}]""", """[{}]""",
            """[{"ordinal":0,"label":"not permitted"}]""", """[{"ordinal":2147483648}]""",
        )) assertFailsWith<CashPolicyError> {
            prepareCashSourceResolutions(record("state" to "draft"), listOf(issue("""{"components":$parts}""")), listOf(selected()), emptyMap(), emptySet())
        }
        val result = prepareCashSourceResolutions(record("state" to "draft"), listOf(issue("""{"components":[]}""")), listOf(selected()), emptyMap(), emptySet())
        assertEquals(emptyList<Any?>(), result.patch["components"])
    }

    @Test fun multipleSourcesNeedExplicitConflictResolutionAndEquivalentDecimalsCoalesce() {
        val issues = listOf(issue("""{"note":"first"}"""), issue("""{"note":"second","counterparty":"商户"}""", id = 3))
        val choices = listOf(selected(), selected(3))
        assertEquals("source_resolution_conflict", assertFailsWith<CashPolicyError> {
            prepareCashSourceResolutions(record(), issues, choices, emptyMap(), emptySet())
        }.code)
        val result = prepareCashSourceResolutions(record(), issues, choices, mapOf("note" to ""), emptySet())
        assertFalse("note" in result.patch)
        assertEquals("商户", result.patch["counterparty"])
        val decimal = listOf(issue("""{"amount":"-20.00"}"""), issue("""{"amount":"-20"}""", id = 3))
        assertEquals(CashAmount.parse("-20"), prepareCashSourceResolutions(record(), decimal, choices, emptyMap(), emptySet()).patch["amount"])
    }

    @Test fun currentIssueRevisionAndKindsAreCheckedBeforeReadingPendingContent() {
        val original = issue("[]")
        for (choice in listOf(CashSourceResolution("2", 2, "apply_source"), CashSourceResolution("1", 3, "apply_source"))) {
            assertEquals("issue_changed", assertFailsWith<CashSourceIssueChanged> {
                prepareCashSourceResolutions(record(), listOf(original), listOf(choice), emptyMap(), emptySet())
            }.code)
        }
        val retained = prepareCashSourceResolutions(record(), listOf(original), listOf(CashSourceResolution("1", 2, "keep_existing")), emptyMap(), emptySet())
        assertTrue(retained.patch.isEmpty())
        assertTrue(retained.resolutions.single().bodyDigest.matches(Regex("[0-9a-f]{64}")))
        val independent = original.copy(code = "source_identity_ambiguous")
        assertTrue(prepareCashSourceResolutions(record("state" to "draft"), listOf(independent),
            listOf(CashSourceResolution("1", 2, "create_independent")), emptyMap(), emptySet()).patch.isEmpty())
        assertFailsWith<CashPolicyError> { prepareCashSourceResolutions(record(), listOf(independent), listOf(selected()), emptyMap(), emptySet()) }
    }

    @Test fun issueReferencesRetainTheirExactPublicText() {
        for (id in listOf("01", "9223372036854775808", "9999999999999999999")) {
            assertEquals("issue_changed", assertFailsWith<CashSourceIssueChanged> {
                prepareCashSourceResolutions(record(), listOf(issue()),
                    listOf(CashSourceResolution(id, 2, "keep_existing")), emptyMap(), emptySet())
            }.code)
        }
    }

    @Test fun exactLongIssueIdentitiesAndRevisionsSurviveLocalPreparation() {
        val original = issue(id = Long.MAX_VALUE).copy(revision = Long.MAX_VALUE)
        val choice = CashSourceResolution(Long.MAX_VALUE.toString(), Long.MAX_VALUE, "keep_existing")
        val prepared = prepareCashSourceResolutions(record(), listOf(original), listOf(choice), emptyMap(), emptySet())
        assertEquals(choice, prepared.resolutions.single().choice)
        assertTrue(prepared.patch.isEmpty())
    }

    @Test fun originalNumericContainerAndSupplementaryKeyDigestsRemainFrozen() {
        val origin = json("""{"\uD800\uDC00":1e-07,"\uE000":0.0,"boolean":true,"text":"\u007f\n"}""")
        val first = issue("""{"source_payload":{"v":[1.0]}}""").copy(origin = origin)
        val second = issue("""{"source_payload":{"v":[true]}}""", id = 2).copy(origin = origin)
        val prepared = prepareCashSourceResolutions(record("state" to "draft"), listOf(first, second),
            listOf(selected(), selected(2)), emptyMap(), emptySet())
        assertEquals(listOf("b9a35370fcddef19b16ff739b0c1f58974bcfa31acdf63e84d69a2b73e33e3e5",
            "c4b3f44cf981dc7c15b0d53cbc2a94a97f61b62cffbc58fd9b9d5f8b8a6d1a88"), prepared.resolutions.map { it.bodyDigest })
        assertEquals(json("""{"source_payload":{"v":[1.0]}}"""), encodeLocalCashValue(prepared.patch))
    }

    @Test fun copyAndDigestProgrammingFailuresPropagateWithoutBecomingSourceRejections() {
        val fault = AssertionError("injected source rule fault")
        val copyFailure = CashSourceIssueResolutionRules(LocalCashRecordValues, ::decodeLocalCashValue, { "unused" }, { throw fault })
        assertSame(fault, assertFailsWith<AssertionError> { copyFailure.pendingPatch(issue("""{"note":"valid"}""")) })
        val digestFailure = CashSourceIssueResolutionRules(LocalCashRecordValues, ::decodeLocalCashValue, { throw fault }, { it })
        assertSame(fault, assertFailsWith<AssertionError> {
            digestFailure.prepare(record(), listOf(issue()), listOf(CashSourceResolution("1", 2, "keep_existing")), emptyMap(), emptySet())
        })
    }

    @Test fun acceptedPatchDoesNotAliasCallerOwnedSourceContainers() {
        val nested = mutableListOf<JsonElement>(JsonPrimitive("original"))
        val fields = mutableMapOf<String, JsonElement>("source_payload" to JsonObject(mapOf("nested" to JsonArray(nested))))
        val original = issue().copy(pendingFields = JsonObject(fields))
        val result = pendingCashSourcePatch(original)
        fields.clear()
        nested.clear()
        assertEquals(json("""{"source_payload":{"nested":["original"]}}"""), result)
    }
}
