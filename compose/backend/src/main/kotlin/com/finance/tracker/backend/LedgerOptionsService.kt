package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*
import java.time.Instant

internal fun buildLedgerOptions() = com.finance.tracker.business.domain.buildCashLedgerOptions()

class LedgerOptionsService(database: Database,now: ()->Instant=Instant::now) {
    private val reads=ReadServices(database,now)
    fun options(token: String?): LedgerOptionsDto {
        reads.requireContext(token)
        return buildLedgerOptions()
    }
}
