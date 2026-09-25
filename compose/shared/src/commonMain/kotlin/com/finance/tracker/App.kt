package com.finance.tracker

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
import kotlinx.coroutines.launch

@Composable
fun App(apiOrigin: String = getConfiguredApiOrigin()) {
    FinanceTrackerApp(apiOrigin = apiOrigin)
}

private enum class AccessPhase {
    LOADING,
    SIGNED_OUT,
    WORKSPACE,
    AUTHENTICATED,
}

private data class DestinationState(
    val route: AppRoute,
    val api: FinanceApiClient,
    val session: SessionDto,
    val sizeClass: WindowSizeClass,
    val onNavigate: (AppPage) -> Unit,
    val canNavigateBack: Boolean,
    val onNavigateBack: () -> Unit,
    val onNavigateBackOr: (AppPage) -> Unit,
    val onSessionUpdated: (SessionDto) -> Unit,
    val onWorkspaceDeleted: (SessionDto) -> Unit,
    val onInvitationAccepted: (SessionDto) -> Unit,
    val onWorkspaceSelected: (String) -> Unit,
    val onLogout: () -> Unit,
    val navigationIcon: @Composable () -> Unit = {},
)

@Composable
fun FinanceTrackerApp(
    apiOrigin: String = getConfiguredApiOrigin(),
    initialRoute: AppRoute = AppRoute(page = AppPage.CASH_LEDGER),
    onNavigate: (AppRoute) -> Unit = {},
) {
    val tokenStore = remember { createPlatformTokenStore() }
    val apiClient = remember(apiOrigin, tokenStore) { FinanceApiClient({ apiOrigin }, tokenStore) }
    var accessPhase by remember(apiClient) { mutableStateOf(AccessPhase.LOADING) }
    var session by remember(apiClient) { mutableStateOf<SessionDto?>(null) }
    var accessBusy by remember(apiClient) { mutableStateOf(false) }
    var accessError by remember(apiClient) { mutableStateOf<String?>(null) }
    var invitationSignInRequested by remember(apiClient) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(apiClient) {
        onDispose { apiClient.close() }
    }
    val resolvedInitialRoute = getBrowserLocation()?.let { parseAppRoute(it.pathname, it.search) }
        ?: IncomingInvitationLinks.currentToken()?.let { AppRoute(AppPage.INVITATION, invitationToken = it) }
        ?: initialRoute
    var navigationState by rememberAppNavigationState(resolvedInitialRoute)
    val currentRoute = navigationState.currentRoute
    val routeHistory = navigationState.backStack

    DisposableEffect(Unit) {
        val removeListener = observeBrowserHistory {
            getBrowserLocation()?.let { location ->
                navigationState = navigationState.copy(currentRoute = parseAppRoute(location.pathname, location.search))
            }
        }
        val removeInvitationListener = IncomingInvitationLinks.observe { token ->
            val nextRoute = AppRoute(AppPage.INVITATION, invitationToken = token)
            navigationState = navigationState.navigate(nextRoute, recordBackStack = getBrowserLocation() == null)
        }
        onDispose {
            removeListener()
            removeInvitationListener()
        }
    }

    suspend fun acceptSession(value: SessionDto, restoreRequestedWorkspace: Boolean = true) {
        accessPhase = AccessPhase.LOADING
        accessError = null
        var next = value
        val requestedWorkspace = workspaceSelectionForSession(
            value,
            currentRoute.workspaceId,
            restoreRequestedWorkspace = restoreRequestedWorkspace,
        )
        if (requestedWorkspace != null) {
            try {
                next = apiClient.selectWorkspace(requestedWorkspace)
            } catch (_: Throwable) {
                accessError = if (currentRoute.workspaceId != null) {
                    "无法打开该工作区，请检查权限后重试。"
                } else {
                    "无法打开该工作区，请稍后重试。"
                }
            }
        }
        session = next
        accessPhase = if (next.activeWorkspaceId != null && accessError == null) {
            AccessPhase.AUTHENTICATED
        } else {
            AccessPhase.WORKSPACE
        }
        if (accessPhase == AccessPhase.AUTHENTICATED && currentRoute.page != AppPage.INVITATION) {
            val routed = currentRoute.copy(workspaceId = next.activeWorkspaceId)
            appPathForRoute(routed)?.let { path ->
                if (getBrowserLocation()?.pathname != path) replaceBrowserPath(path)
            navigationState = navigationState.copy(currentRoute = routed)
            }
        }
    }

    LaunchedEffect(apiClient) {
        try {
            if (!apiClient.hasStoredSessionToken()) {
                accessPhase = AccessPhase.SIGNED_OUT
            } else {
                acceptSession(apiClient.restoreSession())
            }
        } catch (_: Throwable) {
            accessPhase = AccessPhase.SIGNED_OUT
        }
    }

    val selectPage: (AppPage) -> Unit = { page ->
        val nextRoute = currentRoute.copy(
            page = page,
            workspaceId = session?.activeWorkspaceId ?: currentRoute.workspaceId,
            invitationToken = null,
            unmatchedPath = null,
        )
        navigationState = navigationState.navigate(nextRoute, recordBackStack = getBrowserLocation() == null)
        appPathForRoute(nextRoute)?.let(::pushBrowserPath)
        onNavigate(nextRoute)
    }

    val navigateBack: () -> Unit = {
        if (getBrowserLocation() == null) {
            val previousState = navigationState.back()
            if (previousState != null) {
                navigationState = previousState
                val previous = previousState.currentRoute
                onNavigate(previous)
            }
        }
    }

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

    PlatformBackHandler(
        enabled = accessPhase == AccessPhase.AUTHENTICATED &&
            getBrowserLocation() == null && routeHistory.isNotEmpty(),
        onBack = navigateBack,
    )

    val updateSession: (SessionDto) -> Unit = { updated ->
        if (updated.activeWorkspaceId != session?.activeWorkspaceId) {
            navigationState = navigationState.copy(backStack = emptyList())
        }
        session = updated
        accessError = null
        invitationSignInRequested = false
        val workspaceId = updated.activeWorkspaceId
        if (workspaceId == null) {
            navigationState = AppNavigationState(AppRoute(AppPage.CASH_LEDGER))
            accessPhase = AccessPhase.WORKSPACE
        } else {
            accessPhase = AccessPhase.AUTHENTICATED
            val route = currentRoute.copy(workspaceId = workspaceId)
            navigationState = navigationState.copy(currentRoute = route)
            appPathForRoute(route)?.let(::replaceBrowserPath)
        }
    }

    val workspaceDeleted: (SessionDto) -> Unit = { updated ->
        navigationState = navigationState.copy(backStack = emptyList())
        session = updated
        accessError = null
        invitationSignInRequested = false
        val workspaceId = updated.activeWorkspaceId
        if (workspaceId == null) {
            accessPhase = AccessPhase.WORKSPACE
            navigationState = AppNavigationState(AppRoute(AppPage.CASH_LEDGER))
            replaceBrowserPath("/")
        } else {
            accessPhase = AccessPhase.AUTHENTICATED
            val route = AppRoute(AppPage.CASH_LEDGER, workspaceId = workspaceId)
            navigationState = AppNavigationState(route)
            appPathForRoute(route)?.let(::replaceBrowserPath)
        }
    }

    val acceptInvitationSession: (SessionDto) -> Unit = { accepted ->
        navigationState = navigationState.copy(backStack = emptyList())
        IncomingInvitationLinks.clear(currentRoute.invitationToken)
        session = accepted
        accessError = null
        invitationSignInRequested = false
        val workspaceId = accepted.activeWorkspaceId ?: accepted.workspaces.lastOrNull()?.id
        val returnPage = invitationReturnPage(getBrowserLocation()?.pathname)
        if (workspaceId == null) {
            accessPhase = AccessPhase.WORKSPACE
            navigationState = AppNavigationState(AppRoute(AppPage.CASH_LEDGER))
        } else {
            accessPhase = AccessPhase.AUTHENTICATED
            val route = AppRoute(returnPage, workspaceId = workspaceId)
            navigationState = navigationState.copy(currentRoute = route)
            appPathForRoute(route)?.let(::replaceBrowserPath)
            onNavigate(route)
        }
    }

    val selectWorkspace: (String) -> Unit = { id ->
        scope.launch {
            accessBusy = true
            accessError = null
            try { updateSession(apiClient.selectWorkspace(id)) }
            catch (_: Throwable) { accessError = "无法切换工作区，请稍后重试。" }
            finally { accessBusy = false }
        }
    }

    val logout: () -> Unit = {
        scope.launch {
            accessBusy = true
            try { apiClient.logout() } catch (_: Throwable) { /* Local sign-out remains available. */ }
            session = null
            navigationState = navigationState.copy(backStack = emptyList())
            accessError = null
            invitationSignInRequested = false
            accessPhase = AccessPhase.SIGNED_OUT
            accessBusy = false
        }
    }

    FinanceTheme {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            when (accessPhase) {
                AccessPhase.LOADING -> SessionLoadingScreen()
                AccessPhase.SIGNED_OUT -> if (currentRoute.page == AppPage.INVITATION && !invitationSignInRequested) {
                    InvitationScreen(
                        api = apiClient,
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
                        scope.launch {
                            accessBusy = true
                            accessError = null
                            try {
                                val authenticated = if (registering) apiClient.register(email, password) else apiClient.login(email, password)
                                acceptSession(authenticated)
                            } catch (_: Throwable) {
                                accessError = "邮箱或密码不正确。请检查后重试。"
                            } finally {
                                accessBusy = false
                            }
                        }
                    },
                )
                AccessPhase.WORKSPACE -> session?.let { currentSession ->
                    WorkspaceAccessScreen(
                        session = currentSession,
                        busy = accessBusy,
                        requestError = accessError,
                        onSelect = { id ->
                            scope.launch {
                                accessBusy = true
                                accessError = null
                                try {
                                    acceptSession(apiClient.selectWorkspace(id), restoreRequestedWorkspace = false)
                                } catch (_: Throwable) {
                                    accessError = "无法切换工作区，请稍后重试。"
                                } finally {
                                    accessBusy = false
                                }
                            }
                        },
                        onCreate = { name ->
                            scope.launch {
                                accessBusy = true
                                accessError = null
                                try {
                                    acceptSession(apiClient.createWorkspace(name), restoreRequestedWorkspace = false)
                                } catch (_: Throwable) {
                                    accessError = "无法创建工作区，请检查名称后重试。"
                                } finally {
                                    accessBusy = false
                                }
                            }
                        },
                        onLogout = logout,
                        onRetry = {
                            scope.launch {
                                accessBusy = true
                                accessError = null
                                try {
                                    acceptSession(apiClient.session())
                                } catch (_: Throwable) {
                                    accessError = "无法读取账户，请稍后重试。"
                                } finally {
                                    accessBusy = false
                                }
                            }
                        },
                    )
                } ?: SessionLoadingScreen()
                AccessPhase.AUTHENTICATED -> {
                    val windowSizeClass = WindowSizeClass.fromWidthDp(maxWidth.value.toInt())
                    val currentSession = session
                    if (currentSession == null) {
                        SessionLoadingScreen()
                        return@BoxWithConstraints
                    }
                    val destination = remember(apiClient) {
                        movableContentOf<DestinationState> { state -> DestinationScaffold(state) }
                    }
                    val state = DestinationState(
                        route = currentRoute,
                        api = apiClient,
                        session = currentSession,
                        sizeClass = windowSizeClass,
                        onNavigate = selectPage,
                        canNavigateBack = getBrowserLocation() == null && routeHistory.isNotEmpty(),
                        onNavigateBack = navigateBack,
                        onNavigateBackOr = navigateBackOr,
                        onSessionUpdated = updateSession,
                        onWorkspaceDeleted = workspaceDeleted,
                        onInvitationAccepted = acceptInvitationSession,
                        onWorkspaceSelected = selectWorkspace,
                        onLogout = logout,
                    )
                    when (windowSizeClass) {
                        WindowSizeClass.COMPACT -> CompactAppShell(route = currentRoute, onNavigate = selectPage) { navigationIcon ->
                            destination(state.copy(navigationIcon = navigationIcon))
                        }
                        WindowSizeClass.REGULAR -> RegularAppShell(route = currentRoute, onNavigate = selectPage) { destination(state) }
                        WindowSizeClass.WIDE -> WideAppShell(route = currentRoute, onNavigate = selectPage) { destination(state) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactAppShell(
    route: AppRoute,
    onNavigate: (AppPage) -> Unit,
    content: @Composable (navigationIcon: @Composable () -> Unit) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                NavigationHeader()
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    NavigationDestinations(route.page) { page ->
                        onNavigate(page)
                        scope.launch { drawerState.close() }
                    }
                }
            }
        },
    ) {
        content {
                IconButton(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier
                        .semantics { contentDescription = "打开导航" }
                        .testTag("navigation-open"),
                ) {
                    MenuGlyph()
                }
        }
    }
}

@Composable
private fun MenuGlyph() {
    Column(
        modifier = Modifier.width(18.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(3) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.onSurface),
            )
        }
    }
}

@Composable
internal fun RegularAppShell(route: AppRoute, onNavigate: (AppPage) -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        NavigationRail(
            modifier = Modifier.width(80.dp),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            NavigationHeader(compact = true)
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NavigationRailDestinations(route.page, onNavigate)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

@Composable
internal fun WideAppShell(route: AppRoute, onNavigate: (AppPage) -> Unit, content: @Composable () -> Unit) {
    PermanentNavigationDrawer(
        drawerContent = {
            PermanentDrawerSheet(Modifier.width(256.dp)) {
                NavigationHeader()
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    NavigationDestinations(route.page, onNavigate)
                }
            }
        },
    ) {
        content()
    }
}

@Composable
private fun NavigationHeader(compact: Boolean = false) {
    if (compact) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            BrandMark()
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark()
            Text(
                "财务账本",
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "C",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun NavigationDestinations(selectedPage: AppPage, onNavigate: (AppPage) -> Unit) {
    appDestinations.forEach { destination ->
        NavigationDrawerItem(
            label = { Text(destination.title) },
            selected = selectedPage == destination.page,
            onClick = { onNavigate(destination.page) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .testTag(destination.semanticId),
        )
    }
}

@Composable
private fun NavigationRailDestinations(selectedPage: AppPage, onNavigate: (AppPage) -> Unit) {
    appDestinations.forEach { destination ->
        val selected = selectedPage == destination.page
        NavigationRailItem(
            selected = selected,
            onClick = { onNavigate(destination.page) },
            icon = {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .then(
                            if (selected) {
                                Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                            } else {
                                Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            },
                        ),
                )
            },
            label = { Text(destination.title) },
            alwaysShowLabel = true,
            modifier = Modifier.testTag(destination.semanticId),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationScaffold(
    state: DestinationState,
    modifier: Modifier = Modifier,
) {
    val canWrite = state.session.workspaces.firstOrNull { it.id == state.session.activeWorkspaceId }?.role?.canWrite == true
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("财务账本") },
                navigationIcon = state.navigationIcon,
                actions = {
                    if (state.canNavigateBack) {
                        TextButton(onClick = state.onNavigateBack) { Text("返回") }
                    }
                    WorkspaceMenu(
                        session = state.session,
                        onSelect = state.onWorkspaceSelected,
                        onLogout = state.onLogout,
                    )
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
            ) {
            when (state.route.page) {
                AppPage.CASH_LEDGER -> CashLedgerScreen(state.api, state.session, state.sizeClass, state.onNavigate)
                AppPage.CASH_IMPORT -> CashImportScreen(
                    api = state.api,
                    sizeClass = state.sizeClass,
                    canWrite = canWrite,
                    onBack = { state.onNavigateBackOr(AppPage.CASH_LEDGER) },
                    onDone = {},
                )
                AppPage.CASH_CATEGORIES -> CashCategoryScreen(
                    state.api,
                    state.sizeClass,
                    canWrite,
                )
                AppPage.INVESTMENT_HOLDINGS -> InvestmentHoldingsScreen(state.api, state.sizeClass)
                AppPage.INVESTMENT_EVENTS -> InvestmentEventsScreen(state.api, state.sizeClass)
                AppPage.WORKSPACE_MANAGEMENT -> WorkspaceManagementScreen(
                    api = state.api,
                    session = state.session,
                    sizeClass = state.sizeClass,
                    onSessionUpdated = state.onSessionUpdated,
                    onWorkspaceDeleted = state.onWorkspaceDeleted,
                )
                AppPage.INVITATION -> InvitationScreen(
                    api = state.api,
                    token = state.route.invitationToken.orEmpty(),
                    session = state.session,
                    onRequireSignIn = {},
                    onAccepted = state.onInvitationAccepted,
                    onCancel = {
                        IncomingInvitationLinks.clear(state.route.invitationToken)
                        state.onNavigateBackOr(invitationReturnPage(getBrowserLocation()?.pathname))
                    },
                )
                AppPage.NOT_FOUND -> FeaturePage("页面不存在", "page.not-found", Modifier.fillMaxSize().padding(24.dp)) {
                    StateMessage("找不到此页面。")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceMenu(
    session: SessionDto,
    onSelect: (String) -> Unit,
    onLogout: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = session.workspaces.firstOrNull { it.id == session.activeWorkspaceId }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(SemanticIds.workspaceSwitcher),
        ) { Text(active?.name ?: session.user.email) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            session.workspaces.forEach { workspace ->
                DropdownMenuItem(
                    text = { Text("${workspace.name} · ${workspace.role.label}") },
                    onClick = { expanded = false; if (workspace.id != session.activeWorkspaceId) onSelect(workspace.id) },
                )
            }
            HorizontalMenuDivider()
            DropdownMenuItem(text = { Text("退出登录") }, onClick = { expanded = false; onLogout() })
        }
    }
}

@Composable
private fun HorizontalMenuDivider() {
    androidx.compose.material3.HorizontalDivider()
}
