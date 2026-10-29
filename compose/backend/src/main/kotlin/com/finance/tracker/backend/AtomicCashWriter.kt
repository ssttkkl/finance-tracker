package com.finance.tracker.backend

import java.time.Instant
import kotlinx.serialization.json.*

internal fun CashSqlContext.insertAtomicCash(accountId:Long,occurredAt:Instant,amount:CashAmount,currency:String,
    recordType:String,recordSubtype:String,origin:String,transferId:String?=null,sourceType:String?=null,
    fingerprint:String?=null,counterparty:String="",note:String="",category:String?=null):Pair<Long,Long> {
    requireActive()
    val fact=insertCashRow("cash_transactions",linkedMapOf("workspace_id" to workspace,"account_id" to accountId,"cash_granularity" to "atomic",
        "record_origin" to origin,"manual_transfer_id" to transferId,"state" to "posted","row_revision" to 1L,
        "source_type" to sourceType,"record_id" to "","source_payload" to JsonNull,"relation_metadata" to JsonNull,"source_fingerprint" to fingerprint,"manual_overrides" to JsonObject(emptyMap()),
        "occurred_at" to occurredAt,"amount" to amount,"currency" to currency,"counterparty" to counterparty,"counterparty_account" to "","counterparty_account_attrs" to JsonArray(emptyList()),
        "note" to note,"category_id" to category,"record_type" to recordType,"record_subtype" to recordSubtype,"created_at" to now(),"deleted_at" to null,"deleted_by" to "","delete_reason" to ""))
    val part=insertCashRow("cash_transaction_components",linkedMapOf("workspace_id" to workspace,"cash_transaction_id" to fact,"account_id" to accountId,
        "amount" to amount,"currency" to currency,"ordinal" to 0,"label" to "","source_key" to "","metadata_json" to JsonObject(emptyMap()),"created_at" to now()))
    return fact to part
}
