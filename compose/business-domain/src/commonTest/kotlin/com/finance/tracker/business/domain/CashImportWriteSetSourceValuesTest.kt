package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportWriteSetSourceValuesTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val time = "2026-01-01T00:00:00Z"
    private fun existing(id: String) = ExistingCashWriteReferenceDto(id)
    private fun target(id: String) = CashWriteVersionedReferenceDto(existing(id), "1")
    private fun facts(state: String = "draft") = LedgerFactsDto(
        listOf(LedgerAccountFactDto("10", "Cash", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")),
        emptyList(), emptyList(), emptyList(),
        listOf(LedgerCashTransactionFactDto("1", "10", state, "import", null, "1", "atomic", "wechat", "record", empty, empty,
            "persisted", buildJsonObject { put("note", true) }, time, "-5.00", "CNY", "", "", array, "Source S", null,
            "consumption", "not_applicable", time, null, "", "")),
        listOf(LedgerCashComponentFactDto("11", "1", "10", "-5.00", "CNY", "0", "saved label", "saved key",
            buildJsonObject { put("original", JsonPrimitive(9007199254740993L)) }, time)),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
    private fun row(source: LedgerFactsDto): CashTransactionWriteDto {
        val r = source.cashTransactions.single(); val p = source.cashTransactionComponents.single()
        return CashTransactionWriteDto(target(r.id), CashTransactionWriteValuesDto(r.accountId, r.state, r.recordOrigin,
            r.manualTransferId, r.cashGranularity, r.sourceType, r.recordId, r.sourcePayload, r.relationMetadata,
            r.sourceFingerprint, r.manualOverrides, r.occurredAt, r.amount, r.currency, r.counterparty, r.counterpartyAccount,
            r.counterpartyAccountAttrs, r.note, r.categoryId, r.recordType, r.recordSubtype),
            listOf(CashComponentWriteDto(existing(p.id), p.accountId, p.amount, p.currency, p.ordinal, p.label, p.sourceKey, p.metadataJson)))
    }
    private fun prepare(row: CashTransactionWriteDto, source: LedgerFactsDto) =
        prepareCashImportWriteSetSourceValues(CashWriteSetDto(transactions = listOf(row)), source).transactions.single()
    private fun denied(row: CashTransactionWriteDto, source: LedgerFactsDto, path: String) {
        val failure = assertFailsWith<CashPolicyError> { prepare(row, source) }
        assertEquals("field_not_editable", failure.code)
        assertContains(failure.fields, "transactions[0].$path")
    }

    @Test fun retainedSourceFlagsAndComponentMetadataArePreservedWithoutMutatingInput() {
        val source = facts(); val input = row(source)
        val result = prepare(input, source)
        assertEquals(input.values.manualOverrides, result.values.manualOverrides)
        assertEquals("persisted", result.values.sourceFingerprint)
        assertEquals(input.components, result.components)
        assertEquals("-5.00", input.values.amount)
        assertEquals(input, row(source))
    }

    @Test fun finalJsonCannotForgeManualProtectionOrFingerprint() {
        val source = facts(); val input = row(source)
        denied(input.copy(values = input.values.copy(manualOverrides = empty)), source, "values.manual_overrides")
        denied(input.copy(values = input.values.copy(sourceFingerprint = "forged")), source, "values.source_fingerprint")
    }

    @Test fun equalAmountsRetainTrustedRepresentationAlongsideOtherSourceChanges() {
        val source = facts(); val original = row(source)
        val input = original.copy(values = original.values.copy(amount = "-5.000", note = "new source note"),
            components = original.components.map { it.copy(amount = "-5.0000") })
        val result = prepare(input, source)
        assertEquals("-5.00", result.values.amount); assertEquals("-5.00", result.components.single().amount)
        assertEquals("new source note", result.values.note)
        assertEquals("-5.000", input.values.amount); assertEquals("-5.0000", input.components.single().amount)
    }

    @Test fun newAndFinalizedPostedRowsRequireAvailableCashAccountsAndCurrencies() {
        for (account in listOf(facts().accounts.single().copy(active = false), facts().accounts.single().copy(type = "security"),
            facts().accounts.single().copy(currencies = JsonArray(listOf(JsonPrimitive("USD")))))) {
            val source = facts().copy(accounts = listOf(account))
            val input = row(source).let { it.copy(values = it.values.copy(state = "posted")) }
            val finalized = assertFailsWith<CashPolicyError> { prepare(input, source) }
            assertEquals(if (account.active && account.type == "cash") "account_currency_missing" else "account_unavailable", finalized.code)
            val created = input.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("row"), null),
                values = input.values.copy(sourceFingerprint = null, manualOverrides = empty),
                components = input.components.map { it.copy(reference = NewCashWriteReferenceDto("part"), label = "", sourceKey = "", metadataJson = empty) })
            assertFailsWith<CashPolicyError> { prepare(created, source) }
        }
    }

    @Test fun draftExplicitFinancialEditsRecheckAccountsButRetainedPostedAccountsStayValid() {
        val unavailable = facts().accounts.single().copy(active = false)
        val source = facts().copy(accounts = listOf(unavailable)); val input = row(source)
        assertEquals(input.components, prepare(input, source).components)
        for (fields in listOf(listOf("components"), listOf("account_id"), listOf("currency"))) {
            val failure = assertFailsWith<CashPolicyError> { prepare(input.copy(editedFields = fields), source) }
            assertEquals("account_unavailable", failure.code)
        }
        val posted = facts("posted").copy(accounts = listOf(unavailable.copy(type = "security", currencies = array)))
        assertEquals(row(posted).components, prepare(row(posted), posted).components)
        val missing = input.copy(values = input.values.copy(accountId = null), components = emptyList(), editedFields = listOf("account_id", "components"))
        val prepared = prepare(missing, source)
        assertNull(prepared.values.accountId); assertTrue(prepared.components.isEmpty()); assertEquals("draft", prepared.values.state)
    }

    @Test fun explicitSelectionInOneRowDoesNotRevalidateAnotherAutomaticDraft() {
        val original = facts(); val first = row(original).copy(editedFields = listOf("account_id"))
        val secondParent = original.cashTransactions.single().copy(id = "2", accountId = "20")
        val secondPart = original.cashTransactionComponents.single().copy(id = "21", cashTransactionId = "2", accountId = "20")
        val accounts = listOf(original.accounts.single(), original.accounts.single().copy(id = "20", active = false))
        val source = original.copy(accounts = accounts, cashTransactions = original.cashTransactions + secondParent,
            cashTransactionComponents = original.cashTransactionComponents + secondPart)
        val second = row(source.copy(cashTransactions = listOf(secondParent), cashTransactionComponents = listOf(secondPart)))
        val result = prepareCashImportWriteSetSourceValues(CashWriteSetDto(transactions = listOf(first, second)), source)
        assertEquals(listOf("10", "20"), result.transactions.map { it.values.accountId })
        assertEquals(listOf("draft", "draft"), result.transactions.map { it.values.state })
    }

    @Test fun everyCurrencyOfAnExplicitAccountIsCheckedAndCashLoanLendAreAllowed() {
        val source = facts(); val original = row(source); val part = original.components.single()
        val input = original.copy(values = original.values.copy(accountId = null, amount = null, currency = null, cashGranularity = "aggregate"),
            components = listOf(part, part.copy(reference = NewCashWriteReferenceDto("usd"), currency = "USD", ordinal = "1", label = "", sourceKey = "", metadataJson = empty)),
            editedFields = listOf("components"))
        assertEquals("account_currency_missing", assertFailsWith<CashPolicyError> { prepare(input, source) }.code)
        for (type in listOf("cash", "loan", "lend")) {
            val available = source.copy(accounts = source.accounts.map { it.copy(type = type, currencies = JsonArray(listOf(JsonPrimitive("CNY"), JsonPrimitive("USD")))) })
            assertEquals(input.components, prepare(input, available).components)
            assertEquals("posted", prepare(original.copy(values = original.values.copy(state = "posted")), available).values.state)
        }
    }

    @Test fun existingComponentNonfinancialValuesCannotBeRewritten() {
        val source = facts(); val input = row(source); val part = input.components.single()
        for ((field, value) in listOf("label" to part.copy(label = "new"), "source_key" to part.copy(sourceKey = "new"),
            "metadata_json" to part.copy(metadataJson = empty)))
            denied(input.copy(components = listOf(value)), source, "components[0].$field")
    }

    @Test fun newParentAndComponentsRequireOriginalWriterDefaults() {
        val source = facts()
        val input = row(source).let { it.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("row"), null),
            values = it.values.copy(sourceFingerprint = null, manualOverrides = empty),
            components = listOf(it.components.single().copy(reference = NewCashWriteReferenceDto("part"), label = "", sourceKey = "", metadataJson = empty))) }
        assertEquals(input.components, prepare(input, source).components)
        denied(input.copy(values = input.values.copy(sourceFingerprint = "provided")), source, "values.source_fingerprint")
        denied(input.copy(values = input.values.copy(manualOverrides = buildJsonObject { put("amount", true) })), source, "values.manual_overrides")
        denied(input.copy(components = input.components.map { it.copy(label = "provided") }), source, "components[0].label")
    }

    @Test fun publicFieldsAndUnknownOrDuplicateExplicitSourceEditsAreRejected() {
        val source = facts(); val input = row(source)
        for (fields in listOf(listOf("note"), listOf("category_id"), listOf("counterparty"), listOf("counterparty_account"),
            listOf("counterparty_account_attrs"), listOf("record_id"), listOf("amount", "amount"), listOf("unknown")))
            denied(input.copy(editedFields = fields), source, "edited_fields")
        val posted = facts("posted")
        denied(row(posted).copy(editedFields = listOf("source_payload")), posted, "edited_fields")
    }

    @Test fun explicitSourcePayloadEditUsesTheSameNoteAndExplanationEffects() {
        val source = facts(); val input = row(source)
        val payload = buildJsonObject { put("商品", "actual source note") }
        val fields = input.values.copy(sourcePayload = payload, note = "actual source note")
        val accepted = prepare(input.copy(values = fields, editedFields = listOf("source_payload")), source)
        assertEquals(payload, accepted.values.sourcePayload)
        assertEquals(source.cashTransactions.single().manualOverrides, accepted.values.manualOverrides)
        denied(input.copy(values = fields.copy(note = "forged source note"), editedFields = listOf("source_payload")), source, "values.note")
    }

    @Test fun changedSourceExplanationRejectsUnknownKeysAndIncorrectTypes() {
        val source = facts(); val input = row(source)
        for (value in listOf<JsonElement>(buildJsonObject { put("unrecognized", true) }, buildJsonObject { put("txn_id", 12) },
            buildJsonObject { put("_is_refund", "true") }, array))
            denied(input.copy(values = input.values.copy(relationMetadata = value)), source, "values.relation_metadata")
        assertEquals(JsonNull, prepare(input.copy(values = input.values.copy(relationMetadata = JsonNull)), source).values.relationMetadata)
    }

    @Test fun sourcePayloadAndCounterpartyAttributesKeepTheirExistingValueContracts() {
        val source = facts(); val input = row(source)
        for ((path, values) in listOf("source_payload" to input.values.copy(sourcePayload = array),
            "counterparty_account_attrs" to input.values.copy(counterpartyAccountAttrs = JsonArray(listOf(JsonPrimitive(1)))))) {
            val failure = assertFailsWith<CashPolicyError> { prepare(input.copy(values = values), source) }
            assertEquals("invalid_field", failure.code)
            assertContains(failure.fields, "transactions[0].values.$path")
        }
    }

    @Test fun payloadDepthIsCheckedBeforeRecursiveDecoding() {
        val source = facts(); val input = row(source)
        fun nested(depth: Int): JsonElement {
            var value: JsonElement = JsonPrimitive("leaf")
            repeat(depth) { value = JsonObject(mapOf("child" to value)) }
            return value
        }
        assertEquals(nested(64), prepare(input.copy(values = input.values.copy(sourcePayload = nested(64))), source).values.sourcePayload)
        for (depth in listOf(65, 4096)) {
            val failure = assertFailsWith<CashPolicyError> { prepare(input.copy(values = input.values.copy(sourcePayload = nested(depth))), source) }
            assertEquals("invalid_field", failure.code)
            assertEquals(listOf("transactions[0].values.source_payload"), failure.fields)
        }
    }
}
