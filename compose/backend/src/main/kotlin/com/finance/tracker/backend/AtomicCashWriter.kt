package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*

import java.time.Instant
import kotlinx.serialization.json.*

internal fun CashSqlContext.insertAtomicCash(accountId:Long,occurredAt:Instant,amount:CashAmount,currency:String,
    recordType:String,recordSubtype:String,origin:String,transferId:String?=null,sourceType:String?=null,
    fingerprint:String?=null,counterparty:String="",note:String="",category:String?=null):Pair<Long,Long> {
    requireActive()
    val fact=this.sql.insertInto(CASH_TRANSACTIONS)
            .set(CASH_TRANSACTIONS.WORKSPACE_ID, workspace)
            .set(CASH_TRANSACTIONS.ACCOUNT_ID, accountId)
            .set(CASH_TRANSACTIONS.CASH_GRANULARITY, "atomic")
            .set(CASH_TRANSACTIONS.RECORD_ORIGIN, origin)
            .set(CASH_TRANSACTIONS.MANUAL_TRANSFER_ID, transferId)
            .set(CASH_TRANSACTIONS.STATE, "posted")
            .set(CASH_TRANSACTIONS.ROW_REVISION, 1L)
            .set(CASH_TRANSACTIONS.SOURCE_TYPE, sourceType)
            .set(CASH_TRANSACTIONS.RECORD_ID, "")
            .set(CASH_TRANSACTIONS.SOURCE_PAYLOAD, JsonNull)
            .set(CASH_TRANSACTIONS.RELATION_METADATA, JsonNull)
            .set(CASH_TRANSACTIONS.SOURCE_FINGERPRINT, fingerprint)
            .set(CASH_TRANSACTIONS.MANUAL_OVERRIDES, JsonObject(emptyMap()))
            .set(CASH_TRANSACTIONS.OCCURRED_AT, occurredAt)
            .set(CASH_TRANSACTIONS.AMOUNT, CashStoredDecimal(amount.toString()))
            .set(CASH_TRANSACTIONS.CURRENCY, currency)
            .set(CASH_TRANSACTIONS.COUNTERPARTY, counterparty)
            .set(CASH_TRANSACTIONS.COUNTERPARTY_ACCOUNT, "")
            .set(CASH_TRANSACTIONS.COUNTERPARTY_ACCOUNT_ATTRS, JsonArray(emptyList()))
            .set(CASH_TRANSACTIONS.NOTE, note)
            .set(CASH_TRANSACTIONS.CATEGORY_ID, category)
            .set(CASH_TRANSACTIONS.RECORD_TYPE, recordType)
            .set(CASH_TRANSACTIONS.RECORD_SUBTYPE, recordSubtype)
            .set(CASH_TRANSACTIONS.CREATED_AT, now())
            .setNull(CASH_TRANSACTIONS.DELETED_AT)
            .set(CASH_TRANSACTIONS.DELETED_BY, "")
            .set(CASH_TRANSACTIONS.DELETE_REASON, "")
            .returningResult(CASH_TRANSACTIONS.ID).fetchSingle().value1()!!
    val part=this.sql.insertInto(CASH_TRANSACTION_COMPONENTS)
            .set(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID, workspace)
            .set(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID, fact)
            .set(CASH_TRANSACTION_COMPONENTS.ACCOUNT_ID, accountId)
            .set(CASH_TRANSACTION_COMPONENTS.AMOUNT, CashStoredDecimal(amount.toString()))
            .set(CASH_TRANSACTION_COMPONENTS.CURRENCY, currency)
            .set(CASH_TRANSACTION_COMPONENTS.ORDINAL, 0L)
            .set(CASH_TRANSACTION_COMPONENTS.LABEL, "")
            .set(CASH_TRANSACTION_COMPONENTS.SOURCE_KEY, "")
            .set(CASH_TRANSACTION_COMPONENTS.METADATA_JSON, JsonObject(emptyMap()))
            .set(CASH_TRANSACTION_COMPONENTS.CREATED_AT, now())
            .returningResult(CASH_TRANSACTION_COMPONENTS.ID).fetchSingle().value1()!!
    return fact to part
}
