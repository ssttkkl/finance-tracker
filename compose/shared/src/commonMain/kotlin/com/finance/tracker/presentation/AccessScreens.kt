package com.finance.tracker.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshotFlow
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledTextField
import com.finance.tracker.core.design.FinanceTokens

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
    var confirmPassword by remember { mutableStateOf("") }
    var showValidationError by remember { mutableStateOf(false) }
    var confirmPasswordError by remember { mutableStateOf(false) }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val confirmPasswordFocus = remember { FocusRequester() }
    val validation = validateCredentials(email, password)
    val emailStatus = when {
        email.isBlank() -> null
        validation != CredentialValidation.EmailInvalid && validation != CredentialValidation.EmailRequired -> localizedText("auth_email_valid")
        else -> localizedText("auth_email_invalid")
    }
    val passwordStatus = when {
        password.length >= 12 -> localizedText("auth_password_valid")
        password.isEmpty() -> localizedText("auth_password_neutral")
        else -> localizedText("auth_password_invalid")
    }
    val confirmPasswordStatus = when {
        confirmPassword.isEmpty() -> null
        confirmPassword == password -> localizedText("auth_confirm_password_valid")
        else -> localizedText("auth_confirm_password_invalid")
    }
    val visibleError = (if (showValidationError) credentialErrorText(validation) else null)
        ?: if (confirmPasswordError) localizedText("auth_confirm_password_submit_error") else requestError
    val colors = FinanceTokens.forTheme(isSystemInDarkTheme())

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().testTag(SemanticIds.authScreen),
    ) {
        val sizeClass = WindowSizeClass.fromWidthDp(maxWidth.value.toInt())
        Box(
            Modifier.fillMaxSize().background(if (sizeClass == WindowSizeClass.COMPACT) colors.surface else colors.background),
            contentAlignment = if (sizeClass == WindowSizeClass.COMPACT) Alignment.TopCenter else Alignment.Center,
        ) {
        val onSubmit = {
            when {
                validation != CredentialValidation.Valid -> {
                    showValidationError = true
                    confirmPasswordError = false
                    onCredentialValidationFailure(validation)
                }
                registering && confirmPassword != password -> {
                    showValidationError = false
                    confirmPasswordError = true
                }
                else -> {
                    showValidationError = false
                    confirmPasswordError = false
                    onAuthenticate(email.trim(), password, registering)
                }
            }
        }
        val form: @Composable (Modifier) -> Unit = { modifier ->
            AuthFormContent(
                registering = registering,
                email = email,
                password = password,
                confirmPassword = confirmPassword,
                emailStatus = emailStatus,
                passwordStatus = passwordStatus,
                confirmPasswordStatus = confirmPasswordStatus,
                visibleError = visibleError,
                busy = busy,
                colors = colors,
                modifier = modifier,
                emailFocus = emailFocus,
                passwordFocus = passwordFocus,
                confirmPasswordFocus = confirmPasswordFocus,
                onEmailChange = { email = it; showValidationError = false; confirmPasswordError = false },
                onPasswordChange = { password = it; showValidationError = false; confirmPasswordError = false },
                onConfirmPasswordChange = { confirmPassword = it; showValidationError = false; confirmPasswordError = false },
                onBack = { registering = false; showValidationError = false; confirmPasswordError = false },
                onToggleMode = { registering = true; showValidationError = false; confirmPasswordError = false },
                onSubmit = onSubmit,
            )
        }
        if (sizeClass == WindowSizeClass.COMPACT) {
            form(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, top = 52.dp, end = 24.dp, bottom = 24.dp))
        } else {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().testTag(SemanticIds.authCard),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surface,
                    tonalElevation = 0.dp,
                ) {
                    form(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp))
                }
            }
        }
        }
    }
}

@Composable
private fun AuthFormContent(
    registering: Boolean,
    email: String,
    password: String,
    confirmPassword: String,
    emailStatus: String?,
    passwordStatus: String,
    confirmPasswordStatus: String?,
    visibleError: String?,
    busy: Boolean,
    colors: com.finance.tracker.core.design.FinanceColorRoles,
    modifier: Modifier,
    emailFocus: FocusRequester,
    passwordFocus: FocusRequester,
    confirmPasswordFocus: FocusRequester,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onBack: () -> Unit,
    onToggleMode: () -> Unit,
    onSubmit: () -> Unit,
) {
    val density = LocalDensity.current
    Column(modifier) {
        if (registering) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthBackButton(
                    enabled = !busy,
                    colors = colors,
                    modifier = Modifier.alignBy { density.run { 27.75.dp.roundToPx() } }
                        .testTag(SemanticIds.authBack),
                    onClick = onBack,
                )
                Text(
                    localizedText("auth_create_title"),
                    modifier = Modifier.alignByBaseline().testTag(SemanticIds.authTitle),
                    style = TextStyle(fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, color = colors.foreground),
                )
            }
        } else {
            Text(
                localizedText("auth_login_title"),
                modifier = Modifier.testTag(SemanticIds.authTitle),
                style = TextStyle(fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, color = colors.foreground),
            )
        }
        Spacer(Modifier.height(10.dp))
        AuthTextField(email, onEmailChange, localizedText("copy_9ed627bcf6"), SemanticIds.authEmail, emailFocus, !busy, colors,
            modifier = Modifier,
            supportingText = if (registering) emailStatus else null,
            supportingColor = if (emailStatus == localizedText("auth_email_invalid")) Color(0xFFD92D20) else Color(0xFF188038),
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
            onKeyboardAction = { passwordFocus.requestFocus() },
        )
        Spacer(Modifier.height(if (registering) 20.dp else 18.dp))
        AuthTextField(password, onPasswordChange, localizedText("copy_c839a8ff17"), SemanticIds.authPassword, passwordFocus, !busy, colors,
            modifier = Modifier,
            supportingText = if (registering) passwordStatus else null,
            supportingColor = when {
                password.length >= 12 -> Color(0xFF188038)
                password.isNotEmpty() -> Color(0xFFD92D20)
                else -> colors.foreground.copy(alpha = 0.64f)
            },
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, imeAction = if (registering) androidx.compose.ui.text.input.ImeAction.Next else androidx.compose.ui.text.input.ImeAction.Done),
            outputTransformation = OutputTransformation { replace(0, length, "•".repeat(length)) },
            onKeyboardAction = { if (registering) confirmPasswordFocus.requestFocus() else onSubmit() },
        )
        if (registering) {
            Spacer(Modifier.height(20.dp))
            AuthTextField(confirmPassword, onConfirmPasswordChange, localizedText("auth_confirm_password"), SemanticIds.authConfirmPassword, confirmPasswordFocus, !busy, colors,
                modifier = Modifier,
                supportingText = confirmPasswordStatus,
                supportingColor = if (confirmPassword == password) Color(0xFF188038) else Color(0xFFD92D20),
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                outputTransformation = OutputTransformation { replace(0, length, "•".repeat(length)) },
                onKeyboardAction = onSubmit,
            )
        }
        if (visibleError != null) {
            Spacer(Modifier.height(16.dp))
            Text(visibleError, color = colors.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(SemanticIds.authError), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.height(24.dp))
        }
        AuthPrimaryButton(onSubmit, !busy, colors,
                modifier = Modifier.testTag(SemanticIds.authSubmit),
        ) {
            if (busy) CircularProgressIndicator() else Text(if (registering) localizedText("copy_da0e5f8dc9") else localizedText("copy_21f1e88275"))
        }
        if (!registering) {
            Spacer(Modifier.height(8.dp))
            AuthModeButton(onToggleMode, !busy, colors,
                modifier = Modifier.testTag(SemanticIds.authToggleMode),
            ) {
                Text(localizedText("copy_22a4a2a530"), style = TextStyle(fontSize = 14.sp, color = colors.accent))
            }
        }
    }
}

@Composable
private fun AuthBackButton(
    enabled: Boolean,
    colors: com.finance.tracker.core.design.FinanceColorRoles,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val accessibleLabel = localizedText("auth_back_to_login")
    UnstyledButton(onClick = onClick, enabled = enabled, modifier = modifier.size(44.dp).semantics(mergeDescendants = true) {
        contentDescription = accessibleLabel
    }) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.offset(x = 10.dp, y = 3.75.dp).size(24.dp)) {
                val strokeWidth = 2.5.dp.toPx()
                val arrowColor = colors.foreground
                drawLine(arrowColor, Offset(19.dp.toPx(), 12.dp.toPx()), Offset(5.dp.toPx(), 12.dp.toPx()), strokeWidth, StrokeCap.Round)
                drawLine(arrowColor, Offset(5.dp.toPx(), 12.dp.toPx()), Offset(12.dp.toPx(), 5.dp.toPx()), strokeWidth, StrokeCap.Round)
                drawLine(arrowColor, Offset(5.dp.toPx(), 12.dp.toPx()), Offset(12.dp.toPx(), 19.dp.toPx()), strokeWidth, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    semanticId: String,
    focusRequester: FocusRequester,
    enabled: Boolean,
    colors: com.finance.tracker.core.design.FinanceColorRoles,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    supportingColor: Color = colors.foreground.copy(alpha = 0.64f),
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    outputTransformation: OutputTransformation? = null,
    onKeyboardAction: () -> Unit = {},
) {
    val state = rememberTextFieldState(value)
    var focused by remember { mutableStateOf(false) }
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestValue by rememberUpdatedState(value)
    LaunchedEffect(value, focused) {
        if (!focused && state.text.toString() != value) state.edit { replace(0, length, value) }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { currentValue ->
            if (currentValue != latestValue) latestOnValueChange(currentValue)
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.fillMaxWidth()) {
        Text(label, color = colors.foreground, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        if (supportingText != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                supportingText,
                color = supportingColor,
                style = TextStyle(fontSize = 12.sp, fontWeight = if (supportingText.startsWith("✓") || supportingText.startsWith("✕")) FontWeight.Medium else FontWeight.Normal),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(when (semanticId) {
                    SemanticIds.authEmail -> SemanticIds.authEmailValidation
                    SemanticIds.authPassword -> SemanticIds.authPasswordValidation
                    else -> SemanticIds.authConfirmPasswordValidation
                }),
            )
            Spacer(Modifier.height(3.dp))
        } else {
            Spacer(Modifier.height(2.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .testTag(when (semanticId) {
                    SemanticIds.authEmail -> SemanticIds.authEmailControl
                    SemanticIds.authPassword -> SemanticIds.authPasswordControl
                    else -> SemanticIds.authConfirmPasswordControl
                }),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .testTag(when (semanticId) {
                        SemanticIds.authEmail -> SemanticIds.authEmailVisual
                        SemanticIds.authPassword -> SemanticIds.authPasswordVisual
                        else -> SemanticIds.authConfirmPasswordVisual
                    })
                    .background(colors.surface, shape)
                    .border(1.dp, if (focused) colors.focus else colors.fieldBorder, shape),
                contentAlignment = Alignment.CenterStart,
            ) {
                UnstyledTextField(
                    state = state,
                    enabled = enabled,
                    accessibilityLabel = label,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    outputTransformation = outputTransformation,
                    keyboardOptions = keyboardOptions,
                    onKeyboardAction = { onKeyboardAction() },
                    textStyle = TextStyle(color = colors.foreground, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            focused = it.isFocused
                        }
                        .testTag(semanticId)
                        .semantics(mergeDescendants = true) { contentDescription = label },
                ) {
                    TextInput(placeholder = {
                        if (!placeholder.isNullOrEmpty()) Text(placeholder, color = colors.foreground.copy(alpha = 0.55f), fontSize = 14.sp)
                    })
                }
            }
        }
    }
}

@Composable
private fun AuthPrimaryButton(
    onClick: () -> Unit,
    enabled: Boolean,
    colors: com.finance.tracker.core.design.FinanceColorRoles,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(if (enabled) Modifier else Modifier.semantics { disabled() }),
    ) {
        Box(Modifier.fillMaxWidth().height(46.dp).background(if (enabled) colors.accent else colors.disabled, shape).then(if (focused) Modifier.border(1.dp, colors.focus, shape) else Modifier), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides colors.accentForeground) {
                content()
            }
        }
    }
}

@Composable
private fun AuthModeButton(
    onClick: () -> Unit,
    enabled: Boolean,
    colors: com.finance.tracker.core.design.FinanceColorRoles,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp).then(if (enabled) Modifier else Modifier.semantics { disabled() }),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 18.dp), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides colors.accent) {
                content()
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
