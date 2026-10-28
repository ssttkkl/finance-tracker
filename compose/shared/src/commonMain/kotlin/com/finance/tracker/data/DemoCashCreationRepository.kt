package com.finance.tracker.data

import com.finance.tracker.domain.*

class DemoCashCreationRepository(private val finance: DemoFinanceRepository) : CashCreationRepository {
    override suspend fun createRecord(input: StructuredObject) {
        fun text(key: String) = (input.fields[key] as? StructuredText)?.value.orEmpty()
        val created = finance.createCashRecord(CashRecordWrite(text("occurred_at"), text("amount"), text("currency"), text("counterparty"),
            text("counterparty_account"), text("note"), text("account_name"), text("record_type"), text("record_subtype"), text("category_id").ifEmpty { null }))
        finance.saveCommonFields(created.record.id, input.fields.filterKeys {
            it in setOf("note", "category_id", "counterparty", "counterparty_account", "counterparty_account_attrs")
        })
    }
    override suspend fun createTransfer(input: StructuredObject) { finance.createDemoCashTransfer(input) }
}
