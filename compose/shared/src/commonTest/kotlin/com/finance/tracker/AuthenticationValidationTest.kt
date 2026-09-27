package com.finance.tracker

import com.finance.tracker.domain.CredentialValidation
import com.finance.tracker.domain.validateCredentials
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthenticationValidationTest {
    @Test
    fun classifiesRegistrationInputErrorsForLocalizedPresentation() {
        assertEquals(CredentialValidation.EmailRequired, validateCredentials("  ", "a-valid-password"))
        assertEquals(CredentialValidation.EmailInvalid, validateCredentials("not-an-email", "a-valid-password"))
        assertEquals(CredentialValidation.PasswordRequired, validateCredentials("person@example.com", ""))
        assertEquals(CredentialValidation.PasswordTooShort, validateCredentials("person@example.com", "short"))
        assertEquals(CredentialValidation.Valid, validateCredentials(" person@example.com ", "a-valid-password"))
    }
}
