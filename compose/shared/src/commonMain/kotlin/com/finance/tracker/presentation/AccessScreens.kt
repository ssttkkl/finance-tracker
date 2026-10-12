package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finance.tracker.core.WindowSizeClass
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
    val emailIsError = emailStatus == localizedText("auth_email_invalid")
    val passwordIsError = password.isNotEmpty() && password.length < 12
    val confirmPasswordIsError = confirmPasswordError || (confirmPassword.isNotEmpty() && confirmPassword != password)
    val visibleError = (if (showValidationError) credentialErrorText(validation) else null)
        ?: if (confirmPasswordError) localizedText("auth_confirm_password_submit_error") else requestError

    MaterialTheme(
        shapes = MaterialTheme.shapes.copy(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
        ),
    ) {
        AuthResponsiveShell { modifier, compact ->
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
            AuthFormContent(
                registering = registering,
                email = email,
                password = password,
                confirmPassword = confirmPassword,
                emailStatus = emailStatus,
                emailIsError = emailIsError,
                passwordStatus = passwordStatus,
                passwordIsError = passwordIsError,
                confirmPasswordStatus = confirmPasswordStatus,
                confirmPasswordIsError = confirmPasswordIsError,
                visibleError = visibleError,
                busy = busy,
                compact = compact,
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
    }
}

@Composable
private fun AuthFormContent(
    registering: Boolean,
    email: String,
    password: String,
    confirmPassword: String,
    emailStatus: String?,
    emailIsError: Boolean,
    passwordStatus: String,
    passwordIsError: Boolean,
    confirmPasswordStatus: String?,
    confirmPasswordIsError: Boolean,
    visibleError: String?,
    busy: Boolean,
    compact: Boolean,
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
    Column(modifier) {
        AuthHeader(registering = registering, compact = compact, enabled = !busy, onBack = onBack)
        Spacer(Modifier.height(if (compact) 14.dp else 2.dp))
        AuthTextField(email, onEmailChange, localizedText("copy_9ed627bcf6"), SemanticIds.authEmail, emailFocus, !busy,
            modifier = Modifier,
            supportingText = if (registering) emailStatus else null,
            isError = registering && emailIsError,
            isSuccess = registering && emailStatus != null && !emailIsError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            onKeyboardAction = { passwordFocus.requestFocus() },
        )
        Spacer(Modifier.height(if (registering && emailStatus != null) 11.dp else 15.dp))
        AuthTextField(password, onPasswordChange, localizedText("copy_c839a8ff17"), SemanticIds.authPassword, passwordFocus, !busy,
            modifier = Modifier,
            supportingText = if (registering) passwordStatus else null,
            isError = registering && passwordIsError,
            isSuccess = registering && password.length >= 12,
            masked = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (registering) ImeAction.Next else ImeAction.Done),
            onKeyboardAction = { if (registering) confirmPasswordFocus.requestFocus() else onSubmit() },
        )
        if (registering) {
            Spacer(Modifier.height(11.dp))
            AuthTextField(confirmPassword, onConfirmPasswordChange, localizedText("auth_confirm_password"), SemanticIds.authConfirmPassword, confirmPasswordFocus, !busy,
                modifier = Modifier,
                supportingText = confirmPasswordStatus,
                isError = confirmPasswordIsError,
                isSuccess = confirmPasswordStatus != null && !confirmPasswordIsError,
                masked = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                onKeyboardAction = onSubmit,
            )
        }
        if (visibleError != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                visibleError,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(SemanticIds.authError),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.height(if (registering && confirmPasswordStatus != null) 11.dp else 23.dp))
        }
        Button(
            onClick = onSubmit,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(40.dp).testTag(SemanticIds.authSubmit)
                .then(if (busy) Modifier.semantics { disabled() } else Modifier),
            shape = MaterialTheme.shapes.small,
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(if (registering) localizedText("copy_da0e5f8dc9") else localizedText("copy_21f1e88275"))
            }
        }
        if (!registering) {
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onToggleMode,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(44.dp).testTag(SemanticIds.authToggleMode)
                    .then(if (busy) Modifier.semantics { disabled() } else Modifier),
            ) {
                Text(localizedText("copy_22a4a2a530"))
            }
        }
    }
}

@Composable
private fun AuthHeader(registering: Boolean, compact: Boolean, enabled: Boolean, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(44.dp)) {
        if (registering) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onBack,
                    enabled = enabled,
                    modifier = Modifier
                        .size(44.dp)
                        .offset(x = if (compact) (-8).dp else 0.dp)
                        .testTag(SemanticIds.authBack)
                        .then(if (enabled) Modifier else Modifier.semantics { disabled() }),
                ) {
                    Icon(
                        imageVector = AuthBackIcon,
                        contentDescription = localizedText("auth_back_to_login"),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    localizedText("auth_create_title"),
                    modifier = Modifier.testTag(SemanticIds.authTitle),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, lineHeight = 29.sp),
                )
            }
        } else {
            Text(
                localizedText("auth_login_title"),
                modifier = Modifier.align(Alignment.CenterStart).testTag(SemanticIds.authTitle),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, lineHeight = 29.sp),
            )
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
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    isError: Boolean = false,
    isSuccess: Boolean = false,
    masked: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: () -> Unit = {},
) {
    val feedbackModifier = if (supportingText != null) {
        Modifier
            .semantics {
                contentDescription = supportingText
                liveRegion = LiveRegionMode.Polite
            }
            .testTag(validationTagFor(semanticId))
    } else {
        Modifier
    }
    Column(modifier.fillMaxWidth().then(feedbackModifier)) {
        Box(Modifier.fillMaxWidth().testTag(controlTagFor(semanticId))) {
            Box(Modifier.fillMaxWidth().testTag(visualTagFor(semanticId))) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag(semanticId)
                        .semantics {
                            contentDescription = label
                            if (!enabled) disabled()
                        },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(label) },
                    supportingText = supportingText?.let { text ->
                        {
                            Text(
                                text,
                                color = when {
                                    isError -> MaterialTheme.colorScheme.error
                                    isSuccess -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outline
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    isError = isError,
                    visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = KeyboardActions(
                        onNext = { onKeyboardAction() },
                        onDone = { onKeyboardAction() },
                    ),
                    shape = MaterialTheme.shapes.small,
                )
            }
        }
    }
}

private fun controlTagFor(semanticId: String): String = when (semanticId) {
    SemanticIds.authEmail -> SemanticIds.authEmailControl
    SemanticIds.authPassword -> SemanticIds.authPasswordControl
    else -> SemanticIds.authConfirmPasswordControl
}

private fun visualTagFor(semanticId: String): String = when (semanticId) {
    SemanticIds.authEmail -> SemanticIds.authEmailVisual
    SemanticIds.authPassword -> SemanticIds.authPasswordVisual
    else -> SemanticIds.authConfirmPasswordVisual
}

private fun validationTagFor(semanticId: String): String = when (semanticId) {
    SemanticIds.authEmail -> SemanticIds.authEmailValidation
    SemanticIds.authPassword -> SemanticIds.authPasswordValidation
    else -> SemanticIds.authConfirmPasswordValidation
}

private val AuthBackIcon: ImageVector = ImageVector.Builder(
    name = "ArrowBack",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(20f, 11f)
        horizontalLineTo(7.83f)
        lineToRelative(5.59f, -5.59f)
        lineTo(12f, 4f)
        lineToRelative(-8f, 8f)
        lineToRelative(8f, 8f)
        lineToRelative(1.41f, -1.41f)
        lineTo(7.83f, 13f)
        horizontalLineTo(20f)
        close()
    }
}.build()

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

    if (session.workspaces.isEmpty()) {
        FirstUseWorkspaceAccessScreen(
            session = session,
            busy = busy,
            requestError = requestError,
            workspaceName = workspaceName,
            workspaceNameLabel = workspaceNameLabel,
            nameError = nameError,
            onWorkspaceNameChange = { workspaceName = it; nameError = false },
            onCreate = {
                if (normalizedName == null) {
                    nameError = true
                    onInvalidWorkspaceName()
                } else {
                    onCreate(normalizedName)
                }
            },
            onLogout = onLogout,
            onRetry = onRetry,
        )
        return
    }

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
            Text(localizedText("copy_b6eef2b9e2"), style = MaterialTheme.typography.headlineSmall)
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
private fun FirstUseWorkspaceAccessScreen(
    session: Session,
    busy: Boolean,
    requestError: String?,
    workspaceName: String,
    workspaceNameLabel: String,
    nameError: Boolean,
    onWorkspaceNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onLogout: () -> Unit,
    onRetry: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(SemanticIds.workspaceScreen),
    ) {
        val sizeClass = WindowSizeClass.fromWidthDp(maxWidth.value.toInt())
        val headerMaxWidth = if (sizeClass == WindowSizeClass.WIDE) 1120.dp else 720.dp
        val viewportHeight = maxHeight

        if (sizeClass == WindowSizeClass.COMPACT) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                WorkspaceAccountHeader(
                    session = session,
                    busy = busy,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    onLogout = onLogout,
                )
                Spacer(Modifier.height(48.dp))
                FirstUseWorkspaceCard(
                    workspaceName = workspaceName,
                    workspaceNameLabel = workspaceNameLabel,
                    busy = busy,
                    requestError = requestError,
                    nameError = nameError,
                    onWorkspaceNameChange = onWorkspaceNameChange,
                    onCreate = onCreate,
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(20.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(Modifier.fillMaxWidth().heightIn(min = viewportHeight)) {
                    WorkspaceAccountHeader(
                        session = session,
                        busy = busy,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 28.dp, start = 24.dp, end = 24.dp)
                            .widthIn(max = headerMaxWidth)
                            .fillMaxWidth()
                            .height(72.dp),
                        onLogout = onLogout,
                    )
                    FirstUseWorkspaceCard(
                        workspaceName = workspaceName,
                        workspaceNameLabel = workspaceNameLabel,
                        busy = busy,
                        requestError = requestError,
                        nameError = nameError,
                        onWorkspaceNameChange = onWorkspaceNameChange,
                        onCreate = onCreate,
                        onRetry = onRetry,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = if (sizeClass == WindowSizeClass.WIDE) 10.dp else (-8).dp)
                            .widthIn(max = 440.dp)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceAccountHeader(
    session: Session,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onLogout: () -> Unit,
) {
    val colors = FinanceTokens.forTheme(androidx.compose.foundation.isSystemInDarkTheme())
    Column(modifier) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    localizedText("app_name"),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        lineHeight = 23.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    session.user.email,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onLogout,
                enabled = !busy,
                modifier = Modifier.width(90.dp).height(44.dp).testTag(SemanticIds.workspaceLogout),
            ) {
                Text(
                    localizedText("copy_094774b4a7"),
                    color = colors.foreground,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.fieldBorder.copy(alpha = 0.55f)),
        )
    }
}

@Composable
private fun FirstUseWorkspaceCard(
    workspaceName: String,
    workspaceNameLabel: String,
    busy: Boolean,
    requestError: String?,
    nameError: Boolean,
    onWorkspaceNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
) {
    val colors = FinanceTokens.forTheme(androidx.compose.foundation.isSystemInDarkTheme())
    Surface(
        modifier = modifier.testTag(SemanticIds.workspaceCreateCard),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, colors.fieldBorder.copy(alpha = 0.12f)),
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(contentPadding)) {
            Text(
                localizedText("copy_d92d1b8da8"),
                modifier = Modifier.testTag(SemanticIds.workspaceCreateTitle),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 24.sp,
                    lineHeight = 29.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = workspaceName,
                onValueChange = onWorkspaceNameChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag(SemanticIds.workspaceCreateName)
                    .semantics { contentDescription = workspaceNameLabel },
                enabled = !busy,
                singleLine = true,
                placeholder = { Text(workspaceNameLabel) },
                isError = nameError,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    errorBorderColor = colors.danger,
                    errorCursorColor = colors.danger,
                ),
            )
            if (nameError) {
                Text(
                    localizedText("copy_b0b3a62d15"),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (requestError != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        requestError,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = onRetry,
                        enabled = !busy,
                        modifier = Modifier.testTag(SemanticIds.workspaceRetry),
                    ) {
                        Text(localizedText("copy_e2d53a6d3a"))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onCreate,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag(SemanticIds.workspaceCreate),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    if (busy) localizedText("copy_f5fd2680d8") else localizedText("copy_d92d1b8da8"),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
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
