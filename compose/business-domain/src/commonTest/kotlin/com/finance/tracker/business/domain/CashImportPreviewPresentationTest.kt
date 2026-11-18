package com.finance.tracker.business.domain

import kotlin.test.*
import kotlin.time.Instant

class CashImportPreviewPresentationTest {
    private val entries = CashImportEntryChoiceRules(LocalCashRecordValues, ::localCashImportDigest)
    private val edgeOrder = Comparator<String> { a, b -> a.toLong().compareTo(b.toLong()) }
    private fun virtualPart(text: String): Boolean {
        if (text.isEmpty() || text.any { it !in '0'..'9' }) return false
        val digits = text.trimStart('0').ifEmpty { "0" }
        return digits.length > 19 || digits.length == 19 && digits >= "9223372036854775808"
    }
    private fun rules() = CashImportPreviewPresentationRules<Long, Instant>(LocalCashRecordValues,
        { it as? Long }, ::virtualPart, entries, edgeOrder)
    private fun preview(): CashImportPreviewSnapshot<Long, Instant> = CashImportPreviewSnapshotTest().snapshot().also { preview ->
        for ((id, fact) in preview.facts) preview.values.getValue(id).putAll(mapOf(
            "state" to fact.state, "record_origin" to fact.recordOrigin, "source_type" to fact.sourceType,
            "record_id" to fact.recordId, "amount" to fact.amount, "currency" to fact.currency,
            "note" to fact.note, "counterparty" to fact.counterparty, "record_type" to fact.recordType))
    }
    private fun build(preview: CashImportPreviewSnapshot<Long, Instant>) = preview.build()

    @Test fun unchangedRecordsOnlyAppearWhenTouchedAndKeepDraftOrderingAndStableEntryTargets() {
        val preview = preview()
        val file = ParsedImportFile("file", "digest", "parsed", filename = "账单.csv", diagnostic = mapOf("message" to "detail"))
        val before = preview.values.mapValues { copyLocalCashValue(it.value) }
        val result = rules().present(listOf(file), preview, build(preview), emptyList(), emptyList(),
            mapOf("10" to mapOf("name" to "账户")), entryParentIds = setOf("1", "2"))
        @Suppress("UNCHECKED_CAST") val items = result["items"] as List<Map<String, Any?>>
        assertEquals(listOf("draft", "posted"), items.map { it["anticipated_state"] })
        assertEquals(listOf("pending", "confirmed"), items.map { it["confirmation_status"] })
        assertEquals(2, (result["entry_targets"] as Map<*, *>).size)
        assertEquals(2, (result["monthly_source"] as List<*>).size)
        assertEquals(true, result["can_commit"])
        @Suppress("UNCHECKED_CAST") val publicFile = (result["file_results"] as List<Map<String, Any?>>).single()
        assertEquals("账单.csv", publicFile["display_name"])
        assertEquals("ready", publicFile["status"])
        assertFalse("source_type" in publicFile)
        assertFalse("client_file_ref" in publicFile)
        assertEquals(before, preview.values)
        assertEquals(emptyList<Any?>(), rules().present(emptyList(), preview, build(preview), emptyList(), emptyList(), emptyMap())["items"])
    }

    @Test fun frozenMemberFieldsStripOnlyVirtualComponentIdsAndChoicesHaveStableReferences() {
        val preview = preview()
        preview.values.getValue("2")["components"] = listOf(
            mapOf("component_id" to "9223372036854775807", "ordinal" to 0L),
            mapOf("component_id" to "9223372036854775808", "ordinal" to 1L),
            mapOf("component_id" to "18446744073709551616", "ordinal" to 2L),
            mapOf("component_id" to "not-an-id", "ordinal" to 3L))
        val result = rules().present(emptyList(), preview, build(preview), emptyList(), emptyList(), emptyMap(),
            entryParentIds = setOf("2"))
        @Suppress("UNCHECKED_CAST") val fields = ((result["detail_values"] as Map<*, *>)["2"] as Map<*, *>)["components"] as List<Map<String, Any?>>
        assertEquals("9223372036854775807", fields[0]["component_id"])
        assertFalse("component_id" in fields[1])
        assertFalse("component_id" in fields[2])
        assertEquals("not-an-id", fields[3]["component_id"])
        val again = rules().present(emptyList(), preview, build(preview), emptyList(), emptyList(), emptyMap(), entryParentIds = setOf("2"))
        assertEquals(result["entry_targets"], again["entry_targets"])
        assertEquals(4, (preview.values.getValue("2")["components"] as List<*>).size)
    }

    @Test fun fileResultsKeepPasswordFailureStatusAndCopyDiagnostics() {
        val diagnostic = linkedMapOf<String, Any?>("messages" to mutableListOf("cause"))
        val file = ParsedImportFile("file", "digest", "needs_password", errorCode = "password_required", diagnostic = diagnostic)
        val view = cashImportFileView(file, ::copyLocalCashValue)
        assertEquals("password_required", view["status"])
        assertEquals(true, view["can_retry"])
        assertNull(view["recognized_row_count"])
        @Suppress("UNCHECKED_CAST") val copied = (view["diagnostic"] as Map<*, *>)["messages"] as MutableList<String>
        copied += "other"
        assertEquals(listOf("cause"), diagnostic["messages"])
    }
}
