package com.finance.tracker.presentation

import com.finance.tracker.domain.*

internal val cashTransferFinancialFields = listOf("amount", "currency", "account_id", "occurred_at")
internal data class CashTransferEntryMembers(val outgoing: CashCurrentRecord, val incoming: CashCurrentRecord)
internal fun cashTransferEntryMembers(root: CashCurrentRecord, members: List<CashCurrentRecord>): CashTransferEntryMembers? {
    val entry = root.entry ?: return null
    val identity = root.fields.fields["manual_transfer_id"].cashText()?.takeIf { it.isNotBlank() } ?: return null
    if (root.recordOrigin != "manual_transfer" || root.state != "posted" || !entry.supportsTransferEdit ||
        !entry.editableFields.containsAll(cashTransferFinancialFields) || members.size != 2 || members.map { it.transactionId }.distinct().size != 2 ||
        members.singleOrNull { it.transactionId == root.transactionId }?.rowRevision != root.rowRevision) return null
    if (members.any { member -> member.recordOrigin != "manual_transfer" || member.state != "posted" || member.entry?.supportsTransferEdit != true ||
        member.entry.entryId != entry.entryId || member.entry.revision != entry.revision ||
        !member.entry.editableFields.containsAll(cashTransferFinancialFields) || member.fields.fields["manual_transfer_id"].cashText() != identity }) return null
    fun amount(member: CashCurrentRecord): String = member.fields.fields["amount"].cashText().orEmpty()
    if (members.any { !isExactDecimalString(amount(it)) }) return null
    val outgoing = members.singleOrNull { displayDecimalSign(amount(it)) == -1 } ?: return null
    val incoming = members.singleOrNull { displayDecimalSign(amount(it)) == 1 } ?: return null
    if (outgoing.fields.fields["currency"] == incoming.fields.fields["currency"] &&
        displayDecimalCompare(amount(outgoing).removePrefix("-"), amount(incoming).removePrefix("+")) != 0) return null
    return CashTransferEntryMembers(outgoing, incoming)
}
internal fun cashTransferEntryValues(record: CashCurrentRecord): Map<String, String> = cashDraftInitialValues(
    record.copy(editableFields = cashTransferFinancialFields)).let { values -> values + ("amount" to values["amount"].orEmpty().removePrefix("-").removePrefix("+")) }
internal fun cashTransferIncomingValues(outgoing: Map<String, String>, incoming: Map<String, String>,
    initialOutgoing: Map<String, String>, initialIncoming: Map<String, String>): Map<String, String> {
    if (outgoing["currency"] != incoming["currency"]) return incoming
    val amount = if (outgoing["amount"] != initialOutgoing["amount"] || initialOutgoing["currency"] != initialIncoming["currency"])
        outgoing["amount"].orEmpty() else initialIncoming["amount"].orEmpty()
    return incoming + ("amount" to amount)
}
internal fun cashTransferEntryRequest(root: CashCurrentRecord, members: List<CashCurrentRecord>, commonChanges: Map<String, String>,
    outgoing: Map<String, String>, incoming: Map<String, String>, timeZoneId: String): CashMaintenanceRequest {
    fun invalid(code: String): Nothing = throw DomainFailure(code, 0, FailureCategory.RECOVERABLE)
    val pair = cashTransferEntryMembers(root, members) ?: invalid("field_not_editable")
    val common = cashEntryRequest(root, commonChanges, timeZoneId)
    val effectiveIncoming = cashTransferIncomingValues(outgoing, incoming, cashTransferEntryValues(pair.outgoing), cashTransferEntryValues(pair.incoming))
    fun patch(record: CashCurrentRecord, values: Map<String, String>, negative: Boolean): CashMemberPatch {
        if (values.keys != cashTransferFinancialFields.toSet()) invalid("field_not_editable")
        val magnitude = values["amount"].orEmpty().removePrefix("+")
        if (!Regex("\\d+(?:\\.\\d+)?").matches(magnitude) || magnitude.none { it in '1'..'9' }) invalid("invalid_amount")
        if (values["account_id"].isNullOrEmpty()) invalid("invalid_account")
        if (values["occurred_at"].isNullOrEmpty()) invalid("invalid_datetime")
        val initial = cashTransferEntryValues(record)
        val changes = values.filter { (field, value) -> initial[field] != value }.toMutableMap()
        if ("amount" in changes) changes["amount"] = if (negative) "-$magnitude" else magnitude
        return CashMemberPatch(record.transactionId, cashRecordFieldPatch(cashTransferFinancialFields, changes, timeZoneId))
    }
    val patches = listOf(patch(pair.outgoing, outgoing, true), patch(pair.incoming, effectiveIncoming, false))
    return if (patches.any { it.patch.fields.isNotEmpty() }) common.copy(memberPatches = patches) else common
}
