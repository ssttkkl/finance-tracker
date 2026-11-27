package com.finance.tracker.app

import com.finance.tracker.core.TokenStore
import com.finance.tracker.core.DiagnosticLogger
import com.finance.tracker.core.createDiagnosticLogger
import com.finance.tracker.core.NoOpDiagnosticLogger
import com.finance.tracker.data.LocalCashImportLifecycleRepository
import com.finance.tracker.data.DemoCashImportLifecycleRepository
import com.finance.tracker.domain.CashImportLifecycleRepository
import com.finance.tracker.data.FinanceApiClient
import com.finance.tracker.data.seedAuditScenarios
import com.finance.tracker.data.DemoFinanceRepository
import com.finance.tracker.data.DemoSessionRepository
import com.finance.tracker.data.DemoStatementFileSource
import com.finance.tracker.data.KtorCashMaintenanceRepository
import com.finance.tracker.data.DemoCashMaintenanceRepository
import com.finance.tracker.domain.CashMaintenanceRepository
import com.finance.tracker.domain.CashCreationRepository
import com.finance.tracker.data.KtorCashCreationRepository
import com.finance.tracker.data.DemoCashCreationRepository
import com.finance.tracker.data.KtorFinanceRepository
import com.finance.tracker.data.KtorSessionRepository
import com.finance.tracker.data.LedgerSessionRestoration
import com.finance.tracker.data.LocalFirstFinanceRepository
import com.finance.tracker.data.LocalCashMaintenanceRepository
import com.finance.tracker.data.captureCredentials
import com.finance.tracker.data.clearTokenIfCurrent
import com.finance.tracker.data.SessionCacheRepository
import com.finance.tracker.data.loginWithCredentials
import com.finance.tracker.data.registerWithCredentials
import com.finance.tracker.domain.FinanceRepository
import com.finance.tracker.domain.FileSource
import com.finance.tracker.domain.SessionRepository

data class FinanceTrackerGraph(
    val apiClient: FinanceApiClient?,
    val sessionRepository: SessionRepository,
    val financeRepository: FinanceRepository,
    val cashMaintenanceRepository: CashMaintenanceRepository,
    val cashCreationRepository: CashCreationRepository,
    val cashImportRepository: CashImportLifecycleRepository,
    val diagnosticLogger: DiagnosticLogger,
    val demoStatement: FileSource? = null,
)

fun createFinanceTrackerGraph(apiOrigin: String, tokenStore: TokenStore): FinanceTrackerGraph {
    val apiClient = FinanceApiClient({ apiOrigin }, tokenStore)
    val diagnosticLogger = createDiagnosticLogger()
    val remoteSessionRepository = KtorSessionRepository(apiClient)
    val sessionRepository = SessionCacheRepository(
        delegate = remoteSessionRepository,
        diagnosticLogger = diagnosticLogger,
        apiOrigin = apiOrigin,
        captureCredentials = { apiClient.captureCredentials() },
        clearRejectedToken = { apiClient.clearTokenIfCurrent(it) },
        createRestoration = { owner, ticket, credentials -> LedgerSessionRestoration(apiClient, credentials, owner, ticket) },
        authenticate = { email, password, registering, owner, ticket ->
            if (registering) apiClient.registerWithCredentials(email, password, owner, ticket)
            else apiClient.loginWithCredentials(email, password, owner, ticket)
        },
        bindSession = remoteSessionRepository::boundTo,
    )
    val cashOperations = sessionRepository.businessOperations(apiClient)
    return FinanceTrackerGraph(
        apiClient = apiClient,
        sessionRepository = sessionRepository,
        financeRepository = LocalFirstFinanceRepository(
            KtorFinanceRepository(apiClient, cashOperations),
            { sessionRepository.localReader() },
            refreshBrowsing = sessionRepository::refreshBrowsingFacts,
        ),
        cashMaintenanceRepository = LocalCashMaintenanceRepository(KtorCashMaintenanceRepository(apiClient, cashOperations), sessionRepository::localReader),
        cashCreationRepository = KtorCashCreationRepository(apiClient, cashOperations, sessionRepository::captureCashCreationJournal),
        cashImportRepository = LocalCashImportLifecycleRepository(sessionRepository::captureImports,
            captureSessionImports = sessionRepository::captureImportsFor),
        diagnosticLogger = diagnosticLogger,
    )
}

fun createDemoFinanceTrackerGraph(): FinanceTrackerGraph {
    val finance = DemoFinanceRepository().apply { seedAuditScenarios() }
    return FinanceTrackerGraph(
    apiClient = null,
    sessionRepository = DemoSessionRepository(),
    financeRepository = finance,
    cashMaintenanceRepository = DemoCashMaintenanceRepository(finance),
    cashCreationRepository = DemoCashCreationRepository(finance),
    cashImportRepository = DemoCashImportLifecycleRepository(finance),
    diagnosticLogger = NoOpDiagnosticLogger,
    demoStatement = DemoStatementFileSource(auditScenarios = true),
)
}
