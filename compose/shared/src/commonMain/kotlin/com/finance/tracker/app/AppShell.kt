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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompactAppShell(
    route: AppRoute,
    onNavigate: (AppPage) -> Unit,
    content: @Composable (navigationIcon: @Composable () -> Unit) -> Unit,
) {
    val openNavigationLabel = localizedText("copy_99aaea15ca")
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
                        .semantics { contentDescription = openNavigationLabel }
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
                localizedText("copy_2e5de483d7"),
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
            label = { Text(localizedText(destination.titleKey)) },
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
            label = { Text(localizedText(destination.titleKey)) },
            alwaysShowLabel = true,
            modifier = Modifier.testTag(destination.semanticId),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DestinationScaffold(
    state: DestinationState,
    modifier: Modifier = Modifier,
) {
    val canWrite = state.session.workspaces.firstOrNull { it.id == state.session.activeWorkspaceId }?.role?.canWrite == true
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(localizedText("copy_2e5de483d7")) },
                navigationIcon = state.navigationIcon,
                actions = {
                    if (state.canNavigateBack) {
                        TextButton(onClick = state.onNavigateBack) { Text(localizedText("copy_11d0241540")) }
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
                AppPage.CASH_LEDGER -> CashLedgerScreen(state.financeRepository, state.session, state.sizeClass, state.onNavigate, state.diagnosticLogger)
                AppPage.CASH_IMPORT -> CashImportScreen(
                    repository = state.financeRepository,
                    workspaceId = state.session.activeWorkspaceId.orEmpty(),
                    sizeClass = state.sizeClass,
                    canWrite = canWrite,
                    onBack = { state.onNavigateBackOr(AppPage.CASH_LEDGER) },
                    onDone = {},
                    diagnostics = state.diagnosticLogger,
                )
                AppPage.CASH_CATEGORIES -> CashCategoryScreen(
                    repository = state.financeRepository,
                    workspaceId = state.session.activeWorkspaceId.orEmpty(),
                    sizeClass = state.sizeClass,
                    canWrite = canWrite,
                    diagnostics = state.diagnosticLogger,
                )
                AppPage.INVESTMENT_HOLDINGS -> InvestmentHoldingsScreen(
                    state.financeRepository,
                    state.session.activeWorkspaceId.orEmpty(),
                    state.sizeClass,
                    state.diagnosticLogger,
                )
                AppPage.INVESTMENT_EVENTS -> InvestmentEventsScreen(
                    state.financeRepository,
                    state.session.activeWorkspaceId.orEmpty(),
                    state.sizeClass,
                    state.diagnosticLogger,
                )
                AppPage.WORKSPACE_MANAGEMENT -> WorkspaceManagementScreen(
                    repository = state.sessionRepository,
                    workspaceId = state.session.activeWorkspaceId.orEmpty(),
                    session = state.session,
                    sizeClass = state.sizeClass,
                    onSessionUpdated = state.onSessionUpdated,
                    onWorkspaceDeleted = state.onWorkspaceDeleted,
                    diagnostics = state.diagnosticLogger,
                )
                AppPage.INVITATION -> InvitationScreen(
                    repository = state.sessionRepository,
                    token = state.route.invitationToken.orEmpty(),
                    session = state.session,
                    onRequireSignIn = {},
                    onAccepted = state.onInvitationAccepted,
                    onCancel = {
                        IncomingInvitationLinks.clear(state.route.invitationToken)
                        state.onNavigateBackOr(invitationReturnPage(getBrowserLocation()?.pathname))
                    },
                    diagnostics = state.diagnosticLogger,
                )
                AppPage.NOT_FOUND -> FeaturePage(localizedText("copy_55c9e10608"), "page.not-found", Modifier.fillMaxSize().padding(24.dp)) {
                    StateMessage(localizedText("copy_c2bed2ec5b"))
                }
            }
        }
    }
}

@Composable
private fun WorkspaceMenu(
    session: Session,
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
                    text = { Text("${workspace.name} · ${workspaceRoleLabel(workspace.role)}") },
                    onClick = { expanded = false; if (workspace.id != session.activeWorkspaceId) onSelect(workspace.id) },
                )
            }
            HorizontalMenuDivider()
            DropdownMenuItem(text = { Text(localizedText("copy_094774b4a7")) }, onClick = { expanded = false; onLogout() })
        }
    }
}

@Composable
private fun HorizontalMenuDivider() {
    androidx.compose.material3.HorizontalDivider()
}
