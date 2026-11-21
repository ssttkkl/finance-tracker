package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashWriteSetTransportTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val time = "2026-11-14T00:00:00Z"
    private val coverage = buildJsonObject {
        put("start", "2026-11-01T00:00:00Z"); put("end", "2026-12-01T00:00:00Z"); put("complete", true)
        put("evidence", ""); put("filters", JsonObject(emptyMap())); put("strategy_version", "fixture-v1")
    }
    private fun facts(): LedgerFactsDto {
        val transaction = LedgerCashTransactionFactDto("1", "10", "posted", "import", null, "7", "atomic", "ccb_debit",
            "record", empty, empty, null, empty, time, "-5.00", "CNY", "P", "", array, "S", null, "consumption",
            "not_applicable", time, null, "", "")
        val component = LedgerCashComponentFactDto("11", "1", "10", "-5.00", "CNY", "0", "", "", empty, time)
        val binding = LedgerSourceBindingFactDto("21", "1", "ccb_debit", "file_local", "scope", "file_occurrence", "occ",
            "file_local", null, "fixture-v1", coverage, "2", time, time)
        val entry = LedgerCashEntryFactDto("31", "1", "1", "3", null, "S", "P", "", array,
            JsonArray(listOf(JsonPrimitive("note"))), false)
        val member = LedgerCashEntryMemberFactDto("1", "31", empty, array)
        return LedgerFactsDto(listOf(LedgerAccountFactDto("10", "10", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")),
            emptyList(), emptyList(), emptyList(), listOf(transaction), listOf(component), listOf(binding), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), listOf(entry), listOf(member), emptyList(), "0")
    }
    private fun transactionWrite(target: CashWriteVersionedReferenceDto = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("1"), "7")) =
        CashTransactionWriteDto(target, CashTransactionWriteValuesDto("10", "posted", "import", null, "atomic", "ccb_debit",
            "record", empty, empty, null, empty, time, "-5.00", "CNY", "P", "", array, "S", null, "consumption", "not_applicable"),
            listOf(CashComponentWriteDto(ExistingCashWriteReferenceDto("11"), "10", "-5.00", "CNY", "0", "", "", empty)))
    private fun entryWrite(target: CashWriteVersionedReferenceDto) = CashEntryWriteDto(
        target, null, "S", "P", "", array, listOf("note"),
        listOf(CashEntryMemberWriteDto(target, empty, emptyList())))
    private fun bindingWrite(index: Int, coverageValue: JsonElement) = CashSourceBindingWriteDto(
        CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("binding$index"), null),
        NewCashWriteReferenceDto("row$index"), "ccb_debit", "file_local", "scope", "file_occurrence", "occ$index",
        "file_local", null, "fixture-v1", coverageValue)

    @Test fun replayOfAnUnchangedEntryExpandsToTheFullLogicalCommand() {
        val request = CashWriteSetRequestDto("5", CASH_RULES_VERSION, CashWriteSetDto(
            transactions = listOf(transactionWrite()), entries = listOf(entryWrite(transactionWrite().target))))
        val compact = compactCashWriteSetTransport(request, facts())!!
        assertEquals(2, compact.getValue("command_version").jsonPrimitive.int)
        val replay = compact.getValue("replay_groups").jsonArray.single().jsonObject
        assertEquals(0, replay.getValue("entry").jsonObject.getValue("index").jsonPrimitive.int)
        assertEquals(0, replay.getValue("transactions").jsonArray.single().jsonObject.getValue("index").jsonPrimitive.int)
        val expanded = Json.decodeFromJsonElement<CashWriteSetRequestDto>(expandCashWriteSetTransport(compact, facts()))
        assertEquals(request.baseLedgerSeq, expanded.baseLedgerSeq)
        assertEquals(1, expanded.changes.transactions.size)
        assertEquals(1, expanded.changes.entries.size)
        assertEquals(transactionWrite().values, expanded.changes.transactions.single().values)
        assertEquals(entryWrite(transactionWrite().target).members.single().standaloneValues,
            expanded.changes.entries.single().members.single().standaloneValues)
    }

    @Test fun repeatedCoverageValuesAreStoredOnceAndExactNumbersSurvive() {
        val repeated = buildJsonObject { coverage.forEach { (key, value) -> put(key, value) }; put("page_total", JsonUnquotedLiteral("1.2300")) }
        val request = CashWriteSetRequestDto("5", CASH_RULES_VERSION, CashWriteSetDto(
            transactions = listOf(transactionWrite(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("row0"), null))),
            sourceBindings = listOf(bindingWrite(0, repeated), bindingWrite(1, repeated))))
        val compact = compactCashWriteSetTransport(request, facts())!!
        assertEquals(1, compact.getValue("coverage_table").jsonArray.size)
        val expandedText = expandCashWriteSetTransport(compact, facts()).toString()
        assertTrue("1.2300" in expandedText)
        assertFalse("coverage_ref" in expandedText)
        assertEquals(repeated, Json.parseToJsonElement(expandedText).jsonObject.getValue("changes").jsonObject
            .getValue("source_bindings").jsonArray.first().jsonObject.getValue("coverage"))
    }

    @Test fun aFrozenTransportMustExpandToTheExactLogicalRequest() {
        val request = CashWriteSetRequestDto("5", CASH_RULES_VERSION, CashWriteSetDto(
            transactions = listOf(transactionWrite()), entries = listOf(entryWrite(transactionWrite().target))))
        val compact = compactCashWriteSetTransport(request, facts())!!
        verifyCashWriteSetTransport(request, compact)
        val tampered = JsonObject(compact + ("replay_groups" to JsonArray(compact.getValue("replay_groups").jsonArray.map {
            val group = it.jsonObject
            JsonObject(group + ("transactions" to JsonArray(group.getValue("transactions").jsonArray.map { item ->
                JsonObject(item.jsonObject + ("id" to JsonPrimitive("other")))
            })))
        })))
        assertFails { verifyCashWriteSetTransport(request, tampered) }
        val other = request.copy(changes = request.changes.copy(transactions = request.changes.transactions.map {
            it.copy(values = it.values.copy(note = "different"))
        }))
        assertFails { verifyCashWriteSetTransport(other, compact) }
    }

    @Test fun changedOrDependentRowsRemainFullCommands() {
        val changedPayload = transactionWrite().copy(values = transactionWrite().values.copy(note = "changed"))
        val changed = CashWriteSetRequestDto("5", CASH_RULES_VERSION, CashWriteSetDto(
            transactions = listOf(changedPayload), entries = listOf(entryWrite(changedPayload.target))))
        assertNull(compactCashWriteSetTransport(changed, facts())?.get("replay_groups"))
        val dependent = CashWriteSetRequestDto("5", CASH_RULES_VERSION, CashWriteSetDto(
            transactions = listOf(transactionWrite()), entries = listOf(entryWrite(transactionWrite().target)),
            resolvedIssues = listOf(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("99"), "1"))))
        assertEquals(1, buildCashWriteSetReplayGroups(dependent, facts()).size)
    }

    @Test fun aSmallDictionaryReferencedManyTimesIsChargedBeforeExpansion() {
        val large = buildJsonObject { coverage.forEach { (key, value) -> put(key, value) }
            put("page_fingerprints", buildJsonObject { put("1", JsonPrimitive("f".repeat(700_000))) }) }
        val bindings = (0 until 120).map { bindingWrite(it, large) }
        val request = CashWriteSetRequestDto("5", CASH_RULES_VERSION, CashWriteSetDto(sourceBindings = bindings))
        val compact = compactCashWriteSetTransport(request, null)!!
        assertEquals(1, compact.getValue("coverage_table").jsonArray.size)
        assertFailsWith<CashWriteSetPayloadTooLarge> { expandCashWriteSetTransport(compact, null) }
    }

    @Test fun aForgedOrStaleProofIsRejectedBeforeExpansion() {
        val compact = compactCashWriteSetTransport(CashWriteSetRequestDto("5", CASH_RULES_VERSION, CashWriteSetDto(
            transactions = listOf(transactionWrite()), entries = listOf(entryWrite(transactionWrite().target)))), facts())!!
        val forged = JsonObject(compact + ("replay_groups" to JsonArray(compact.getValue("replay_groups").jsonArray.map {
            JsonObject(it.jsonObject + ("digest" to JsonPrimitive("0".repeat(64))))
        })))
        assertFailsWith<CashWriteSetReplayMismatch> { expandCashWriteSetTransport(forged, facts()) }
        val staleFacts = facts().let { it.copy(cashTransactions = it.cashTransactions.map { row -> row.copy(rowRevision = "8") }) }
        assertFailsWith<CashWriteSetReplayMismatch> { expandCashWriteSetTransport(compact, staleFacts) }
    }
}
