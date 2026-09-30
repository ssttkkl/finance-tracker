package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
    val preview = invitationState.preview
    val error = invitationState.errorCode?.let { localizedText(invitationErrorResourceKey(it)) }
    LaunchedEffect(invitationViewModel, token) { invitationViewModel.load(token) }
    LaunchedEffect(invitationState.acceptedSession) {
        invitationState.acceptedSession?.let { accepted ->
            onAccepted(accepted)
            invitationViewModel.consumeAcceptedSession()
        }
    }

    FeaturePage(localizedText("copy_d7f050d6f3"), SemanticIds.invitationScreen, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        SectionCard(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
            when {
                invitationState.loading -> Text(localizedText("copy_d347833ffd"))
                preview == null -> {
                    Text(localizedText("copy_73e6ee9610"), style = MaterialTheme.typography.titleLarge)
                    InlineError(error)
                    if (!invitationState.terminalPreviewError) FinanceTertiaryButton(onClick = { invitationViewModel.retry(token) }) { Text(localizedText("copy_e2d53a6d3a")) }
                    FinanceTertiaryButton(onClick = onCancel) { Text(localizedText("copy_11d0241540")) }
                }
                preview.valid == false -> {
                    Text(localizedText("copy_ec0fa251c2"), style = MaterialTheme.typography.titleLarge)
                    Text(localizedText("copy_40d123e2b5"))
                    FinanceTertiaryButton(onClick = onCancel) { Text(localizedText("copy_11d0241540")) }
                }
                else -> {
                    Text(localizedText("copy_4d2ee8a422", preview.workspace.name), style = MaterialTheme.typography.titleLarge)
                    Text(if (preview.role == WorkspaceRole.EDITOR) localizedText("copy_e85cf84fa9") else localizedText("copy_19beadefbf"))
                    if (session == null) {
                        FinanceButton(onClick = onRequireSignIn) { Text(localizedText("copy_36e59d0e40")) }
                    } else {
                        FinanceButton(onClick = { invitationViewModel.accept(token) }, enabled = !invitationState.accepting, modifier = Modifier.testTag(SemanticIds.invitationAccept)) {
                            Text(if (invitationState.accepting) localizedText("copy_df0b8d97c3") else localizedText("copy_f3703115d3"))
                        }
                    }
                    FinanceTertiaryButton(onClick = onCancel, enabled = !invitationState.accepting) { Text(localizedText("copy_3171424931")) }
                    InlineError(error)
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceManagementScreen(
    repository: SessionRepository,
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
    val activeWorkspace = session.workspaces.firstOrNull { it.id == session.activeWorkspaceId }
    val isAdmin = activeWorkspace?.role == WorkspaceRole.ADMIN
    LaunchedEffect(viewModel) { viewModel.load() }
    LaunchedEffect(state.updatedSession) {
        state.updatedSession?.let { onSessionUpdated(it); viewModel.consumeUpdatedSession() }
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
    val feedback = state.feedbackCode?.let { workspaceFeedbackText(it) }
    val busyMember = state.busyMemberId
    val deleteDialog = state.deleteDialogOpen
    val deleteName = state.deleteName
    val deleteError = state.deleteErrorCode?.let { workspaceErrorText(it) }
    val deleting = state.deleting
    FeaturePage(localizedText("copy_e0617c59d4"), SemanticIds.workspaceManagementScreen) {
        if (loading && details == null) StateMessage(localizedText("copy_bac3e13248"))
        if (error != null && details == null) StateMessage(error.orEmpty(), isError = true) { FinanceTertiaryButton(onClick = viewModel::load) { Text(localizedText("copy_e2d53a6d3a")) } }
        details?.let { current ->
            SectionCard {
                Text(localizedText("copy_b9add18dda"), style = MaterialTheme.typography.titleLarge)
                val saveWorkspaceName: () -> Unit = { viewModel.saveName(isAdmin) }
                val copyWorkspaceId: () -> Unit = {
                    scope.launch {
                        viewModel.setFeedback(if (copyTextToClipboard(current.workspace.id)) "workspace_copied" else "copy_failed")
                    }
                }
                if (sizeClass == WindowSizeClass.COMPACT) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledInput(name, viewModel::updateName, localizedText("copy_3a0522a8a9"), semanticId = SemanticIds.workspaceManagementName, enabled = isAdmin)
                        FinanceButton(
                            onClick = saveWorkspaceName,
                            enabled = isAdmin && name.trim().isNotEmpty() && name.trim() != current.workspace.name,
                        ) { Text(localizedText("copy_fadf24dbc5")) }
                        Text(localizedText("copy_b387059362"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(current.workspace.id, style = MaterialTheme.typography.bodyMedium)
                        FinanceTertiaryButton(onClick = copyWorkspaceId) { Text(localizedText("copy_ba4ba33f86")) }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LabeledInput(name, viewModel::updateName, localizedText("copy_3a0522a8a9"), modifier = Modifier.weight(1f), semanticId = SemanticIds.workspaceManagementName, enabled = isAdmin)
                            FinanceButton(
                                onClick = saveWorkspaceName,
                                enabled = isAdmin && name.trim().isNotEmpty() && name.trim() != current.workspace.name,
                            ) { Text(localizedText("copy_fadf24dbc5")) }
                        }
                        Column(Modifier.weight(0.8f)) {
                            Text(localizedText("copy_b387059362"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(current.workspace.id, style = MaterialTheme.typography.bodyMedium)
                                FinanceTertiaryButton(onClick = copyWorkspaceId) { Text(localizedText("copy_ba4ba33f86")) }
                            }
                        }
                    }
                }
            }
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(localizedText("copy_c1ee9f0190"), style = MaterialTheme.typography.titleLarge)
                    Text(localizedText("copy_e3fcffbd56", current.members.size))
                }
                if (current.members.none { !it.isSelf }) Text(localizedText("copy_a72c953809"))
                current.members.forEach { member ->
                    val controls: @Composable () -> Unit = {
                        if (isAdmin && !member.isSelf) {
                            ChoicePicker(
                                label = localizedText("copy_560165a6d7"),
                                value = member.role.name.lowercase(),
                                options = listOf("admin" to localizedText("copy_ef84e765e0"), "editor" to localizedText("copy_bff55f2cd9"), "viewer" to localizedText("copy_16df9ebf26")),
                                modifier = Modifier.widthIn(max = 220.dp),
                                enabled = busyMember != member.userId,
                                onSelected = { role -> viewModel.updateMember(member.userId, WorkspaceRole.valueOf(role.uppercase()), isAdmin) },
                            )
                            FinanceTertiaryButton(onClick = { viewModel.removeMember(member.userId, isAdmin) }, enabled = busyMember != member.userId) { Text(localizedText("copy_2f752c005e")) }
                        } else {
                            Text(workspaceRoleLabel(member.role), modifier = Modifier.padding(top = 14.dp))
                        }
                    }
                    if (sizeClass == WindowSizeClass.COMPACT) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (member.isSelf) localizedText("copy_5630b886f9") else member.email, style = MaterialTheme.typography.titleMedium)
                            if (member.isSelf) Text(member.email, style = MaterialTheme.typography.bodySmall)
                            controls()
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(if (member.isSelf) localizedText("copy_5630b886f9") else member.email, style = MaterialTheme.typography.titleMedium)
                                if (member.isSelf) Text(member.email, style = MaterialTheme.typography.bodySmall)
                            }
                            controls()
                        }
                    }
                    HorizontalDivider()
                }
            }
            SectionCard {
                Text(localizedText("copy_37f99c470a"), style = MaterialTheme.typography.titleLarge)
                val createInvite: () -> Unit = {
                    viewModel.createInvitation(isAdmin, getConfiguredWebOrigin(), getBrowserLocation()?.pathname)
                }
                ChoicePicker(
                    label = localizedText("copy_560165a6d7"),
                    value = invitationRole.name.lowercase(),
                    options = listOf("editor" to localizedText("copy_bff55f2cd9"), "viewer" to localizedText("copy_16df9ebf26")),
                    onSelected = { viewModel.setInvitationRole(WorkspaceRole.valueOf(it.uppercase())) },
                    enabled = isAdmin,
                )
                FinanceButton(onClick = createInvite, enabled = isAdmin, modifier = Modifier.fillMaxWidth().testTag(SemanticIds.workspaceManagementInvite)) { Text(localizedText("copy_286539ed41")) }
                if (invitationLink.isNotEmpty()) {
                    LabeledInput(invitationLink, {}, localizedText("copy_8a8f47d4d5"), semanticId = SemanticIds.workspaceManagementInviteLink, enabled = false)
                    FinanceTertiaryButton(onClick = {
                        scope.launch {
                            viewModel.setFeedback(if (copyTextToClipboard(invitationLink)) "workspace_copied" else "copy_failed")
                        }
                    }) { Text(localizedText("copy_abb22bd95c")) }
                }
            }
            if (isAdmin) {
                SectionCard {
                    Text(localizedText("copy_91b31b846d"), style = MaterialTheme.typography.titleLarge)
                    FinanceButton(onClick = viewModel::openDeleteDialog, variant = FinanceButtonVariant.Danger) { Text(localizedText("copy_91b31b846d")) }
                }
            }
        }
        InlineError(error)
        feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteDialog,
            title = { Text(localizedText("copy_5656d6acd8")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(localizedText("copy_021b37112e"))
                    LabeledInput(deleteName, viewModel::updateDeleteName, localizedText("copy_3a0522a8a9"))
                    InlineError(deleteError)
                }
            },
            confirmButton = {
                FinanceTertiaryButton(
                    onClick = { viewModel.deleteWorkspace(isAdmin) },
                    enabled = !deleting && deleteName == details?.workspace?.name,
                ) { Text(if (deleting) localizedText("copy_91647936fd") else localizedText("copy_91b31b846d")) }
            },
            dismissButton = { FinanceTertiaryButton(onClick = viewModel::dismissDeleteDialog, enabled = !deleting) { Text(localizedText("copy_4d0b4688c7")) } },
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
    "network_unavailable" -> localizedText("copy_e0a1c9fd69")
    "unknown_error" -> localizedText("copy_382ef5a830")
    else -> localizedText("copy_e6a62f3e45")
}

@Composable
private fun workspaceFeedbackText(code: String): String = when (code) {
    "workspace_name_saved" -> localizedText("copy_1522ab043b")
    "workspace_copied" -> localizedText("copy_da8ca6bca3")
    "copy_failed" -> localizedText("copy_9572cd5988")
    "workspace_app_invite_link_generated" -> localizedText("copy_2f66146378")
    "workspace_invite_link_generated" -> localizedText("copy_c771e450fa")
    else -> localizedText("copy_abb235feac")
}
