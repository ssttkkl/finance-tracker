package com.finance.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@Composable
internal fun InvitationScreen(
    api: FinanceApiClient,
    token: String,
    session: SessionDto?,
    onRequireSignIn: () -> Unit,
    onAccepted: (SessionDto) -> Unit,
    onCancel: () -> Unit,
) {
    var preview by remember(token) { mutableStateOf<InvitationPreviewDto?>(null) }
    var loading by remember(token) { mutableStateOf(true) }
    var accepting by remember(token) { mutableStateOf(false) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var terminalPreviewError by remember(token) { mutableStateOf(false) }
    var retryPreview by remember(token) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api, token, retryPreview) {
        loading = true
        error = null
        terminalPreviewError = false
        try { preview = api.invitationPreview(token) }
        catch (cause: Throwable) {
            preview = null
            terminalPreviewError = isTerminalInvitationFailure(cause)
            error = invitationErrorMessage(cause)
        }
        finally { loading = false }
    }

    FeaturePage("工作区邀请", SemanticIds.invitationScreen, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        SectionCard(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
            when {
                loading -> Text("正在确认邀请…")
                preview == null -> {
                    Text("邀请不可用", style = MaterialTheme.typography.titleLarge)
                    InlineError(error)
                    if (!terminalPreviewError) TextButton(onClick = { retryPreview++ }) { Text("重试") }
                    TextButton(onClick = onCancel) { Text("返回") }
                }
                preview?.valid == false -> {
                    Text("邀请已失效", style = MaterialTheme.typography.titleLarge)
                    Text("该邀请已被使用或已过期。")
                    TextButton(onClick = onCancel) { Text("返回") }
                }
                else -> {
                    Text("加入「${preview!!.workspace.name}」", style = MaterialTheme.typography.titleLarge)
                    Text(if (preview!!.role == Role.EDITOR) "可查看和修改账本、导入和关联关系。" else "可浏览账本及相关信息，不能修改内容。")
                    if (session == null) {
                        Button(onClick = onRequireSignIn) { Text("登录后接受邀请") }
                    } else {
                        Button(onClick = {
                            scope.launch {
                                accepting = true
                                error = null
                                try { onAccepted(api.acceptInvitation(token)) }
                                catch (cause: Throwable) { error = invitationErrorMessage(cause) }
                                finally { accepting = false }
                            }
                        }, enabled = !accepting, modifier = Modifier.testTag(SemanticIds.invitationAccept)) {
                            Text(if (accepting) "正在加入…" else "接受邀请")
                        }
                    }
                    TextButton(onClick = onCancel, enabled = !accepting) { Text("暂不加入") }
                    InlineError(error)
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceManagementScreen(
    api: FinanceApiClient,
    session: SessionDto,
    sizeClass: WindowSizeClass,
    onSessionUpdated: (SessionDto) -> Unit,
    onWorkspaceDeleted: (SessionDto) -> Unit,
) {
    val activeWorkspace = session.workspaces.firstOrNull { it.id == session.activeWorkspaceId }
    val isAdmin = activeWorkspace?.role == Role.ADMIN
    var details by remember(api, session.activeWorkspaceId) { mutableStateOf<WorkspaceMembersDto?>(null) }
    var loading by remember(api, session.activeWorkspaceId) { mutableStateOf(true) }
    var error by remember(api, session.activeWorkspaceId) { mutableStateOf<String?>(null) }
    var name by remember(api, session.activeWorkspaceId) { mutableStateOf("") }
    var invitationRole by remember { mutableStateOf(Role.EDITOR) }
    var invitationLink by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var busyMember by remember { mutableStateOf<String?>(null) }
    var deleteDialog by remember { mutableStateOf(false) }
    var deleteName by remember { mutableStateOf("") }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                val value = api.workspaceDetails()
                details = value
                name = value.workspace.name
            } catch (cause: Throwable) { error = userError(cause) }
            finally { loading = false }
        }
    }

    LaunchedEffect(api, session.activeWorkspaceId) { load() }

    FeaturePage("工作区管理", SemanticIds.workspaceManagementScreen) {
        if (loading && details == null) StateMessage("正在读取工作区信息…")
        if (error != null && details == null) StateMessage(error.orEmpty(), isError = true) { TextButton(onClick = ::load) { Text("重试") } }
        details?.let { current ->
            SectionCard {
                Text("工作区信息", style = MaterialTheme.typography.titleLarge)
                Text("固定 ID", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(current.workspace.id, style = MaterialTheme.typography.bodyMedium)
                val saveWorkspaceName: () -> Unit = {
                    scope.launch {
                        feedback = null
                        error = null
                        try {
                            val updated = api.updateWorkspace(name.trim())
                            onSessionUpdated(updated)
                            feedback = "已保存。"
                            load()
                        } catch (cause: Throwable) { error = userError(cause) }
                    }
                }
                val copyWorkspaceId: () -> Unit = {
                    scope.launch {
                        feedback = if (copyTextToClipboard(current.workspace.id)) "已复制。" else "复制失败。"
                    }
                }
                if (sizeClass == WindowSizeClass.COMPACT) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledInput(name, { name = it.take(255) }, "工作区名称", semanticId = SemanticIds.workspaceManagementName, enabled = isAdmin)
                        Button(
                            onClick = saveWorkspaceName,
                            enabled = isAdmin && name.trim().isNotEmpty() && name.trim() != current.workspace.name,
                        ) { Text("保存") }
                        TextButton(onClick = copyWorkspaceId) { Text("复制工作区 ID") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledInput(name, { name = it.take(255) }, "工作区名称", modifier = Modifier.weight(1f), semanticId = SemanticIds.workspaceManagementName, enabled = isAdmin)
                        Button(
                            onClick = saveWorkspaceName,
                            enabled = isAdmin && name.trim().isNotEmpty() && name.trim() != current.workspace.name,
                        ) { Text("保存") }
                    }
                    TextButton(onClick = copyWorkspaceId) { Text("复制工作区 ID") }
                }
            }
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("成员", style = MaterialTheme.typography.titleLarge)
                    Text("${current.members.size} 位成员")
                }
                if (current.members.none { !it.isSelf }) Text("暂无其他成员。")
                current.members.forEach { member ->
                    val controls: @Composable () -> Unit = {
                        if (isAdmin && !member.isSelf) {
                            ChoicePicker(
                                label = "权限",
                                value = member.role.name.lowercase(),
                                options = listOf("admin" to "管理员", "editor" to "可编辑", "viewer" to "仅可查看"),
                                modifier = Modifier.widthIn(max = 220.dp),
                                enabled = busyMember != member.userId,
                                onSelected = { role ->
                                    scope.launch {
                                        busyMember = member.userId
                                        try { api.updateMember(member.userId, Role.valueOf(role.uppercase())); load() }
                                        catch (cause: Throwable) { error = userError(cause) }
                                        finally { busyMember = null }
                                    }
                                },
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    busyMember = member.userId
                                    try { api.removeMember(member.userId); load() }
                                    catch (cause: Throwable) { error = userError(cause) }
                                    finally { busyMember = null }
                                }
                            }, enabled = busyMember != member.userId) { Text("移除") }
                        } else {
                            Text(roleLabel(member.role), modifier = Modifier.padding(top = 14.dp))
                        }
                    }
                    if (sizeClass == WindowSizeClass.COMPACT) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (member.isSelf) "你" else member.email, style = MaterialTheme.typography.titleMedium)
                            if (member.isSelf) Text(member.email, style = MaterialTheme.typography.bodySmall)
                            controls()
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(if (member.isSelf) "你" else member.email, style = MaterialTheme.typography.titleMedium)
                                if (member.isSelf) Text(member.email, style = MaterialTheme.typography.bodySmall)
                            }
                            controls()
                        }
                    }
                    HorizontalDivider()
                }
            }
            SectionCard {
                Text("邀请成员", style = MaterialTheme.typography.titleLarge)
                val createInvite: () -> Unit = {
                    scope.launch {
                        feedback = null
                        try {
                            val invitation = api.invite(invitationRole)
                            val webOrigin = getConfiguredWebOrigin().trimEnd('/')
                            invitationLink = invitationLinkFor(webOrigin, getBrowserLocation()?.pathname, invitation.token)
                            feedback = if (webOrigin.isBlank()) "应用邀请链接已生成。" else "链接已生成。"
                        } catch (cause: Throwable) { error = userError(cause) }
                    }
                }
                if (sizeClass == WindowSizeClass.COMPACT) {
                    ChoicePicker(
                        label = "权限",
                        value = invitationRole.name.lowercase(),
                        options = listOf("editor" to "可编辑", "viewer" to "仅可查看"),
                        onSelected = { invitationRole = Role.valueOf(it.uppercase()) },
                        enabled = isAdmin,
                    )
                    Button(onClick = createInvite, enabled = isAdmin, modifier = Modifier.testTag(SemanticIds.workspaceManagementInvite)) { Text("创建链接") }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                        ChoicePicker(
                            label = "权限",
                            value = invitationRole.name.lowercase(),
                            options = listOf("editor" to "可编辑", "viewer" to "仅可查看"),
                            onSelected = { invitationRole = Role.valueOf(it.uppercase()) },
                            enabled = isAdmin,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = createInvite, enabled = isAdmin, modifier = Modifier.testTag(SemanticIds.workspaceManagementInvite)) { Text("创建链接") }
                    }
                }
                if (invitationLink.isNotEmpty()) {
                    LabeledInput(invitationLink, {}, "邀请链接", semanticId = SemanticIds.workspaceManagementInviteLink, enabled = false)
                    TextButton(onClick = {
                        scope.launch { feedback = if (copyTextToClipboard(invitationLink)) "已复制。" else "复制失败。" }
                    }) { Text("复制链接") }
                }
            }
            if (isAdmin) {
                SectionCard {
                    Text("删除工作区", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { deleteName = ""; deleteError = null; deleteDialog = true }) { Text("删除工作区") }
                }
            }
        }
        InlineError(error)
        feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteDialog = false },
            title = { Text("删除工作区？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("删除后将同时移除该工作区的账本数据、成员和邀请。")
                    LabeledInput(deleteName, { deleteName = it }, "工作区名称")
                    InlineError(deleteError)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            deleting = true
                            deleteError = null
                            try { onWorkspaceDeleted(api.deleteWorkspace(deleteName)) }
                            catch (cause: Throwable) { deleteError = userError(cause) }
                            finally { deleting = false }
                        }
                    },
                    enabled = !deleting && deleteName == details?.workspace?.name,
                ) { Text(if (deleting) "正在删除…" else "删除工作区") }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }, enabled = !deleting) { Text("取消") } },
        )
    }
}

private fun roleLabel(role: Role): String = when (role) {
    Role.ADMIN -> "管理员"
    Role.EDITOR -> "可编辑"
    Role.VIEWER -> "仅可查看"
}
