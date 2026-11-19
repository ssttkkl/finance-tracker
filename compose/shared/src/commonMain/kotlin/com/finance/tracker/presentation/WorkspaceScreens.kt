package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField

@Composable
internal fun InvitationScreen(
    repository: SessionRepository,
    token: String,
    session: Session?,
    onRequireSignIn: () -> Unit,
    onAccepted: (Session) -> Unit,
    onCancel: () -> Unit,
    diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) {
    val invitationViewModel: InvitationViewModel = viewModel(
        key = "invitation:$token",
        factory = viewModelFactory { initializer { InvitationViewModel(repository, diagnostics) } },
    )
    val invitationState by invitationViewModel.state.collectAsStateWithLifecycle()
    val error = invitationState.errorCode?.let { localizedText(invitationErrorResourceKey(it)) }
    LaunchedEffect(invitationViewModel, token) { invitationViewModel.load(token) }
    LaunchedEffect(invitationState.acceptedSession) {
        invitationState.acceptedSession?.let { accepted ->
            onAccepted(accepted)
            invitationViewModel.consumeAcceptedSession()
        }
    }

    if (session == null) {
        AuthResponsiveShell { modifier, compact, _, _ ->
            AuthEntryCard(compact = compact, modifier = modifier.widthIn(max = 440.dp)) {
                InvitationContent(
                    state = invitationState,
                    error = error,
                    isAuthenticated = false,
                    onRetry = { invitationViewModel.retry(token) },
                    onRequireSignIn = onRequireSignIn,
                    onAccept = { invitationViewModel.accept(token) },
                    onCancel = onCancel,
                )
            }
        }
    } else {
        FeaturePage(
            localizedText("copy_d7f050d6f3"),
            SemanticIds.invitationScreen,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            showTitle = false,
        ) {
            SectionCard(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
                InvitationContent(
                    state = invitationState,
                    error = error,
                    isAuthenticated = true,
                    onRetry = { invitationViewModel.retry(token) },
                    onRequireSignIn = onRequireSignIn,
                    onAccept = { invitationViewModel.accept(token) },
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun InvitationContent(
    state: InvitationUiState,
    error: String?,
    isAuthenticated: Boolean,
    onRetry: () -> Unit,
    onRequireSignIn: () -> Unit,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    val connection = LocalSessionConnection.current
    if (isAuthenticated) SessionConnectionNotice()
    val preview = state.preview
    when {
        state.loading -> Text(localizedText("copy_d347833ffd"))
        preview == null -> {
            Text(localizedText("copy_73e6ee9610"), style = MaterialTheme.typography.titleLarge)
            InlineError(error)
            if (!state.terminalPreviewError) TextButton(onClick = onRetry) { Text(localizedText("copy_e2d53a6d3a")) }
            TextButton(onClick = onCancel) { Text(localizedText("copy_11d0241540")) }
        }
        preview.valid == false -> {
            Text(localizedText("copy_ec0fa251c2"), style = MaterialTheme.typography.titleLarge)
            Text(localizedText("copy_40d123e2b5"))
            TextButton(onClick = onCancel) { Text(localizedText("copy_11d0241540")) }
        }
        else -> {
            Text(localizedText("copy_4d2ee8a422", preview.workspace.name), style = MaterialTheme.typography.titleLarge)
            Text(if (preview.role == WorkspaceRole.EDITOR) localizedText("copy_e85cf84fa9") else localizedText("copy_19beadefbf"))
            if (state.accepting) {
                Button(onClick = {}, enabled = false, modifier = Modifier.testTag(SemanticIds.invitationAccept)) {
                    Text(localizedText("copy_df0b8d97c3"))
                }
            } else if (isAuthenticated) {
                PlatformControlAccessibility(SemanticIds.invitationAccept, ControlAccessibilityState.Button(connection.canSubmit))
                Button(onClick = { if (connection.canSubmit) onAccept() }, enabled = connection.canSubmit, modifier = Modifier.testTag(SemanticIds.invitationAccept)) {
                    Text(localizedText("copy_f3703115d3"))
                }
            } else {
                Button(onClick = onRequireSignIn) { Text(localizedText("copy_36e59d0e40")) }
            }
            TextButton(onClick = onCancel, enabled = !state.accepting) { Text(localizedText("copy_3171424931")) }
            InlineError(error)
        }
    }
}

@Composable
internal fun WorkspaceManagementScreen(
    repository: SessionRepository,
    categoryRepository: FinanceRepository,
    initialSection: ManagementSection,
    directoryOpen: Boolean,
    onCloseDirectory: (Boolean) -> Unit,
    workspaceId: String,
    session: Session,
    sizeClass: WindowSizeClass,
    onSessionUpdated: (Session) -> Unit,
    onWorkspaceDeleted: (Session) -> Unit,
    diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) {
    val scope = rememberCoroutineScope()
    val viewModel: WorkspaceManagementViewModel = viewModel(
        key = "workspace-management:$workspaceId",
        factory = viewModelFactory { initializer { WorkspaceManagementViewModel(repository, diagnostics) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember(workspaceId) { SnackbarHostState() }
    var notice by remember(workspaceId) { mutableStateOf<String?>(null) }
    val copiedMessage = localizedText("copy_da8ca6bca3")
    val copyFailureMessage = localizedText("workspace_copy_failed")
    val savedMessage = localizedText("copy_1522ab043b")
    var categoryModalOpen by remember(workspaceId) { mutableStateOf(false) }
    val copyRequest = remember(workspaceId) { mutableStateOf(0) }
    fun copy(value: String) {
        val request = ++copyRequest.value
        scope.launch {
            val copied = copyTextToClipboard(value)
            if (!copied) viewModel.recordClipboardFailure()
            if (request == copyRequest.value) notice = if (copied) copiedMessage else copyFailureMessage
        }
    }
    val activeWorkspace = session.workspaces.firstOrNull { it.id == session.activeWorkspaceId }
    val connection = LocalSessionConnection.current
    val isAdmin = activeWorkspace?.role == WorkspaceRole.ADMIN
    val canSubmit = isAdmin && connection.canSubmit
    LaunchedEffect(viewModel) { viewModel.load() }
    LaunchedEffect(state.updatedSession) {
        state.updatedSession?.let {
            if (state.nameSaved) notice = savedMessage
            onSessionUpdated(it)
            viewModel.consumeUpdatedSession()
        }
    }
    LaunchedEffect(state.deletedSession) {
        state.deletedSession?.let { onWorkspaceDeleted(it); viewModel.consumeDeletedSession() }
    }

    val details = state.details
    val loading = state.loading
    val error = state.errorCode?.let { workspaceErrorText(it) }
    val name = state.nameDraft
    val invitationRole = state.invitationRole
    val invitationLink = state.invitationLink
    val nameError = state.nameErrorCode?.let { workspaceErrorText(it) }
    val busyMember = state.busyMemberId
    val deleteDialog = state.deleteDialogOpen
    val deleteName = state.deleteName
    val deleteFailure = state.deleteFailure
    val deleteError = deleteFailure?.let { workspaceErrorText(it.code) }
    val deleting = state.deleting
    val pageNoticeVisible = !deleteDialog && !directoryOpen && !categoryModalOpen
    LaunchedEffect(notice, pageNoticeVisible) {
        val message = notice?.takeIf { pageNoticeVisible } ?: return@LaunchedEffect
        try {
            snackbar.showSnackbar(message)
        } finally {
            if (notice == message) notice = null
        }
    }
    LaunchedEffect(deleteDialog, deleteFailure, deleteError, connection.ready) {
        if (!deleteDialog && connection.ready && deleteFailure?.short == true && !deleteError.isNullOrBlank()) {
            notice = deleteError
            viewModel.consumeDeleteNotice(deleteFailure)
        }
    }
    val verifyDeletion = { viewModel.verifyDeletion(workspaceId) }
    ManagementLayout(
        sizeClass, isAdmin, initialSection, !loading, directoryOpen, onCloseDirectory,
        snackbar = snackbar,
        status = {
            if (details != null) {
                InlineError(error)
            }
        },
    ) { section ->
        if (section == ManagementSection.CATEGORIES) {
            CashCategoryScreen(
                categoryRepository, workspaceId, sizeClass, activeWorkspace?.role?.canWrite == true,
                showNotice = { notice = it }, onModalVisibilityChanged = { categoryModalOpen = it },
                diagnostics = diagnostics,
            )
        } else if (details == null) {
            ManagementSectionContent {
                Text(localizedText(section.titleKey), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                if (loading) StateMessage(localizedText("copy_bac3e13248"))
                else StateMessage(error.orEmpty(), isError = true) {
                    TextButton(onClick = viewModel::load) { Text(localizedText("copy_e2d53a6d3a")) }
                }
            }
        } else {
            val current = details
            when (section) {
                ManagementSection.WORKSPACE -> ManagementSectionContent {
                    Text(localizedText("management_basic_info"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    val saveWorkspaceName: () -> Unit = { viewModel.saveName(canSubmit) }
                    ManagementSettingRow(localizedText("copy_3a0522a8a9"), sizeClass) {
                        val nameField: @Composable (Modifier) -> Unit = { fieldModifier ->
                            OutlinedTextField(
                                value = name, onValueChange = viewModel::updateName,
                                modifier = fieldModifier.testTag(SemanticIds.workspaceManagementName)
                                    .accessibleTextFieldLabel(localizedText("copy_3a0522a8a9")),
                                enabled = isAdmin, singleLine = true,
                            )
                        }
                        val saveName: @Composable (Modifier) -> Unit = { buttonModifier ->
                            PlatformControlAccessibility("management.save-name", ControlAccessibilityState.Button(canSubmit && name.trim().isNotEmpty() && name.trim() != current.workspace.name))
                            Button(
                                onClick = saveWorkspaceName,
                                enabled = canSubmit && name.trim().isNotEmpty() && name.trim() != current.workspace.name,
                                modifier = buttonModifier.testTag("management.save-name"),
                            ) { Text(localizedText("copy_fadf24dbc5")) }
                        }
                        if (sizeClass == WindowSizeClass.COMPACT) {
                            nameField(Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { saveName(Modifier) }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                nameField(Modifier.weight(1f).alignByBaseline())
                                saveName(Modifier.alignByBaseline())
                            }
                        }
                    }

                    InlineError(nameError)
                    ManagementSettingRow(localizedText("copy_b387059362"), sizeClass) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            SelectionContainer(Modifier.weight(1f)) {
                                Text(current.workspace.id, style = MaterialTheme.typography.bodyLarge)
                            }
                            TextButton(onClick = { copy(current.workspace.id) }, modifier = Modifier.testTag("workspace-management.copy-id")) {
                                Text(localizedText("copy_ba4ba33f86"))
                            }
                        }
                    }
                }

                ManagementSection.MEMBERS -> ManagementSectionContent {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(localizedText("management_members"), Modifier.weight(1f).alignByBaseline().semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                        Text(localizedText("copy_e3fcffbd56", current.members.size), Modifier.alignByBaseline(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (current.members.none { !it.isSelf }) Text(localizedText("copy_a72c953809"))
                    current.members.forEachIndexed { memberIndex, member ->
                        val controls: @Composable () -> Unit = {
                            if (isAdmin && !member.isSelf) {
                                ChoicePicker(
                                    label = localizedText("copy_560165a6d7"),
                                    value = member.role.name.lowercase(),
                                    options = listOf("admin" to localizedText("copy_ef84e765e0"), "editor" to localizedText("copy_bff55f2cd9"), "viewer" to localizedText("copy_16df9ebf26")),
                                    modifier = Modifier.widthIn(max = 220.dp),
                                    enabled = connection.canSubmit && busyMember != member.userId,
                                    semanticId = SemanticIds.workspaceManagementMemberRole,
                                    onSelected = { role -> viewModel.updateMember(member.userId, WorkspaceRole.valueOf(role.uppercase()), canSubmit) },
                                )
                                TextButton(onClick = { viewModel.removeMember(member.userId, canSubmit) }, enabled = connection.canSubmit && busyMember != member.userId) { Text(localizedText("copy_2f752c005e")) }
                            } else {
                                Text(workspaceRoleLabel(member.role), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (sizeClass != WindowSizeClass.WIDE) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (member.isSelf) localizedText("copy_5630b886f9") else member.email, style = MaterialTheme.typography.bodyLarge)
                                if (member.isSelf) Text(member.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                controls()
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (member.isSelf) localizedText("copy_5630b886f9") else member.email, style = MaterialTheme.typography.bodyLarge)
                                    if (member.isSelf) Text(member.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                controls()
                            }
                        }
                        if (memberIndex < current.members.lastIndex) HorizontalDivider()
                    }
                }
                ManagementSection.INVITATIONS -> ManagementSectionContent {
                    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(localizedText("copy_37f99c470a"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                        val createInvite: () -> Unit = {
                            viewModel.createInvitation(canSubmit, getConfiguredWebOrigin(), getBrowserLocation()?.pathname)
                        }
                        ChoicePicker(
                            label = localizedText("copy_560165a6d7"),
                            value = invitationRole.name.lowercase(),
                            options = listOf("editor" to localizedText("copy_bff55f2cd9"), "viewer" to localizedText("copy_16df9ebf26")),
                            onSelected = { viewModel.setInvitationRole(WorkspaceRole.valueOf(it.uppercase())) },
                            enabled = isAdmin,
                            semanticId = SemanticIds.workspaceManagementInvitationRole,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            PlatformControlAccessibility(SemanticIds.workspaceManagementInvite, ControlAccessibilityState.Button(canSubmit))
                            Button(onClick = createInvite, enabled = canSubmit, modifier = Modifier.testTag(SemanticIds.workspaceManagementInvite)) { Text(localizedText("copy_286539ed41")) }
                        }
                        if (invitationLink.isNotEmpty()) {
                            OutlinedTextField(
                                value = invitationLink,
                                onValueChange = {},
                                modifier = Modifier.testTag(SemanticIds.workspaceManagementInviteLink)
                                    .accessibleTextFieldLabel(localizedText("copy_8a8f47d4d5")),
                                label = { Text(localizedText("copy_8a8f47d4d5")) },
                                readOnly = true,
                                singleLine = true,
                            )
                            TextButton(onClick = { copy(invitationLink) }, modifier = Modifier.testTag("workspace-management.copy-link")) {
                                Text(localizedText("copy_abb22bd95c"))
                            }
                        }
                    }
                }
                ManagementSection.DANGER -> ManagementSectionContent {
                    Text(localizedText("management_danger"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    if (!deleteDialog && deleteFailure != null && (!deleteFailure.short || !connection.ready)) {
                        ActionFailureStatus(
                            deleteFailure, deleteError.orEmpty(),
                            canRefresh = !connection.checking && !deleting, onRefresh = verifyDeletion,
                        )
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = viewModel::openDeleteDialog,
                        enabled = canSubmit && !loading && !deleting && deleteFailure?.short != false,
                        border = financeInteractiveBorder(enabled = canSubmit && !loading && !deleting && deleteFailure?.short != false),
                        modifier = Modifier.testTag("workspace.management.delete"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(localizedText("copy_91b31b846d")) }
                }
                ManagementSection.CATEGORIES -> Unit
            }
        }
    }

    PlatformModalEscapeHandler(
        enabled = isWebPlatform && deleteDialog,
        onEscape = viewModel::dismissDeleteDialog,
    )
    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteDialog,
            title = { Text(localizedText("copy_5656d6acd8")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SessionConnectionNotice()
                    Text(localizedText("copy_021b37112e"))
                    OutlinedTextField(
                        value = deleteName,
                        onValueChange = viewModel::updateDeleteName,
                        modifier = Modifier.testTag("workspace.management.delete-name")
                            .accessibleTextFieldLabel(localizedText("copy_3a0522a8a9")),
                        label = { Text(localizedText("copy_3a0522a8a9")) },
                        enabled = canSubmit && !deleting && deleteFailure?.short != false,
                        singleLine = true,
                    )
                    if (deleteFailure != null && (!deleteFailure.short || !connection.ready)) {
                        ActionFailureStatus(deleteFailure, deleteError.orEmpty(),
                            canRefresh = !connection.checking && !deleting, onRefresh = verifyDeletion, refreshError = error)
                    } else InlineError(deleteError)
                }
            },
            confirmButton = {
                PlatformControlAccessibility("workspace.management.delete-confirm", ControlAccessibilityState.Button(canSubmit && deleteFailure?.short != false && deleteName == details?.workspace?.name))
                TextButton(
                    onClick = { viewModel.deleteWorkspace(canSubmit) },
                    enabled = canSubmit && deleteFailure?.short != false && deleteName == details?.workspace?.name,
                    modifier = Modifier.testTag("workspace.management.delete-confirm"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(if (deleting) localizedText("copy_91647936fd") else localizedText("copy_91b31b846d")) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDeleteDialog, modifier = Modifier.testTag("workspace.management.delete-cancel")) { Text(localizedText("copy_4d0b4688c7")) } },
        )
    }
}

@Composable
internal fun workspaceRoleLabel(role: WorkspaceRole): String = when (role) {
    WorkspaceRole.ADMIN -> localizedText("copy_ef84e765e0")
    WorkspaceRole.EDITOR -> localizedText("copy_bff55f2cd9")
    WorkspaceRole.VIEWER -> localizedText("copy_16df9ebf26")
}

@Composable
private fun workspaceErrorText(code: String): String = when (code) {
    "authentication_required" -> localizedText("copy_8e38717a57")
    "workspace_forbidden" -> localizedText("copy_dd9a4bdee0")
    "invalid_workspace_name" -> localizedText("copy_01c5cd44b0")
    "workspace_name_mismatch" -> localizedText("workspace_delete_name_changed")
    "network_unavailable" -> localizedText("copy_e0a1c9fd69")
    "unknown_error" -> localizedText("copy_382ef5a830")
    else -> localizedText("copy_e6a62f3e45")
}
