package com.finance.tracker.business.contracts

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CashWriteSetSerializationTest {
    @Test
    fun splitChoicesPreserveFalseAndTheOldEntryDependencyAfterRetirement() {
        val old = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("retired-entry"), "9223372036854775807")
        for (inherit in listOf(false, true)) {
            val changes = CashWriteSetDto(entryDependencies = listOf(old), entrySplitChoices = listOf(CashEntrySplitChoiceDto(old, inherit)))
            val text = Json.encodeToString(CashWriteSetDto.serializer(), changes)
            val choice = Json.parseToJsonElement(text).jsonObject.getValue("entry_split_choices").jsonArray.single().jsonObject
            assertEquals(JsonPrimitive(inherit), choice["inherit_common_fields"])
            assertEquals(JsonPrimitive("9223372036854775807"), choice.getValue("entry").jsonObject["expected_revision"])
            assertEquals(changes, Json.decodeFromString<CashWriteSetDto>(text))
            assertFalse("split_inherit" in choice)
        }
        assertTrue(Json.decodeFromString<CashWriteSetDto>("{}").entrySplitChoices.isEmpty())
    }

    @Test
    fun splitChoiceRequiresAnExplicitBooleanInsteadOfAFinalMarkerOrDefault() {
        val entry = """{"reference":{"type":"existing","id":"entry"},"expected_revision":"1"}"""
        for (suffix in listOf("", ",\"inherit_common_fields\":null", ",\"inherit_common_fields\":1", ",\"inherit_common_fields\":[]", ",\"inherit_common_fields\":{}",
            ",\"inherit_common_fields\":\"true\"", ",\"split_inherit\":true")) {
            val text = "{\"entry\":$entry$suffix}"
            assertFailsWith<SerializationException> {
                Json.decodeFromString<CashEntrySplitChoiceDto>(text)
            }
            assertFailsWith<SerializationException> {
                Json.decodeFromJsonElement<CashEntrySplitChoiceDto>(Json.parseToJsonElement(text))
            }
        }
    }

    @Test
    fun referencesKeepExistingAndNewIdentitiesDistinctWithoutNumericConversion() {
        val references: List<CashWriteReferenceDto> = listOf(
            ExistingCashWriteReferenceDto("9007199254740993"),
            NewCashWriteReferenceDto("340282366920938463481821351505477763071"),
        )
        for (reference in references) {
            val text = Json.encodeToString(CashWriteReferenceDto.serializer(), reference)
            assertEquals(reference, Json.decodeFromString(CashWriteReferenceDto.serializer(), text))
            assertTrue(Json.parseToJsonElement(text).jsonObject.values.all { it.jsonPrimitive.isString })
        }
        assertFalse(Json.encodeToString(CashWriteReferenceDto.serializer(), references[0]) ==
            Json.encodeToString(CashWriteReferenceDto.serializer(), NewCashWriteReferenceDto("9007199254740993")))
    }

    @Test
    fun requestVersionsRemainExactStringsAndDoNotContainServerAuditFields() {
        val request = CashWriteSetRequestDto("9007199254740993", "cash-rules-v1", CashWriteSetDto())
        val encoded = Json.encodeToString(CashWriteSetRequestDto.serializer(), request)
        val fields = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(JsonPrimitive("9007199254740993"), fields["base_ledger_seq"])
        assertEquals(request, Json.decodeFromString(CashWriteSetRequestDto.serializer(), encoded))
        for (key in listOf("workspace_id", "user_id", "created_at", "ledger_seq", "summary", "plan_id", "idempotency_key")) {
            assertFalse(key in fields, key)
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<CashWriteSetRequestDto>(encoded.dropLast(1) + ",\"workspace_id\":\"other\"}")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<CashWriteSetRequestDto>(encoded.replace("\"9007199254740993\"", "9007199254740993"))
        }
    }

    @Test
    fun sourceBindingPayloadKeepsJsonNumberLexemesAndExpectedRevision() {
        val proof = Json.parseToJsonElement("""{"values":[9007199254740993,1e0,-0,1.2300,1e400,true,null,"1e0"]}""")
        val binding = CashSourceBindingWriteDto(
            CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("2"), "9223372036854775807"),
            ExistingCashWriteReferenceDto("9007199254740993"),
            "wechat", "account", "scope", "provider", "record-1", "strong", "evidence", "1", proof,
        )
        val encoded = Json.encodeToString(CashSourceBindingWriteDto.serializer(), binding)
        val fields = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(proof, fields["coverage"])
        assertEquals(binding, Json.decodeFromString(CashSourceBindingWriteDto.serializer(), encoded))
        assertFalse("created_by" in fields)
        assertFalse("revision" in fields)
        assertFailsWith<SerializationException> {
            Json.decodeFromString<CashSourceBindingWriteDto>(encoded.dropLast(1) + ",\"created_at\":\"1900-01-01T00:00:00Z\"}")
        }
    }

    @Test
    fun targetsAndDependencyProofsDoNotInventCurrentRevisions() {
        val create = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("source-row-1"), null)
        val update = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("1"), "9007199254740993")
        val dependency = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("7"), "9223372036854775807")
        assertEquals(create, Json.decodeFromString<CashWriteVersionedReferenceDto>(Json.encodeToString(CashWriteVersionedReferenceDto.serializer(), create)))
        assertEquals(update, Json.decodeFromString<CashWriteVersionedReferenceDto>(Json.encodeToString(CashWriteVersionedReferenceDto.serializer(), update)))
        assertEquals(dependency, Json.decodeFromString<CashWriteVersionedReferenceDto>(Json.encodeToString(CashWriteVersionedReferenceDto.serializer(), dependency)))
        assertFailsWith<SerializationException> {
            Json.decodeFromString<CashWriteReferenceDto>("""{"type":"existing"}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<CashWriteReferenceDto>("""{"type":"unknown","id":"1"}""")
        }
    }

    @Test
    fun completeFinalDeltaPreservesEveryEntityFamilyAndParentReferences() {
        val transaction = NewCashWriteReferenceDto("row-1")
        val component = NewCashWriteReferenceDto("row-1-component-0")
        val created = CashWriteVersionedReferenceDto(transaction, null)
        val dependency = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("9007199254740993"), "7")
        val proof = Json.parseToJsonElement("""{"number":1.2300,"large":9007199254740993}""")
        val empty = Json.parseToJsonElement("{}")
        val amount = "-12345678901234567890.123456789012345678"
        val changes = CashWriteSetDto(
            transactions = listOf(CashTransactionWriteDto(created,
                CashTransactionWriteValuesDto("1", "posted", "import", null, "transaction", "wechat", "record-1",
                    proof, empty, "fingerprint", empty, "1900-01-01T00:00:00.000001Z", amount, "CNY", "merchant", "", empty, "note", null,
                    "consumption", "not_applicable"),
                listOf(CashComponentWriteDto(component, "1", amount, "CNY", "0", "", "part-1", proof)), editedFields = listOf("source_payload"))),
            deletedTransactions = listOf(CashTransactionDeleteDto(dependency, "merge_draft")),
            relations = listOf(CashRelationWriteDto(
                CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("relation-1"), null), "payment_mirror", created, dependency,
                component, ExistingCashWriteReferenceDto("5"), created, component, "primary", "secondary", "confirmed", true,
                "group-1", "user", proof, "1.2300", "rule-1", "", listOf(CashRelationCandidateWriteDto(dependency, null, proof, "valid")))),
            issues = listOf(CashIssueWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("issue-1"), null), created,
                "issue-key", "field_conflict", "note", proof, empty, proof, listOf(dependency))),
            resolvedIssues = listOf(dependency),
            entries = listOf(CashEntryWriteDto(created, null, "note", "merchant", "", empty, listOf("note"),
                listOf(CashEntryMemberWriteDto(created, proof, listOf("note"))), editedFields = listOf("note", "category_id"))),
            entryDependencies = listOf(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("entry-old"), "3")),
            sourceBindings = listOf(CashSourceBindingWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("binding-1"), null),
                transaction, "wechat", "account", "scope", "provider", "record-1", "strong", null, "1", proof)),
            sourceEmptyPages = listOf(LedgerSourceEmptyPageFactDto("ccb", "1", "document", 2, proof)),
            statementAccountMappings = listOf(CashStatementMappingWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("mapping-1"), null),
                "wechat", "provider", "source-account", "1")),
        )
        val request = CashWriteSetRequestDto("9007199254740993", "cash-rules-v1", changes)
        val text = Json.encodeToString(CashWriteSetRequestDto.serializer(), request)
        assertEquals(request, Json.decodeFromString<CashWriteSetRequestDto>(text))
        assertEquals(request, Json.decodeFromJsonElement(CashWriteSetRequestDto.serializer(), Json.encodeToJsonElement(CashWriteSetRequestDto.serializer(), request)))
        val values = Json.parseToJsonElement(Json.encodeToString(CashTransactionWriteValuesDto.serializer(), changes.transactions.single().values)).jsonObject
        assertEquals(JsonPrimitive(amount), values["amount"])
        assertEquals(proof, values["source_payload"])
        val delta = Json.parseToJsonElement(text).jsonObject.getValue("changes").jsonObject
        assertEquals(listOf("source_payload"), delta.getValue("transactions").jsonArray.single().jsonObject.getValue("edited_fields").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("note", "category_id"), delta.getValue("entries").jsonArray.single().jsonObject.getValue("edited_fields").jsonArray.map { it.jsonPrimitive.content })
        for (audit in listOf("id", "workspace_id", "row_revision", "created_at", "updated_at", "deleted_by")) assertFalse(audit in values)
    }

    @Test
    fun sourceIssueActionsKeepTheirOwnExactRevisionAndOnlyMergeHasATargetDependency() {
        val issue = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("9007199254740993"), "9223372036854775807")
        val target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("9007199254740995"), "9007199254740999")
        val actions: List<CashIssueResolutionDto> = listOf(ApplySourceIssueResolutionDto(issue), KeepExistingIssueResolutionDto(issue),
            CreateIndependentIssueResolutionDto(issue), MergeIntoIssueResolutionDto(issue, target))
        for ((index, action) in actions.withIndex()) {
            val encoded = Json.encodeToString(CashIssueResolutionDto.serializer(), action)
            val fields = Json.parseToJsonElement(encoded).jsonObject
            assertEquals(listOf("apply_source", "keep_existing", "create_independent", "merge_into")[index], fields.getValue("type").jsonPrimitive.content)
            assertEquals(action, Json.decodeFromString<CashIssueResolutionDto>(encoded))
            assertEquals(index == 3, "target_transaction" in fields)
            assertFalse("body_digest" in fields)
            assertFalse("pending_fields" in fields)
        }
        val changes = CashWriteSetDto(issueResolutions = actions)
        val encoded = Json.encodeToString(CashWriteSetDto.serializer(), changes)
        assertEquals(changes, Json.decodeFromString<CashWriteSetDto>(encoded))
        assertEquals(4, Json.parseToJsonElement(encoded).jsonObject.getValue("issue_resolutions").jsonArray.size)
    }

    @Test
    fun sourceIssueActionsCannotOmitAMergeTargetOrSupplyAuthoritativeBodies() {
        val issue = """{"reference":{"type":"existing","id":"1"},"expected_revision":"9007199254740993"}"""
        for (text in listOf(
            """{"type":"merge_into","issue":$issue}""",
            """{"type":"keep_existing","issue":$issue,"target_transaction":$issue}""",
            """{"type":"apply_source","issue":$issue,"pending_fields":{"amount":"1"}}""",
            """{"type":"unknown","issue":$issue}""",
        )) assertFailsWith<SerializationException> { Json.decodeFromString<CashIssueResolutionDto>(text) }
    }

    @Test
    fun explicitEditingAndAutomaticResultsRemainDistinctEvenWhenFinalFlagsAreTheSame() {
        val transaction = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("1"), "1")
        val empty = Json.parseToJsonElement("[]")
        val automatic = CashEntryWriteDto(transaction, null, "", "", "", empty, listOf("note"), emptyList())
        val edited = automatic.copy(editedFields = listOf("note", "category_id"))
        assertEquals(automatic.manualFields, edited.manualFields)
        assertEquals(automatic.note, edited.note)
        val automaticJson = Json.encodeToString(CashEntryWriteDto.serializer(), automatic)
        val editedJson = Json.encodeToString(CashEntryWriteDto.serializer(), edited)
        assertFalse(automaticJson == editedJson)
        assertEquals(automatic, Json.decodeFromString<CashEntryWriteDto>(automaticJson))
        assertEquals(edited, Json.decodeFromString<CashEntryWriteDto>(editedJson))
        assertEquals(JsonPrimitive(""), Json.parseToJsonElement(editedJson).jsonObject.getValue("note"))
    }
}
