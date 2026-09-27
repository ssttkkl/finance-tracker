package com.finance.tracker.app

import com.finance.tracker.core.TokenStore
import com.finance.tracker.core.DiagnosticLogger
import com.finance.tracker.core.createDiagnosticLogger
import com.finance.tracker.data.FinanceApiClient
import com.finance.tracker.data.KtorFinanceRepository
import com.finance.tracker.data.KtorSessionRepository
import com.finance.tracker.domain.FinanceRepository
import com.finance.tracker.domain.SessionRepository

data class FinanceTrackerGraph(
    val apiClient: FinanceApiClient,
    val sessionRepository: SessionRepository,
    val financeRepository: FinanceRepository,
    val diagnosticLogger: DiagnosticLogger,
)

fun createFinanceTrackerGraph(apiOrigin: String, tokenStore: TokenStore): FinanceTrackerGraph {
    val apiClient = FinanceApiClient({ apiOrigin }, tokenStore)
    return FinanceTrackerGraph(
        apiClient = apiClient,
        sessionRepository = KtorSessionRepository(apiClient),
        financeRepository = KtorFinanceRepository(apiClient),
        diagnosticLogger = createDiagnosticLogger(),
    )
}
