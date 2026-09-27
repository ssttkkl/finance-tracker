package com.finance.tracker.domain

enum class CredentialValidation {
    Valid,
    EmailRequired,
    EmailInvalid,
    PasswordRequired,
    PasswordTooShort,
}

fun validateCredentials(email: String, password: String): CredentialValidation {
    val normalizedEmail = email.trim()
    if (normalizedEmail.isEmpty()) return CredentialValidation.EmailRequired
    if (!EMAIL_PATTERN.matches(normalizedEmail)) return CredentialValidation.EmailInvalid
    if (password.isEmpty()) return CredentialValidation.PasswordRequired
    if (password.length < 12) return CredentialValidation.PasswordTooShort
    return CredentialValidation.Valid
}

fun normalizeWorkspaceName(value: String): String? =
    value.trim().takeIf { it.isNotEmpty() && it.length <= MAX_WORKSPACE_NAME_LENGTH }

private const val MAX_WORKSPACE_NAME_LENGTH = 255
private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
