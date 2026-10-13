package com.finance.tracker.app

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.PopupProperties
import com.composeunstyled.UnstyledButton
import com.finance.tracker.core.design.FinanceTokens
import com.finance.tracker.core.design.GeneratedTokens
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompactAppShell(
    state: DestinationState,
    content: @Composable (navigationIcon: @Composable () -> Unit) -> Unit,
) {
    val openNavigationLabel = localizedText("copy_99aaea15ca")
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val colors = FinanceTokens.forTheme(androidx.compose.foundation.isSystemInDarkTheme())
    var accountSheetOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(304.dp),
                drawerContainerColor = colors.surface,
                drawerContentColor = colors.foreground,
            ) {
                Column(Modifier.fillMaxSize()) {
                    NavigationHeader()
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        NavigationDestinations(
                            selectedPage = primaryPageFor(state.route.page),
                            expanded = true,
                            onNavigate = { page ->
                                state.onNavigate(page)
                                scope.launch { drawerState.close() }
                            },
                        )
                    }
                    CompactAccountTrigger(
                        session = state.session,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        onClick = { accountSheetOpen = true },
                    )
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
                MenuGlyph(colors.foreground)
            }
        }
    }
    if (accountSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { accountSheetOpen = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            containerColor = colors.surface,
            contentColor = colors.foreground,
            tonalElevation = 0.dp,
            dragHandle = { BottomSheetDefaults.DragHandle(color = colors.fieldBorder) },
        ) {
            AccountPanelContent(
                session = state.session,
                busy = state.accessBusy,
                error = state.accessError,
                onWorkspaceSelected = state.onWorkspaceSelected,
                onManageWorkspaces = {
                    scope.launch {
                        sheetState.hide()
                        drawerState.close()
                        accountSheetOpen = false
                        state.onNavigate(AppPage.WORKSPACE_MANAGEMENT)
                    }
                },
                onSignOut = {
                    scope.launch {
                        sheetState.hide()
                        drawerState.close()
                        accountSheetOpen = false
                        state.onLogout()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 700.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun MenuGlyph(color: Color) {
    RailGlyph(NavigationGlyphKind.MENU, color, Modifier.size(20.dp))
}

@Composable
internal fun RegularAppShell(
    state: DestinationState,
    content: @Composable () -> Unit,
) {
    PersistentNavigationShell(state, content)
}

@Composable
internal fun WideAppShell(
    state: DestinationState,
    content: @Composable () -> Unit,
) {
    PersistentNavigationShell(state, content)
}

private class AccountMenuKeyboardHandler {
    var onEscape: () -> Boolean = { false }
}

@Composable
private fun NavigationHeader() {
    Box(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(localizedText("copy_2e5de483d7"), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PersistentNavigationShell(state: DestinationState, content: @Composable () -> Unit) {
    val colors = FinanceTokens.forTheme(androidx.compose.foundation.isSystemInDarkTheme())
    val railHoverSource = remember { MutableInteractionSource() }
    val accountMenuKeyboardHandler = remember { AccountMenuKeyboardHandler() }
    val railHovered by railHoverSource.collectIsHoveredAsState()
    var railHasFocus by remember { mutableStateOf(false) }
    var keyboardNavigationActive by remember { mutableStateOf(false) }
    var accountPanelHovered by remember { mutableStateOf(false) }
    var accountPanelHasFocus by remember { mutableStateOf(false) }
    var accountMenuHoverOpen by remember { mutableStateOf(false) }
    var railToggleFocused by remember { mutableStateOf(false) }
    var pinnedExpanded by remember { mutableStateOf(false) }
    var collapseUntilTransientExit by remember { mutableStateOf(false) }
    val railHasKeyboardFocus = railHasFocus && keyboardNavigationActive
    val transientExpansionRequested = railHovered || railHasKeyboardFocus || accountPanelHovered || accountPanelHasFocus || accountMenuHoverOpen
    val expanded = pinnedExpanded || (transientExpansionRequested && !collapseUntilTransientExit)
    LaunchedEffect(railHovered) {
        if (railHovered) keyboardNavigationActive = false
    }
    LaunchedEffect(railHovered, railHasKeyboardFocus, accountPanelHovered, accountPanelHasFocus, collapseUntilTransientExit) {
        if (collapseUntilTransientExit && !railHovered && !railHasKeyboardFocus && !accountPanelHovered && !accountPanelHasFocus) {
            collapseUntilTransientExit = false
        }
    }
    val railWidth by animateDpAsState(
        targetValue = if (expanded) 240.dp else 64.dp,
        animationSpec = tween(durationMillis = 160),
        label = "navigation-rail-width",
    )

    Row(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    if (event.key == Key.Tab) keyboardNavigationActive = true
                    if (event.key == Key.Escape) accountMenuKeyboardHandler.onEscape() else false
                } else {
                    false
                }
            },
    ) {
        Column(
            modifier = Modifier
                .width(railWidth)
                .fillMaxHeight()
                .background(colors.surface)
                .hoverable(railHoverSource)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) {
                                keyboardNavigationActive = false
                            }
                        }
                    }
                }
                .onFocusChanged {
                    railHasFocus = it.hasFocus
                    keyboardNavigationActive = it.hasFocus && !railHovered
                }
                .border(1.dp, colors.fieldBorder.copy(alpha = 0.25f))
                .padding(horizontal = if (expanded) 12.dp else 8.dp, vertical = 16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                val toggleLabel = localizedText(if (expanded) "navigation_rail_collapse" else "navigation_rail_expand")
                UnstyledButton(
                    onClick = {
                        if (expanded) {
                            pinnedExpanded = false
                            collapseUntilTransientExit = transientExpansionRequested
                        } else {
                            pinnedExpanded = true
                            collapseUntilTransientExit = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .onFocusChanged { railToggleFocused = it.isFocused }
                        .focusable()
                        .border(if (railToggleFocused) 2.dp else 0.dp, if (railToggleFocused) colors.focus else Color.Transparent, RoundedCornerShape(8.dp))
                        .semantics { contentDescription = toggleLabel }
                        .testTag(SemanticIds.navigationRailToggle),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = if (expanded) Alignment.CenterStart else Alignment.Center) {
                        RailGlyph(
                            if (expanded) NavigationGlyphKind.CHEVRON_LEFT else NavigationGlyphKind.CHEVRON_RIGHT,
                            colors.mutedForShell(),
                            Modifier.padding(start = if (expanded) 12.dp else 0.dp).size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = colors.fieldBorder.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    NavigationDestinations(
                        selectedPage = primaryPageFor(state.route.page),
                        expanded = expanded,
                        showFocusRing = keyboardNavigationActive,
                        onNavigate = state.onNavigate,
                    )
                }
            }
            DesktopAccountMenu(
                session = state.session,
                expandedRail = expanded,
                busy = state.accessBusy,
                error = state.accessError,
                onWorkspaceSelected = state.onWorkspaceSelected,
                onManageWorkspaces = { state.onNavigate(AppPage.WORKSPACE_MANAGEMENT) },
                onSignOut = state.onLogout,
                onPanelHoveredChanged = { accountPanelHovered = it },
                onPanelFocusChanged = { accountPanelHasFocus = it && keyboardNavigationActive },
                onHoverMenuOpenChanged = { accountMenuHoverOpen = it },
                keyboardHandler = accountMenuKeyboardHandler,
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

@Composable
private fun NavigationDestinations(
    selectedPage: AppPage,
    expanded: Boolean,
    showFocusRing: Boolean = true,
    onNavigate: (AppPage) -> Unit,
) {
    appDestinations.forEach { destination ->
        NavigationDestinationItem(
            page = destination.page,
            label = localizedText(destination.titleKey),
            selected = selectedPage == destination.page,
            expanded = expanded,
            showFocusRing = showFocusRing,
            semanticId = destination.semanticId,
            onClick = { onNavigate(destination.page) },
        )
    }
}

@Composable
private fun NavigationDestinationItem(
    page: AppPage,
    label: String,
    selected: Boolean,
    expanded: Boolean,
    showFocusRing: Boolean,
    semanticId: String,
    onClick: () -> Unit,
) {
    val colors = FinanceTokens.forTheme(androidx.compose.foundation.isSystemInDarkTheme())
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    UnstyledButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .focusable()
            .hoverable(interactionSource)
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                contentDescription = label
                this.selected = selected
            }
            .testTag(semanticId),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(if (selected || hovered) colors.field else Color.Transparent, shape)
                .border(if (focused && showFocusRing) 2.dp else 0.dp, if (focused && showFocusRing) colors.focus else Color.Transparent, shape)
                .padding(horizontal = if (expanded) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        ) {
            Box(Modifier.width(if (expanded) 4.dp else 3.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (selected) Box(Modifier.width(3.dp).height(28.dp).background(colors.accent, RoundedCornerShape(2.dp)))
            }
            RailGlyph(
                kind = navigationGlyphFor(page),
                color = if (selected) colors.accent else colors.mutedForShell(),
                modifier = Modifier.padding(start = if (expanded) 4.dp else 0.dp).size(20.dp),
            )
            if (expanded) {
                Text(
                    label,
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) colors.foreground else colors.mutedForShell(),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopAccountMenu(
    session: Session,
    expandedRail: Boolean,
    busy: Boolean,
    error: String?,
    onWorkspaceSelected: (String) -> Unit,
    onManageWorkspaces: () -> Unit,
    onSignOut: () -> Unit,
    onPanelHoveredChanged: (Boolean) -> Unit,
    onPanelFocusChanged: (Boolean) -> Unit,
    onHoverMenuOpenChanged: (Boolean) -> Unit,
    keyboardHandler: AccountMenuKeyboardHandler,
) {
    val colors = FinanceTokens.forTheme(androidx.compose.foundation.isSystemInDarkTheme())
    val menuColorScheme = MaterialTheme.colorScheme.copy(
        surfaceContainer = colors.surface,
        surfaceContainerLow = colors.surface,
        surfaceContainerHigh = colors.surface,
        surfaceContainerHighest = colors.surface,
        surfaceContainerLowest = colors.surface,
    )
    val triggerInteraction = remember { MutableInteractionSource() }
    val panelInteraction = remember { MutableInteractionSource() }
    val triggerHovered by triggerInteraction.collectIsHoveredAsState()
    val panelHovered by panelInteraction.collectIsHoveredAsState()
    var clickOpen by remember { mutableStateOf(false) }
    var hoverOpen by remember { mutableStateOf(false) }
    var suppressHoverUntilLeave by remember { mutableStateOf(false) }
    val triggerFocusRequester = remember { FocusRequester() }
    val expanded = clickOpen || hoverOpen
    val dismissMenu = {
        val hoverRegionWasActive = triggerHovered || panelHovered || hoverOpen
        clickOpen = false
        hoverOpen = false
        suppressHoverUntilLeave = hoverRegionWasActive
        onPanelHoveredChanged(false)
        onPanelFocusChanged(false)
    }
    PlatformEscapeHandler(expanded) {
        dismissMenu()
        triggerFocusRequester.requestFocus()
    }
    keyboardHandler.onEscape = {
        if (expanded) {
            dismissMenu()
            triggerFocusRequester.requestFocus()
            true
        } else {
            false
        }
    }

    LaunchedEffect(triggerHovered, panelHovered, clickOpen, suppressHoverUntilLeave) {
        if (triggerHovered && !clickOpen && !suppressHoverUntilLeave) hoverOpen = true
        if (!clickOpen && hoverOpen && !triggerHovered && !panelHovered) {
            delay(160)
            if (!triggerHovered && !panelHovered && !clickOpen) hoverOpen = false
        }
        if (!triggerHovered && !panelHovered && suppressHoverUntilLeave) {
            delay(120)
            if (!triggerHovered && !panelHovered) suppressHoverUntilLeave = false
        }
    }
    LaunchedEffect(panelHovered) { onPanelHoveredChanged(panelHovered) }
    LaunchedEffect(hoverOpen) { onHoverMenuOpenChanged(hoverOpen) }

    MaterialTheme(colorScheme = menuColorScheme) {
        Box {
            AccountTrigger(
                session = session,
                expanded = expandedRail,
                interactionSource = triggerInteraction,
                modifier = Modifier.focusRequester(triggerFocusRequester),
                onClick = {
                    if (clickOpen) {
                        dismissMenu()
                    } else {
                        clickOpen = true
                        hoverOpen = false
                        suppressHoverUntilLeave = false
                        triggerFocusRequester.requestFocus()
                    }
                },
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = dismissMenu,
                properties = PopupProperties(focusable = clickOpen),
                modifier = Modifier
                    .width(320.dp)
                    .heightIn(max = 680.dp)
                    .border(1.dp, colors.fieldBorder.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
            ) {
                AccountPanelContent(
                    session = session,
                    busy = busy,
                    error = error,
                    onWorkspaceSelected = onWorkspaceSelected,
                    onManageWorkspaces = {
                        clickOpen = false
                        hoverOpen = false
                        onManageWorkspaces()
                    },
                    onSignOut = {
                        clickOpen = false
                        hoverOpen = false
                        onSignOut()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 680.dp)
                        .verticalScroll(rememberScrollState())
                        .hoverable(panelInteraction)
                        .onFocusChanged { onPanelFocusChanged(it.hasFocus) }
                        .padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun CompactAccountTrigger(
    session: Session,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AccountTrigger(
        session = session,
        expanded = true,
        modifier = modifier,
        onClick = onClick,
    )
}

@Composable
private fun AccountTrigger(
    session: Session,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
) {
    val colors = FinanceTokens.forTheme(androidx.compose.foundation.isSystemInDarkTheme())
    val description = localizedText("account_panel_label")
    val shape = RoundedCornerShape(8.dp)
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    UnstyledButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(colors.field, shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.Spacebar)) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .border(if (focused) 2.dp else 0.dp, if (focused) colors.focus else Color.Transparent, shape)
            .semantics { contentDescription = description }
            .testTag(SemanticIds.workspaceSwitcher),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = if (expanded) 8.dp else 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        ) {
            Box(
                Modifier
                    .width(40.dp)
                    .height(36.dp)
                    .then(if (expanded) Modifier.offset(x = (-8).dp) else Modifier),
            ) {
                val avatarHoverModifier = if (interactionSource != null) Modifier.hoverable(interactions) else Modifier
                AccountAvatar(session.user.email, Modifier.size(36.dp).align(Alignment.Center).then(avatarHoverModifier))
            }
            if (expanded) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        session.user.email,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        description,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = colors.mutedForShell(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                RailGlyph(NavigationGlyphKind.CHEVRON_DOWN, colors.mutedForShell(), Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AccountAvatar(email: String, modifier: Modifier = Modifier) {
    val initial = email.firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = modifier.background(GeneratedTokens.Core.Color.BrandPrimary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = GeneratedTokens.Core.Color.BrandOnPrimary, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun AccountPanelContent(
    session: Session,
    busy: Boolean,
    error: String?,
    onWorkspaceSelected: (String) -> Unit,
    onManageWorkspaces: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FinanceTokens.forTheme(androidx.compose.foundation.isSystemInDarkTheme())
    val retryLabel = localizedText("copy_e2d53a6d3a")
    var pendingWorkspaceId by remember(session.user.email) { mutableStateOf<String?>(null) }
    var workspaceSwitchSucceeded by remember(session.user.email) { mutableStateOf(false) }
    LaunchedEffect(busy, error, session.activeWorkspaceId, pendingWorkspaceId) {
        val pendingId = pendingWorkspaceId
        if (pendingId != null && !busy && error == null && session.activeWorkspaceId == pendingId) {
            workspaceSwitchSucceeded = true
            pendingWorkspaceId = null
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().testTag(SemanticIds.accountMenu),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(session.user.email, Modifier.size(36.dp))
            Text(
                session.user.email,
                modifier = Modifier.padding(start = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        HorizontalDivider(color = colors.fieldBorder.copy(alpha = 0.5f))
        Text(localizedText("account_workspaces_heading"), style = MaterialTheme.typography.labelLarge, color = colors.mutedForShell())
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            session.workspaces.forEach { workspace ->
                val isCurrent = workspace.id == session.activeWorkspaceId
                AccountWorkspaceOption(
                    workspace = workspace,
                    current = isCurrent,
                    busy = busy,
                    loading = busy && pendingWorkspaceId == workspace.id,
                    onClick = {
                        if (!isCurrent && !busy) {
                            pendingWorkspaceId = workspace.id
                            workspaceSwitchSucceeded = false
                            onWorkspaceSelected(workspace.id)
                        }
                    },
                )
            }
        }
        if (busy && pendingWorkspaceId != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(localizedText("account_workspace_switching"), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (error != null && pendingWorkspaceId != null) {
            Text(error, color = colors.danger, style = MaterialTheme.typography.bodySmall)
            UnstyledButton(
                onClick = { onWorkspaceSelected(pendingWorkspaceId.orEmpty()) },
                enabled = !busy,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .focusable()
                    .semantics {
                        contentDescription = retryLabel
                        if (busy) disabled()
                    }
                    .testTag(SemanticIds.accountWorkspaceRetry),
            ) {
                Text(retryLabel, color = colors.accent, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (workspaceSwitchSucceeded) {
            Text(
                localizedText("account_workspace_switch_success"),
                color = colors.accent,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(SemanticIds.accountWorkspaceSuccess),
            )
        }
        HorizontalDivider(color = colors.fieldBorder.copy(alpha = 0.5f))
        AccountAction(
            label = localizedText("copy_e0617c59d4"),
            icon = NavigationGlyphKind.SETTINGS,
            enabled = !busy,
            semanticId = SemanticIds.accountManageWorkspaces,
            onClick = onManageWorkspaces,
        )
        AccountAction(
            label = localizedText("copy_094774b4a7"),
            icon = NavigationGlyphKind.SIGN_OUT,
            enabled = !busy,
            semanticId = SemanticIds.workspaceLogout,
            onClick = onSignOut,
        )
    }
}

@Composable
private fun AccountWorkspaceOption(
    workspace: Workspace,
    current: Boolean,
    busy: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val colors = FinanceTokens.forTheme(androidx.compose.foundation.isSystemInDarkTheme())
    val shape = RoundedCornerShape(8.dp)
    val roleLabel = workspaceRoleLabel(workspace.role)
    val currentLabel = localizedText("account_workspace_current")
    val accessibilityLabel = buildString {
        append(workspace.name)
        append(". ")
        append(roleLabel)
        if (current) append(". ").append(currentLabel)
    }
    var focused by remember { mutableStateOf(false) }
    UnstyledButton(
        onClick = onClick,
        enabled = !busy && !current,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (current) colors.field else Color.Transparent, shape)
            .border(if (focused) 2.dp else 0.dp, if (focused) colors.focus else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .semantics {
                contentDescription = accessibilityLabel
                selected = current
                if (busy || current) disabled()
            }
            .testTag(SemanticIds.accountWorkspaceOption),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RailGlyph(
                NavigationGlyphKind.WALLET,
                if (current) colors.accent else colors.mutedForShell(),
                Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(workspace.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (current) "$roleLabel · $currentLabel" else roleLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.mutedForShell(),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else if (current) {
                RailGlyph(NavigationGlyphKind.CHECK, colors.accent, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AccountAction(
    label: String,
    icon: NavigationGlyphKind,
    enabled: Boolean,
    semanticId: String,
    onClick: () -> Unit,
) {
    val colors = FinanceTokens.forTheme(androidx.compose.foundation.isSystemInDarkTheme())
    val shape = RoundedCornerShape(8.dp)
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .hoverable(interactions)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
            }
            .testTag(semanticId),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(if (hovered) colors.field else Color.Transparent, shape)
                .border(if (focused) 2.dp else 0.dp, if (focused) colors.focus else Color.Transparent, shape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RailGlyph(icon, colors.mutedForShell(), Modifier.size(20.dp))
            Text(label, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private enum class NavigationGlyphKind {
    MENU,
    CHEVRON_LEFT,
    CHEVRON_RIGHT,
    CHEVRON_DOWN,
    WALLET,
    IMPORT,
    TAG,
    HOLDINGS,
    EVENTS,
    SETTINGS,
    SIGN_OUT,
    CHECK,
}

private fun navigationGlyphFor(page: AppPage): NavigationGlyphKind = when (page) {
    AppPage.CASH_LEDGER -> NavigationGlyphKind.WALLET
    AppPage.CASH_IMPORT -> NavigationGlyphKind.IMPORT
    AppPage.CASH_CATEGORIES -> NavigationGlyphKind.TAG
    AppPage.INVESTMENT_HOLDINGS -> NavigationGlyphKind.HOLDINGS
    AppPage.INVESTMENT_EVENTS -> NavigationGlyphKind.EVENTS
    AppPage.WORKSPACE_MANAGEMENT -> NavigationGlyphKind.SETTINGS
    else -> NavigationGlyphKind.WALLET
}

@Composable
private fun RailGlyph(kind: NavigationGlyphKind, color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        val stroke = 1.8f * unit
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun path(vararg coordinates: Float, close: Boolean = false) {
            if (coordinates.size < 4) return
            val shape = Path().apply {
                moveTo(coordinates[0] * unit, coordinates[1] * unit)
                var index = 2
                while (index + 1 < coordinates.size) {
                    lineTo(coordinates[index] * unit, coordinates[index + 1] * unit)
                    index += 2
                }
                if (close) this.close()
            }
            drawPath(shape, color, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        fun outlineRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float = 2f) {
            drawRoundRect(
                color = color,
                topLeft = point(left, top),
                size = Size((right - left) * unit, (bottom - top) * unit),
                cornerRadius = CornerRadius(radius * unit),
                style = Stroke(stroke),
            )
        }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, point(x1, y1), point(x2, y2), stroke)
        when (kind) {
            NavigationGlyphKind.MENU -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f) }
            NavigationGlyphKind.CHEVRON_LEFT -> path(15f, 5f, 8f, 12f, 15f, 19f)
            NavigationGlyphKind.CHEVRON_RIGHT -> path(9f, 5f, 16f, 12f, 9f, 19f)
            NavigationGlyphKind.CHEVRON_DOWN -> path(5f, 9f, 12f, 15f, 19f, 9f)
            NavigationGlyphKind.WALLET -> {
                outlineRect(3f, 6f, 21f, 19f, 2.5f)
                path(3f, 9f, 16f, 9f, 16f, 6f)
                path(15f, 12f, 21f, 12f, 21f, 16f, 15f, 16f, 15f, 12f, close = true)
                drawCircle(color, 0.8f * unit, point(18f, 14f))
            }
            NavigationGlyphKind.IMPORT -> {
                path(12f, 3f, 12f, 14f)
                path(8f, 10f, 12f, 14f, 16f, 10f)
                path(5f, 15f, 5f, 20f, 19f, 20f, 19f, 15f)
            }
            NavigationGlyphKind.TAG -> {
                path(3f, 11f, 11f, 3f, 20f, 3f, 21f, 13f, 13f, 21f, 3f, 11f, close = true)
                drawCircle(color, 1f * unit, point(15.5f, 8f), style = Stroke(stroke))
            }
            NavigationGlyphKind.HOLDINGS -> {
                drawArc(color, -90f, 270f, false, point(3f, 3f), Size(18f * unit, 18f * unit), style = Stroke(stroke))
                path(12f, 4f, 12f, 12f, 19f, 16f)
            }
            NavigationGlyphKind.EVENTS -> {
                outlineRect(3f, 5f, 21f, 21f, 2f)
                line(3f, 9f, 21f, 9f)
                line(8f, 3f, 8f, 7f)
                line(16f, 3f, 16f, 7f)
                line(7f, 13f, 9f, 13f)
                line(13f, 13f, 15f, 13f)
            }
            NavigationGlyphKind.SETTINGS -> {
                line(4f, 6f, 20f, 6f)
                line(4f, 12f, 20f, 12f)
                line(4f, 18f, 20f, 18f)
                drawCircle(color, 2f * unit, point(9f, 6f))
                drawCircle(color, 2f * unit, point(15f, 12f))
                drawCircle(color, 2f * unit, point(8f, 18f))
            }
            NavigationGlyphKind.SIGN_OUT -> {
                path(10f, 4f, 5f, 4f, 5f, 20f, 10f, 20f)
                path(13f, 8f, 17f, 12f, 13f, 16f)
                line(17f, 12f, 9f, 12f)
            }
            NavigationGlyphKind.CHECK -> path(4f, 12f, 9f, 17f, 20f, 6f)
        }
    }
}

private fun com.finance.tracker.core.design.FinanceColorRoles.mutedForShell(): Color = foreground.copy(alpha = 0.68f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DestinationTopBarTitle(page: AppPage) {
    Text(
        localizedText(titleKeyFor(page)),
        modifier = Modifier.testTag(SemanticIds.destinationTopBarTitle),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DestinationScaffold(
    state: DestinationState,
    modifier: Modifier = Modifier,
) {
    val canWrite = state.session.workspaces.firstOrNull { it.id == state.session.activeWorkspaceId }?.role?.canWrite == true
    val shellColors = FinanceTokens.forTheme(androidx.compose.foundation.isSystemInDarkTheme())
    Scaffold(
        modifier = modifier,
        containerColor = shellColors.background,
        topBar = {
            TopAppBar(
                title = { DestinationTopBarTitle(primaryPageFor(state.route.page)) },
                navigationIcon = state.navigationIcon,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = shellColors.surface,
                    titleContentColor = shellColors.foreground,
                    navigationIconContentColor = shellColors.foreground,
                    actionIconContentColor = shellColors.foreground,
                ),
                actions = {
                    if (state.canNavigateBack) {
                        TextButton(onClick = state.onNavigateBack) { Text(localizedText("copy_11d0241540")) }
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
            ) {
            when (primaryPageFor(state.route.page)) {
                AppPage.CASH_LEDGER -> CashLedgerScreen(
                    state.financeRepository,
                    state.session,
                    state.sizeClass,
                    state.onNavigate,
                    state.diagnosticLogger,
                    state.importButtonFocusRequester,
                )
                AppPage.CASH_IMPORT -> Unit
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
                AppPage.NOT_FOUND -> FeaturePage(localizedText("copy_55c9e10608"), "page.not-found", Modifier.fillMaxSize().padding(24.dp), showTitle = false) {
                    StateMessage(localizedText("copy_c2bed2ec5b"))
                }
            }
        }
    }
}
