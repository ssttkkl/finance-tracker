package com.finance.tracker.backend

import java.nio.charset.CharacterCodingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SharedSourceIdentityTest {
    @Test
    fun sharedIdentityUsesTheExistingUtf8JsonDigestForAllLocatorText() {
        for (text in listOf("plain", "中文😀", "quote\"slash\\", "\u0000\n\t", "😀".repeat(512))) {
            val binding = SourceBindingKey("bank", "account", text, "provider_id", text)
            assertEquals(sourceDigest("bank", "account", text, "provider_id", text), binding.key)
            assertEquals(sourceDigest(text), providerIdentityKey(text))
        }
    }

    @Test
    fun malformedLocatorKeepsTheJvmEncodingFailureType() {
        for (text in listOf("\ud800", "\udc00", "a\ud800b")) {
            assertFailsWith<CharacterCodingException> { sourceDigest(text) }
            assertFailsWith<CharacterCodingException> { providerIdentityKey(text) }
            assertFailsWith<CharacterCodingException> { SourceBindingKey("bank", "account", text, "provider_id", "id").key }
        }
    }
}
