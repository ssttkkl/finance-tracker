package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LedgerVersionDto(@SerialName("ledger_seq") val ledgerSeq: String)
