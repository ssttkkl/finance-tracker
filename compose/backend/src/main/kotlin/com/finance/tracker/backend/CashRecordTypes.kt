package com.finance.tracker.backend

internal val cashRecordTypes=setOf("consumption","refund","reversal","transfer_reversal","withdrawal_in","withdrawal_out","transfer_in","transfer_out","repayment","income","investment_in","investment_out","interest","fee","fx_in","fx_out","other")
internal val cashRecordSubtypes=setOf("ordinary_transfer","cross_border_remittance","internal_account_transfer","currency_exchange","withdraw_to_bank","credit_repayment","not_applicable")
internal fun defaultCashRecordSubtype(type: String?): String=when(type) {
    "transfer_in","transfer_out" ->"ordinary_transfer"
    "fx_in","fx_out" ->"currency_exchange"
    "repayment" ->"credit_repayment"
    "withdrawal_in","withdrawal_out" ->"withdraw_to_bank"
    else ->"not_applicable"
}
internal fun validCashRecordPair(type: Any?,subtype: Any?): Boolean {
    if(type !in cashRecordTypes || subtype !in cashRecordSubtypes) return false
    return if(type in setOf("transfer_in","transfer_out")) subtype in setOf("ordinary_transfer","cross_border_remittance","internal_account_transfer")
        else subtype==defaultCashRecordSubtype(type as String)
}
