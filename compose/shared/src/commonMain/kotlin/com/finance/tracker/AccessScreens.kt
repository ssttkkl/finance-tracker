package com.finance.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun AuthScreen(
    busy: Boolean,
    requestError: String?,
    onAuthenticate: (email: String, password: String, registering: Boolean) -> Unit,
) {
    var registering by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<CredentialValidation?>(null) }
    var focusedControl by remember { mutableStateOf<AuthFocusControl?>(null) }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }
    val toggleModeFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val tabFocusModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.key == Key.Tab && event.type == KeyEventType.KeyDown) {
            focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
        } else {
            false
        }
    }
    DisposableEffect(Unit) {
        val removeListener = observeBrowserTabNavigation { backwards ->
            when (nextAuthFocusControl(focusedControl, backwards)) {
                AuthFocusControl.EMAIL -> emailFocus.requestFocus()
                AuthFocusControl.PASSWORD -> passwordFocus.requestFocus()
                AuthFocusControl.SUBMIT -> submitFocus.requestFocus()
                AuthFocusControl.TOGGLE_MODE -> toggleModeFocus.requestFocus()
            }
        }
        onDispose(removeListener)
    }
    val validation = validateCredentials(email, password)

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(SemanticIds.authScreen),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Finance Tracker", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("工作区访问", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        if (registering) "创建你的账户" else "登录到你的账本",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; validationError = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(emailFocus)
                            .onFocusChanged { if (it.isFocused) focusedControl = AuthFocusControl.EMAIL }
                            .testTag(SemanticIds.authEmail)
                            .semantics(mergeDescendants = true) { contentDescription = "邮箱" }
                            .then(tabFocusModifier),
                        enabled = !busy,
                        label = { Text("邮箱") },
                        placeholder = { Text("name@example.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; validationError = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(passwordFocus)
                            .onFocusChanged { if (it.isFocused) focusedControl = AuthFocusControl.PASSWORD }
                            .testTag(SemanticIds.authPassword)
                            .semantics(mergeDescendants = true) { contentDescription = "密码" }
                            .then(tabFocusModifier),
                        enabled = !busy,
                        label = { Text("密码") },
                        placeholder = { Text("至少 12 个字符") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (validation == CredentialValidation.Valid) onAuthenticate(email.trim(), password, registering)
                            else validationError = validation
                        }),
                    )
                    val visibleError = validationError?.let(::credentialErrorText) ?: requestError
                    if (visibleError != null) {
                        Text(
                            visibleError,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag(SemanticIds.authError),
                        )
                    }
                    Button(
                        onClick = {
                            if (validation == CredentialValidation.Valid) onAuthenticate(email.trim(), password, registering)
                            else validationError = validation
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(submitFocus)
                            .onFocusChanged { if (it.isFocused) focusedControl = AuthFocusControl.SUBMIT }
                            .testTag(SemanticIds.authSubmit),
                    ) {
                        if (busy) CircularProgressIndicator()
                        else Text(if (registering) "注册" else "登录")
                    }
                    TextButton(
                        onClick = { registering = !registering; validationError = null },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(toggleModeFocus)
                            .onFocusChanged { if (it.isFocused) focusedControl = AuthFocusControl.TOGGLE_MODE }
                            .testTag(SemanticIds.authToggleMode),
                    ) {
                        Text(if (registering) "已有账户？登录" else "还没有账户？注册")
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceAccessScreen(
    session: SessionDto,
    busy: Boolean,
    requestError: String?,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onLogout: () -> Unit,
    onRetry: () -> Unit,
) {
    var workspaceName by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }
    val normalizedName = normalizeWorkspaceName(workspaceName)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(SemanticIds.workspaceScreen)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Finance Tracker", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(session.user.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onLogout, enabled = !busy, modifier = Modifier.testTag(SemanticIds.workspaceLogout)) {
                    Text("退出登录")
                }
            }
            Text(
                if (session.workspaces.isEmpty()) "创建工作区" else "选择工作区",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (requestError != null) {
                Text(
                    requestError,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = onRetry, enabled = !busy, modifier = Modifier.testTag(SemanticIds.workspaceRetry)) {
                    Text("重试")
                }
            }
            if (session.workspaces.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag(SemanticIds.workspaceList),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    session.workspaces.forEach { workspace ->
                        OutlinedButton(
                            onClick = { onSelect(workspace.id) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(workspace.name, style = MaterialTheme.typography.titleSmall)
                                Text(workspace.role.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("新工作区", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = workspaceName,
                        onValueChange = { workspaceName = it; nameError = false },
                        modifier = Modifier.fillMaxWidth()
                            .testTag(SemanticIds.workspaceCreateName)
                            .semantics(mergeDescendants = true) { contentDescription = "工作区名称" },
                        enabled = !busy,
                        label = { Text("工作区名称") },
                        placeholder = { Text("家庭账本") },
                        singleLine = true,
                        isError = nameError,
                    )
                    if (nameError) {
                        Text("请输入不超过 255 个字符的名称。", color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = {
                            if (normalizedName == null) nameError = true else onCreate(normalizedName)
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag(SemanticIds.workspaceCreate),
                    ) {
                        Text(if (busy) "正在创建…" else "创建工作区")
                    }
                }
            }
        }
    }
}

@Composable
internal fun SessionLoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("正在读取账户…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun credentialErrorText(value: CredentialValidation): String = when (value) {
    CredentialValidation.Valid -> ""
    CredentialValidation.EmailRequired -> "请输入邮箱。"
    CredentialValidation.EmailInvalid -> "请输入有效邮箱地址。"
    CredentialValidation.PasswordRequired -> "请输入密码。"
    CredentialValidation.PasswordTooShort -> "密码至少 12 个字符。"
}
