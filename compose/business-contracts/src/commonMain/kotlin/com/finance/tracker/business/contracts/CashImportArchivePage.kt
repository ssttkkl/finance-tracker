package com.finance.tracker.business.contracts

import kotlinx.serialization.Serializable

@Serializable
data class CashImportArchivePage(val items: List<CashMutationResultDto>, val nextAfter: String?)
