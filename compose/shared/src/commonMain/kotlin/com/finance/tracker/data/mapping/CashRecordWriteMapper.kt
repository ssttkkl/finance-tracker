package com.finance.tracker.data.mapping

import com.finance.tracker.domain.CashRecordWrite
import com.finance.tracker.domain.StructuredBoolean
import com.finance.tracker.domain.StructuredNull
import com.finance.tracker.domain.StructuredNumber
import com.finance.tracker.domain.StructuredObject
import com.finance.tracker.domain.StructuredText

fun CashRecordWrite.toStructuredObject(): StructuredObject = StructuredObject(
    buildMap {
        put("occurred_at", StructuredText(occurredAt))
        put("amount", StructuredText(amount))
        put("currency", StructuredText(currency))
        put("counterparty", StructuredText(counterparty))
        put("counterparty_account", StructuredText(counterpartyAccount))
        put("note", StructuredText(note))
        put("account_name", StructuredText(accountName))
        put("record_type", StructuredText(recordType))
        put("record_subtype", StructuredText(recordSubtype.ifBlank { "not_applicable" }))
        put("category_id", categoryId?.let(::StructuredText) ?: StructuredNull)
        projectionVersion?.let { put("projection_version", StructuredNumber(it.toString())) }
        confirmRelationImpact?.let { put("confirm_relation_impact", StructuredBoolean(it)) }
    },
)
