package com.finance.tracker.app

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch

@Composable
fun App(apiOrigin: String = getConfiguredApiOrigin(), demoMode: Boolean = false) {
    FinanceTrackerApp(apiOrigin = apiOrigin, demoMode = demoMode)
}

internal data class DestinationState(
    val route: AppRoute,
    val financeRepository: FinanceRepository,
    val cashMaintenanceRepository: CashMaintenanceRepository,
    val cashCreationRepository: CashCreationRepository,
    val cashImportRepository: CashImportLifecycleRepository,
    val importRecovery: ImportRecoveryStore,
    val importAccountRecovery: ImportAccountRecovery,
    val registerImportExit: (((() -> Unit) -> Unit)?) -> Unit,
    val onImportDone: () -> Unit,
    val cashMaintenanceRecovery: CashMaintenanceRecoveryStore,
    val sessionRepository: SessionRepository,
    val diagnosticLogger: DiagnosticLogger,
    val session: Session,
    val accessBusy: Boolean,
    val accessError: String?,
    val sizeClass: WindowSizeClass,
    val cashLedgerReloadRevision: Int,
    val onNavigate: (AppPage) -> Unit,
    val canNavigateBack: Boolean,
    val onNavigateBack: () -> Unit,
    val onNavigateBackOr: (AppPage) -> Unit,
    val onCloseSecondaryPage: () -> Unit,
    val onSessionUpdated: (Session) -> Unit,
    val onWorkspaceDeleted: (Session) -> Unit,
    val onInvitationAccepted: (Session) -> Unit,
    val onWorkspaceSelected: (String) -> Unit,
    val onLogout: () -> Unit,
    val demoStatement: FileSource? = null,
    val navigationIcon: @Composable () -> Unit = {},
)

@Composable
private fun accessErrorText(code: String): String = when (code) {
    "invalid_credentials", "authentication_required" -> localizedText("copy_7c95b6d695")
    "network_unavailable" -> localizedText("copy_e0a1c9fd69")
    "unknown_error" -> localizedText("copy_382ef5a830")
    "workspace_access_denied" -> localizedText("workspace_access_denied")
    "auth_input_invalid_emailrequired" -> localizedText("copy_a2789a9770")
    "auth_input_invalid_emailinvalid" -> localizedText("copy_dfa5e652bf")
    "auth_input_invalid_passwordrequired" -> localizedText("copy_727bbbf558")
    "auth_input_invalid_passwordtooshort" -> localizedText("copy_b806c505de")
    else -> localizedText("copy_4cd657fe7b")
}

@Composable
fun FinanceTrackerApp(
    apiOrigin: String = getConfiguredApiOrigin(),
    demoMode: Boolean = false,
    initialRoute: AppRoute = AppRoute(page = AppPage.CASH_LEDGER),
    onNavigate: (AppRoute) -> Unit = {},
) {
    val owner = remember { FinanceTrackerViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        FinanceTrackerAppContent(apiOrigin, demoMode, initialRoute, onNavigate)
    }
}

private class FinanceTrackerViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

@Composable
private fun FinanceTrackerAppContent(
    apiOrigin: String,
    demoMode: Boolean,
    initialRoute: AppRoute,
    onNavigate: (AppRoute) -> Unit,
) {
    val graph = remember(apiOrigin, demoMode) {
        if (demoMode) {
            createDemoFinanceTrackerGraph()
        } else {
            createFinanceTrackerGraph(apiOrigin, createPlatformTokenStore())
        }
    }
    val apiClient = graph.apiClient
    val accessViewModel: AccessViewModel = viewModel(
        key = "access:$apiOrigin",
        factory = viewModelFactory {
            initializer { AccessViewModel(graph.sessionRepository, diagnostics = graph.diagnosticLogger) }
        },
    )
    val accessState by accessViewModel.state.collectAsStateWithLifecycle()
    val accessPhase = accessState.status
    val session = accessState.session
    val accessBusy = accessState.busy
    val accessError = accessState.errorCode?.let { accessErrorText(it) }
    var invitationSignInRequested by remember(apiClient, demoMode) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(apiClient) {
        onDispose { apiClient?.close() }
    }
    val resolvedInitialRoute = getBrowserLocation()?.let { parseAppRoute(it.pathname, it.search) }
        ?: IncomingInvitationLinks.currentToken()?.let { AppRoute(AppPage.INVITATION, invitationToken = it) }
        ?: initialRoute
    var navigationState by rememberAppNavigationState(resolvedInitialRoute)
    var importExitGuard by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
    val registerImportExit = remember { { handler: ((() -> Unit) -> Unit)? -> importExitGuard = handler } }
    fun guardNavigation(action: () -> Unit) {
        val guard = importExitGuard.takeIf { navigationState.currentRoute.page == AppPage.CASH_IMPORT }
        if (guard == null) action() else guard(action)
    }
    val currentRoute = navigationState.currentRoute
    val routeHistory = navigationState.backStack
    var importCompletedAwaitingReturn by remember { mutableStateOf(false) }
    var cashLedgerReloadRevision by remember { mutableStateOf(0) }
    LaunchedEffect(currentRoute.page) {
        if (currentRoute.page == AppPage.CASH_LEDGER && importCompletedAwaitingReturn) {
            cashLedgerReloadRevision += 1
            importCompletedAwaitingReturn = false
        }
    }

    DisposableEffect(Unit) {
        val removeListener = observeBrowserHistory {
            getBrowserLocation()?.let { location ->
                val next = parseAppRoute(location.pathname, location.search)
                val target = location.pathname + location.search
                if (next != navigationState.currentRoute) {
                    appPathForRoute(navigationState.currentRoute)?.let(::replaceBrowserPath)
                    guardNavigation {
                        navigationState = navigationState.copy(currentRoute = next)
                        replaceBrowserPath(target)
                    }
                }
            }
        }
        val removeInvitationListener = IncomingInvitationLinks.observe { token ->
            val nextRoute = AppRoute(AppPage.INVITATION, invitationToken = token)
            guardNavigation { navigationState = navigationState.navigate(nextRoute, recordBackStack = getBrowserLocation() == null) }
        }
        onDispose {
            removeListener()
            removeInvitationListener()
        }
    }

    LaunchedEffect(accessViewModel, currentRoute.workspaceId) {
        accessViewModel.initialize(currentRoute.workspaceId)
    }

    LaunchedEffect(accessPhase, session?.activeWorkspaceId, currentRoute.page) {
        val workspaceId = session?.activeWorkspaceId
        if (accessPhase == AccessStatus.AUTHENTICATED && workspaceId != null && currentRoute.page != AppPage.INVITATION) {
            val routed = currentRoute.copy(workspaceId = workspaceId)
            appPathForRoute(routed)?.let { path ->
                if (getBrowserLocation()?.pathname != path) replaceBrowserPath(path)
            }
            navigationState = navigationState.copy(currentRoute = routed)
        }
    }

    val selectPage: (AppPage) -> Unit = { page ->
        if (page != currentRoute.page) guardNavigation {
            val nextRoute = currentRoute.copy(page = page,
                workspaceId = session?.activeWorkspaceId ?: currentRoute.workspaceId,
                invitationToken = null, unmatchedPath = null)
            navigationState = navigationState.navigate(nextRoute, recordBackStack = getBrowserLocation() == null)
            appPathForRoute(nextRoute)?.let(::pushBrowserPath)
            onNavigate(nextRoute)
        }
    }

    val navigateBackDirect: () -> Unit = {
        if (getBrowserLocation() == null) navigationState.back()?.let { previous ->
            navigationState = previous
            onNavigate(previous.currentRoute)
        }
    }
    val navigateBack: () -> Unit = { guardNavigation(navigateBackDirect) }

    val navigateBackOr: (AppPage) -> Unit = { fallbackPage ->
        if (getBrowserLocation() == null && routeHistory.isNotEmpty()) {
            navigateBack()
        } else {
            val nextRoute = currentRoute.copy(
                page = fallbackPage,
                workspaceId = session?.activeWorkspaceId ?: currentRoute.workspaceId,
                invitationToken = null,
                unmatchedPath = null,
            )
            appPathForRoute(nextRoute)?.let(::pushBrowserPath)
            navigationState = navigationState.copy(currentRoute = nextRoute)
            onNavigate(nextRoute)
        }
    }

    val closeSecondaryPage: () -> Unit = {
        val ledgerRoute = currentRoute.copy(
            page = AppPage.CASH_LEDGER,
            workspaceId = session?.activeWorkspaceId ?: currentRoute.workspaceId,
            invitationToken = null,
            unmatchedPath = null,
        )
        if (getBrowserLocation() != null) {
            navigationState = navigationState.copy(currentRoute = ledgerRoute)
            appPathForRoute(ledgerRoute)?.let(::replaceBrowserPath)
            onNavigate(ledgerRoute)
        } else if (routeHistory.isNotEmpty()) {
            navigateBackDirect()
        } else {
            navigationState = navigationState.copy(currentRoute = ledgerRoute)
            onNavigate(ledgerRoute)
        }
    }

    PlatformBackHandler(
        enabled = accessPhase == AccessStatus.AUTHENTICATED &&
            getBrowserLocation() == null && routeHistory.isNotEmpty(),
        onBack = navigateBack,
    )

    val updateSession: (Session) -> Unit = { updated ->
        if (updated.activeWorkspaceId != session?.activeWorkspaceId) {
            navigationState = navigationState.copy(backStack = emptyList())
        }
        accessViewModel.replaceSession(updated)
        invitationSignInRequested = false
        val workspaceId = updated.activeWorkspaceId
        if (workspaceId == null) {
            navigationState = AppNavigationState(AppRoute(AppPage.CASH_LEDGER))
        } else {
            val route = currentRoute.copy(workspaceId = workspaceId)
            navigationState = navigationState.copy(currentRoute = route)
            appPathForRoute(route)?.let(::replaceBrowserPath)
        }
    }

    val workspaceDeleted: (Session) -> Unit = { updated ->
        navigationState = navigationState.copy(backStack = emptyList())
        accessViewModel.replaceSession(updated)
        invitationSignInRequested = false
        val workspaceId = updated.activeWorkspaceId
        if (workspaceId == null) {
            navigationState = AppNavigationState(AppRoute(AppPage.CASH_LEDGER))
            replaceBrowserPath("/")
        } else {
            val route = AppRoute(AppPage.CASH_LEDGER, workspaceId = workspaceId)
            navigationState = AppNavigationState(route)
            appPathForRoute(route)?.let(::replaceBrowserPath)
        }
    }

    val acceptInvitationSession: (Session) -> Unit = { accepted ->
        navigationState = navigationState.copy(backStack = emptyList())
        IncomingInvitationLinks.clear(currentRoute.invitationToken)
        accessViewModel.replaceSession(accepted)
        invitationSignInRequested = false
        val workspaceId = accepted.activeWorkspaceId ?: accepted.workspaces.lastOrNull()?.id
        val returnPage = invitationReturnPage(getBrowserLocation()?.pathname)
        if (workspaceId == null) {
            navigationState = AppNavigationState(AppRoute(AppPage.CASH_LEDGER))
        } else {
            val route = AppRoute(returnPage, workspaceId = workspaceId)
            navigationState = navigationState.copy(currentRoute = route)
            appPathForRoute(route)?.let(::replaceBrowserPath)
            onNavigate(route)
        }
    }

    val selectWorkspace: (String) -> Unit = { id -> guardNavigation { accessViewModel.selectWorkspace(id) } }

    val logout: () -> Unit = { guardNavigation {
        accessViewModel.signOut()
        navigationState = navigationState.copy(backStack = emptyList())
        invitationSignInRequested = false
    } }

    FinanceTheme {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            when (accessPhase) {
                AccessStatus.LOADING -> SessionLoadingScreen()
                AccessStatus.SIGNED_OUT -> if (currentRoute.page == AppPage.INVITATION && !invitationSignInRequested) {
                    InvitationScreen(
                        repository = graph.sessionRepository,
                        token = currentRoute.invitationToken.orEmpty(),
                        session = null,
                        onRequireSignIn = { invitationSignInRequested = true },
                        onAccepted = acceptInvitationSession,
                        onCancel = {
                            IncomingInvitationLinks.clear(currentRoute.invitationToken)
                            navigateBackOr(invitationReturnPage(getBrowserLocation()?.pathname))
                        },
                    )
                } else AuthScreen(
                    busy = accessBusy,
                    requestError = accessError,
                    onAuthenticate = { email, password, registering ->
                        accessViewModel.authenticate(email, password, registering, currentRoute.workspaceId)
                    },
                    onCredentialValidationFailure = accessViewModel::recordCredentialValidationFailure,
                )
                AccessStatus.WORKSPACE -> session?.let { currentSession ->
                    WorkspaceAccessScreen(
                        session = currentSession,
                        busy = accessBusy,
                        requestError = accessError,
                        onSelect = accessViewModel::selectWorkspace,
                        onCreate = accessViewModel::createWorkspace,
                        onLogout = logout,
                        onRetry = { accessViewModel.retrySession(currentRoute.workspaceId) },
                        onInvalidWorkspaceName = accessViewModel::recordWorkspaceNameValidationFailure,
                    )
                } ?: SessionLoadingScreen()
                AccessStatus.AUTHENTICATED -> {
                    val windowSizeClass = WindowSizeClass.fromWidthDp(maxWidth.value.toInt())
                    val currentSession = session
                    if (currentSession == null) {
                        SessionLoadingScreen()
                        return@BoxWithConstraints
                    }
                    val destination = remember(apiClient) {
                        movableContentOf<DestinationState> { state -> DestinationScaffold(state) }
                    }
                    val maintenanceRecovery = remember(apiOrigin, demoMode, currentSession.user.email, currentSession.activeWorkspaceId) {
                        if (demoMode || currentSession.activeWorkspaceId.isNullOrBlank()) InMemoryCashMaintenanceRecoveryStore()
                        else ScopedCashMaintenanceRecoveryStore(ImportRecoveryScope(apiOrigin, currentSession.user.email, currentSession.activeWorkspaceId), createPlatformImportRecoveryStorage())
                    }
                    val importStores = remember(apiOrigin, demoMode, currentSession.user.email, currentSession.activeWorkspaceId) {
                        val storage = if (demoMode) InMemoryImportRecoveryStorage() else createPlatformImportRecoveryStorage()
                        val recoveryScope = ImportRecoveryScope(apiOrigin, currentSession.user.email, currentSession.activeWorkspaceId.orEmpty())
                        ScopedImportRecoveryStore(recoveryScope, storage) to ScopedImportAccountRecovery(recoveryScope, storage)
                    }
                    val state = DestinationState(
                        route = currentRoute,
                        financeRepository = graph.financeRepository,
                        cashMaintenanceRepository = graph.cashMaintenanceRepository,
                        cashCreationRepository = graph.cashCreationRepository,
                        cashImportRepository = graph.cashImportRepository,
                        importRecovery = importStores.first,
                        importAccountRecovery = importStores.second,
                        registerImportExit = registerImportExit,
                        onImportDone = { importCompletedAwaitingReturn = true },
                        cashMaintenanceRecovery = maintenanceRecovery,
                        sessionRepository = graph.sessionRepository,
                        diagnosticLogger = graph.diagnosticLogger,
                        demoStatement = graph.demoStatement,
                        session = currentSession,
                        accessBusy = accessBusy,
                        accessError = accessError,
                        sizeClass = windowSizeClass,
                        cashLedgerReloadRevision = cashLedgerReloadRevision,
                        onNavigate = selectPage,
                        canNavigateBack = getBrowserLocation() == null && routeHistory.isNotEmpty(),
                        onNavigateBack = navigateBack,
                        onNavigateBackOr = navigateBackOr,
                        onCloseSecondaryPage = closeSecondaryPage,
                        onSessionUpdated = updateSession,
                        onWorkspaceDeleted = workspaceDeleted,
                        onInvitationAccepted = acceptInvitationSession,
                        onWorkspaceSelected = selectWorkspace,
                        onLogout = logout,
                    )
                    Box(Modifier.fillMaxSize()) {
                        when (windowSizeClass) {
                            WindowSizeClass.COMPACT -> CompactAppShell(state) { navigationIcon ->
                                destination(state.copy(navigationIcon = navigationIcon))
                            }
                            WindowSizeClass.REGULAR -> RegularAppShell(state) {
                                destination(state.copy(navigationIcon = {}))
                            }
                            WindowSizeClass.WIDE -> WideAppShell(state) { destination(state.copy(navigationIcon = {})) }
                        }

                    }
                }
            }
        }
    }
}
