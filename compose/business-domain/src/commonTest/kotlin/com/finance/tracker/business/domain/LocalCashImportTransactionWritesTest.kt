package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportTransactionWritesTest {
    private val empty = JsonObject(emptyMap())
    private val now = "2026-08-15T02:00:00Z"
    private val payload = Json.parseToJsonElement("""{"订单号":"0001","付款方式":"支付宝余额","n":9007199254740993,"d":1.2300,"e":1E+003}""")
    private fun source() = LedgerSnapshotDto("w", "9007199254740993", CASH_RULES_VERSION,
        LedgerFactsDto(listOf(LedgerAccountFactDto("10", "现金", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "9007199254740993")),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0"))
    private fun files(note: String = "source note") = listOf(ParsedImportFile("f", "f".repeat(64), "parsed",
        ParsedCashDocument("alipay", "csv", "p", listOf(ParsedCashRow(1,
            mapOf("amount" to "-12.50", "currency" to "CNY", "occurred_at" to now,
                "record_type" to "consumption", "record_subtype" to "not_applicable", "counterparty" to "商户",
                "note" to note, "source_payload" to payload), paymentMethod = "支付宝余额", providerId = "0001")),
            "owner", "支付宝", CashSourceCoverage(null, null, false, "", emptyMap(), "v"), 1, 1)))
    private fun choices(action: String = "apply", patch: JsonObject = empty) = buildJsonArray {
        add(buildJsonObject { put("row_ref", "f:r1"); put("action", action); put("patch", patch) })
    }
    private fun plan(source: LedgerSnapshotDto, files: List<ParsedImportFile>, choices: JsonArray): JsonObject {
        val selections = encodeLocalCashValue(scanLocalCashFunding(files).groups.map {
            mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to "10",
                "expected_account_revision" to JsonPrimitive(9007199254740993L))
        })
        val operations = prepareLocalCashImportOperations(files, source, selections, choices, wireValues = false)
        val prepared = LocalCashImportPreviewPreparation(source) {}.prepareOperations(operations.operations)
        val preview = buildLocalCashImportPreviewSnapshot(source, prepared.context, prepared.operations, operations.rows) { Instant.parse(now) }
        val input = prepareLocalCashImportInput(selections, choices, JsonArray(emptyList()))
        return freezeLocalCashImportPreviewPlan(completeLocalCashImportPreviewPlan(source, prepared, preview,
            emptyList(), files, input, null, operations.candidateSets)).also { assertEquals(JsonPrimitive(true), it["can_commit"]) }
    }
    private fun saved(state: String = "posted", entry: Boolean = true): LedgerSnapshotDto {
        val file = files().single(); val occurrence = CashSourceRows.sourceBatchRows(listOf(file)).rows.values.single()
        val bindings = occurrence.bindingSpecs.mapIndexed { i, key -> LedgerSourceBindingFactDto("${i + 20}", "1", key.sourceType,
            key.scopeKind, key.scopeKey, key.identityKind, key.identityKey, key.identityStrength, null, "p", empty, "1", now, now) }
        val row = LedgerCashTransactionFactDto("1", "10", state, "legacy", null, "9007199254740995", "atomic", "alipay", "saved-record",
            payload, empty, "kept-fingerprint", buildJsonObject { put("note", true) }, now, "-12.50", "CNY", "商户", "", JsonArray(emptyList()),
            "source note", null, "consumption", "not_applicable", now, null, "", "")
        return source().let { it.copy(facts = it.facts.copy(cashTransactions = listOf(row), cashSourceBindings = bindings,
            cashTransactionComponents = listOf(LedgerCashComponentFactDto("11", "1", "10", "-12.50", "CNY", "0", "kept label", "kept key", payload, now)),
            cashEntries = if (entry) listOf(LedgerCashEntryFactDto("entry", "1", "1", "9007199254740997", null, "old public", "商户", "",
                JsonArray(emptyList()), JsonArray(listOf(JsonPrimitive("note"))), false)) else emptyList(),
            cashEntryMembers = if (entry) listOf(LedgerCashEntryMemberFactDto("1", "entry", empty, JsonArray(emptyList()))) else emptyList())) }
    }

    @Test fun realComputedPreviewBuildsSourceValuesAndSeparateCWithExactVirtualReferences() {
        val source = source(); val choices = choices(patch = buildJsonObject { put("note", "public C") })
        val plan = plan(source, files(), choices); val before = plan.toString()
        val row = buildLocalCashImportTransactionWrites(source, plan, choices).single()
        val operation = plan.getValue("operations").jsonArray.single().jsonObject
        assertEquals(NewCashWriteReferenceDto(operation.getValue("preview_transaction_id").jsonPrimitive.content), row.target.reference)
        assertTrue((row.target.reference as NewCashWriteReferenceDto).ref.length > 19)
        assertNull(row.target.expectedRevision)
        assertEquals("source note", row.values.note)
        assertEquals(Json.parseToJsonElement("""{"订单号":"0001","付款方式":"支付宝余额","n":9007199254740993,"d":1.23,"e":1000.0}"""), row.values.sourcePayload)
        assertEquals("-12.50", row.values.amount); assertEquals("2026-08-15T02:00:00+00:00", row.values.occurredAt)
        assertEquals("import", row.values.recordOrigin); assertNull(row.values.sourceFingerprint); assertEquals(empty, row.values.manualOverrides)
        assertEquals(CashEntryBeforeRelationEditDto(null, buildJsonObject { put("note", "public C") }), row.entryEdit)
        assertTrue(row.editedFields.isEmpty())
        assertEquals(NewCashWriteReferenceDto(operation.getValue("preview_components").jsonArray.single().jsonObject.getValue("component_id").jsonPrimitive.content),
            row.components.single().reference)
        assertEquals("0", row.components.single().ordinal)
        assertEquals(before, plan.toString()); assertTrue(source.facts.cashTransactions.isEmpty())
        val encoded = Json { explicitNulls = true; encodeDefaults = true }.encodeToString(CashTransactionWriteDto.serializer(), row)
        for (number in listOf("9007199254740993", "1.23", "1000.0")) assertTrue(number in encoded)
    }

    @Test fun completedPlanRawJsonLexemesAreNotNormalizedAgainDuringConstruction() {
        val source = source(); val plan = plan(source, files(), choices())
        val operation = plan.getValue("operations").jsonArray.single().jsonObject
        val values = operation.getValue("values").jsonObject
        val changed = JsonObject(plan + ("operations" to JsonArray(listOf(JsonObject(operation +
            ("values" to JsonObject(values + ("source_payload" to payload))))))))
        val row = buildLocalCashImportTransactionWrites(source, changed, choices()).single()
        assertEquals(payload, row.values.sourcePayload)
        val encoded = Json { explicitNulls = true; encodeDefaults = true }.encodeToString(CashTransactionWriteDto.serializer(), row)
        for (number in listOf("9007199254740993", "1.2300", "1E+003")) assertTrue(number in encoded)
    }

    @Test fun existingIdentityRevisionsHiddenFieldsAndEntryObservationComeFromTheSameSnapshot() {
        val source = saved(); val choices = choices(patch = buildJsonObject { put("note", "new C") })
        val row = buildLocalCashImportTransactionWrites(source, plan(source, files(), choices), choices).single()
        assertEquals(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("1"), "9007199254740995"), row.target)
        assertEquals("legacy", row.values.recordOrigin); assertEquals("kept-fingerprint", row.values.sourceFingerprint)
        assertEquals(source.facts.cashTransactions.single().manualOverrides, row.values.manualOverrides)
        val part = row.components.single()
        assertEquals(ExistingCashWriteReferenceDto("11"), part.reference)
        assertEquals("kept label", part.label); assertEquals("kept key", part.sourceKey); assertEquals(payload, part.metadataJson)
        assertEquals(CashEntryBeforeRelationEditDto(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("entry"), "9007199254740997"),
            buildJsonObject { put("note", "new C") }), row.entryEdit)
        assertEquals("source note", row.values.note); assertTrue(row.editedFields.isEmpty())
    }

    @Test fun explicitDraftFinancialPatchIsDistinctFromAccountDefaultsAndPublicEdits() {
        val source = source()
        val patch = buildJsonObject {
            put("account_id", "10"); put("note", "C"); put("amount", "-13.25")
            put("components", buildJsonArray { add(buildJsonObject {
                put("account_id", "10"); put("amount", "-13.25"); put("currency", "CNY"); put("ordinal", 0)
            }) })
        }
        val choices = choices("defer", patch)
        val row = buildLocalCashImportTransactionWrites(source, plan(source, files(), choices), choices).single()
        assertEquals("draft", row.values.state); assertEquals("-13.25", row.values.amount)
        assertEquals(setOf("account_id", "amount", "components"), row.editedFields.toSet())
        assertEquals(setOf("note"), row.entryEdit!!.patch.keys)
        val defaultChoices = choices("defer")
        assertTrue(buildLocalCashImportTransactionWrites(source, plan(source, files(), defaultChoices), defaultChoices).single().editedFields.isEmpty())
    }

    @Test fun postedDeferAndKeepRetainSourceAndHaveNoCOrAdoptedPatch() {
        val source = saved().let { it.copy(facts = it.facts.copy(cashTransactions = it.facts.cashTransactions.map { tx -> tx.copy(manualOverrides = empty) })) }
        for (action in listOf("defer", "keep_existing")) {
            val choices = choices(action)
            val row = buildLocalCashImportTransactionWrites(source, plan(source, files("new source"), choices), choices).single()
            assertEquals("source note", row.values.note); assertEquals(empty, row.values.relationMetadata)
            assertNull(row.entryEdit); assertTrue(row.editedFields.isEmpty())
        }
    }

    @Test fun draftWithoutExistingEntryKeepsExplicitNullObservationAndParentRevision() {
        val source = saved("draft", false); val choices = choices("defer", buildJsonObject { put("note", "C") })
        val row = buildLocalCashImportTransactionWrites(source, plan(source, files(), choices), choices).single()
        assertEquals("9007199254740995", row.target.expectedRevision)
        assertNotNull(row.entryEdit); assertNull(row.entryEdit!!.entry)
    }

    @Test fun reorderedRetainedComponentAndReplacementUseTheirOwnIdentityAndHiddenFields() {
        val source = saved("draft", false).let { original ->
            original.copy(facts = original.facts.copy(
                cashTransactions = original.facts.cashTransactions.map { it.copy(accountId = null, cashGranularity = "aggregate") },
                cashTransactionComponents = listOf(
                    original.facts.cashTransactionComponents.single().copy(amount = "-5.00"),
                    LedgerCashComponentFactDto("12", "1", "10", "-7.50", "CNY", "1", "second label", "second key", payload, now))))
        }
        val patch = buildJsonObject {
            put("account_id", JsonNull); put("cash_granularity", "aggregate")
            put("components", Json.parseToJsonElement("""[
                {"component_id":"12","account_id":"10","amount":"-7.50","currency":"CNY","ordinal":0},
                {"account_id":"10","amount":"-5.00","currency":"CNY","ordinal":1}]
            """))
        }
        val choices = choices("defer", patch)
        val row = buildLocalCashImportTransactionWrites(source, plan(source, files(), choices), choices).single()
        val kept = row.components[0]; val replacement = row.components[1]
        assertEquals(ExistingCashWriteReferenceDto("12"), kept.reference); assertEquals("0", kept.ordinal)
        assertEquals("second label", kept.label); assertEquals("second key", kept.sourceKey); assertEquals(payload, kept.metadataJson)
        assertTrue(replacement.reference is NewCashWriteReferenceDto); assertEquals("1", replacement.ordinal)
        assertEquals("", replacement.label); assertEquals("", replacement.sourceKey); assertEquals(empty, replacement.metadataJson)
        assertFalse(row.components.any { it.reference == ExistingCashWriteReferenceDto("11") })
        prepareCashImportWriteSetSourceValues(CashWriteSetDto(transactions = listOf(row)), source.facts)
    }

    @Test fun explicitChoiceOnANonRepresentativeDuplicateRowIsKept() {
        val source = source(); val first = files().single()
        val files = listOf(first, first.copy(fileRef = "second", digest = "a".repeat(64)))
        val initial = plan(source, files, JsonArray(emptyList()))
        val refs = initial.getValue("operations").jsonArray.single().jsonObject.getValue("row_refs").jsonArray
        assertEquals(2, refs.size)
        val choices = buildJsonArray { add(buildJsonObject {
            put("row_ref", refs.last()); put("action", "defer"); put("patch", buildJsonObject { put("account_id", "10") })
        }) }
        val plan = plan(source, files, choices)
        val operation = plan.getValue("operations").jsonArray.single().jsonObject
        assertNotEquals(refs.last(), operation.getValue("row_refs").jsonArray.first())
        val row = buildLocalCashImportTransactionWrites(source, plan, choices).single()
        assertEquals("draft", row.values.state); assertEquals(listOf("account_id"), row.editedFields)
    }

    @Test fun incompleteOrStaleInternalPlanFailsWithoutDroppingTheOperation() {
        val source = saved(); val choices = choices(); val plan = plan(source, files(), choices)
        assertFailsWith<IllegalStateException> { buildLocalCashImportTransactionWrites(source, JsonObject(plan + ("can_commit" to JsonPrimitive(false))), choices) }
        val newer = source.copy(facts = source.facts.copy(cashTransactions = source.facts.cashTransactions.map { it.copy(rowRevision = "9007199254740996") }))
        assertFailsWith<IllegalStateException> { buildLocalCashImportTransactionWrites(newer, plan, choices) }
    }
}
