package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    onCredentialValidationFailure: (CredentialValidation) -> Unit,
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
    val emailLabel = localizedText("copy_9ed627bcf6")
    val passwordLabel = localizedText("copy_c839a8ff17")
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
            Text(localizedText("app_name"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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
                    Text(localizedText("copy_aa455d1d85"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        if (registering) localizedText("copy_da87e4eb0c") else localizedText("copy_953ff44970"),
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
                            .semantics(mergeDescendants = true) { contentDescription = emailLabel }
                            .then(tabFocusModifier),
                        enabled = !busy,
                        label = { Text(emailLabel) },
                        placeholder = { Text(localizedText("auth_email_placeholder")) },
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
                            .semantics(mergeDescendants = true) { contentDescription = passwordLabel }
                            .then(tabFocusModifier),
                        enabled = !busy,
                        label = { Text(passwordLabel) },
                        placeholder = { Text(localizedText("copy_1345609bc1")) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (validation == CredentialValidation.Valid) onAuthenticate(email.trim(), password, registering)
                            else {
                                validationError = validation
                                onCredentialValidationFailure(validation)
                            }
                        }),
                    )
                    val visibleError = validationError?.let { credentialErrorText(it) } ?: requestError
                    if (visibleError != null) {
                        Text(
                            visibleError,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag(SemanticIds.authError),
                        )
                    }
                    FinanceButton(
                        onClick = {
                            if (validation == CredentialValidation.Valid) onAuthenticate(email.trim(), password, registering)
                            else {
                                validationError = validation
                                onCredentialValidationFailure(validation)
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(submitFocus)
                            .onFocusChanged { if (it.isFocused) focusedControl = AuthFocusControl.SUBMIT }
                            .testTag(SemanticIds.authSubmit),
                    ) {
                        if (busy) CircularProgressIndicator()
                        else Text(if (registering) localizedText("copy_da0e5f8dc9") else localizedText("copy_21f1e88275"))
                    }
                    FinanceTertiaryButton(
                        onClick = { registering = !registering; validationError = null },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(toggleModeFocus)
                            .onFocusChanged { if (it.isFocused) focusedControl = AuthFocusControl.TOGGLE_MODE }
                            .testTag(SemanticIds.authToggleMode),
                    ) {
                        Text(if (registering) localizedText("copy_c2c57c2d99") else localizedText("copy_22a4a2a530"))
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceAccessScreen(
    session: Session,
    busy: Boolean,
    requestError: String?,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onLogout: () -> Unit,
    onRetry: () -> Unit,
    onInvalidWorkspaceName: () -> Unit,
) {
    val workspaceNameLabel = localizedText("copy_3a0522a8a9")
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
        verticalArrangement = Arrangement.Top,
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
                    Text(localizedText("app_name"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(session.user.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FinanceTertiaryButton(onClick = onLogout, enabled = !busy, modifier = Modifier.testTag(SemanticIds.workspaceLogout)) {
                    Text(localizedText("copy_094774b4a7"))
                }
            }
            Text(
                if (session.workspaces.isEmpty()) localizedText("copy_d92d1b8da8") else localizedText("copy_b6eef2b9e2"),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (requestError != null) {
                Text(
                    requestError,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                FinanceTertiaryButton(onClick = onRetry, enabled = !busy, modifier = Modifier.testTag(SemanticIds.workspaceRetry)) {
                    Text(localizedText("copy_e2d53a6d3a"))
                }
            }
            if (session.workspaces.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag(SemanticIds.workspaceList),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    session.workspaces.forEach { workspace ->
                        FinanceSecondaryButton(
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
                                Text(workspaceRoleLabel(workspace.role), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            FinanceSurface(modifier = Modifier.fillMaxWidth()) {
                    Text(localizedText("copy_55e800686b"), style = MaterialTheme.typography.titleMedium)
                    LabeledInput(
                        value = workspaceName,
                        onValueChange = { workspaceName = it; nameError = false },
                        label = workspaceNameLabel,
                        semanticId = SemanticIds.workspaceCreateName,
                        enabled = !busy,
                        singleLine = true,
                        isError = nameError,
                    )
                    if (nameError) {
                        Text(localizedText("copy_b0b3a62d15"), color = MaterialTheme.colorScheme.error)
                    }
                    FinanceButton(
                        onClick = {
                            if (normalizedName == null) {
                                nameError = true
                                onInvalidWorkspaceName()
                            } else onCreate(normalizedName)
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag(SemanticIds.workspaceCreate),
                    ) {
                        Text(if (busy) localizedText("copy_f5fd2680d8") else localizedText("copy_d92d1b8da8"))
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
            Text(localizedText("copy_1b86167f23"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun credentialErrorText(value: CredentialValidation): String = when (value) {
    CredentialValidation.Valid -> ""
    CredentialValidation.EmailRequired -> localizedText("copy_a2789a9770")
    CredentialValidation.EmailInvalid -> localizedText("copy_dfa5e652bf")
    CredentialValidation.PasswordRequired -> localizedText("copy_727bbbf558")
    CredentialValidation.PasswordTooShort -> localizedText("copy_b806c505de")
}
