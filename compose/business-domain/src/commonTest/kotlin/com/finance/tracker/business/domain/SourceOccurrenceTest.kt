package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class SourceOccurrenceTest {
    private fun occurrence() = SourceOccurrence("bank", "owner", "f", "v1", "csv", 2,
        mapOf("amount" to "-20.00"), providerId = "bank-id-1")

    @Test
    fun occurrenceKeysFollowSourceLocationRatherThanMutableFields() {
        val value = occurrence()
        assertEquals("0cf42247dd90cdbfbc43f89ced0025a3600c4984cde19f052ba4c1e58dd7d20f", value.occurrenceKey)
        assertEquals("25840008b660fd2a00cc871e0fe3bb7cbfe08ce1334216f74b19d0b277404d7f", value.providerIdentity)
        assertEquals(value.occurrenceKey, value.copy(fields = mapOf("amount" to "-21.00", "note" to "new")).occurrenceKey)
        assertEquals(value.occurrenceKey, value.copy(sourceAccountKey = "another owner").occurrenceKey)
        assertEquals(value.fileScopeKey, value.copy(sourceAccountKey = "another owner").fileScopeKey)
        assertEquals(value.occurrenceKey, value.copy().occurrenceKey)
    }

    @Test
    fun sourceNamespacesAndDocumentLocationsRemainDistinct() {
        val first = occurrence().copy(sourceType = "wechat")
        val second = first.copy(sourceAccountKey = "another owner")
        kotlin.test.assertNotEquals(first.occurrenceKey, second.occurrenceKey)
        kotlin.test.assertNotEquals(first.fileScopeKey, second.fileScopeKey)
        val coverage = first.coverage.copy(documentKey = "a".repeat(64), pageTotal = 2,
            pageFingerprints = mapOf("1" to "b".repeat(64)))
        val document = first.copy(coverage = coverage, documentPosition = "p1:t0:r1")
        assertEquals("1", document.documentPage?.second)
        assertEquals(document.documentScopeKey, document.observationScopeKey)
        assertEquals(null, document.copy(documentPosition = "p2:t0:r1").documentOccurrenceKey)
        assertEquals(document.documentOccurrenceKey, document.copy(fileDigest = "different file").documentOccurrenceKey)
    }

    @Test
    fun locatorValidationPreservesUnicodeLengthsAndPlatformIdentityRequirements() {
        assertEquals("😀".repeat(512), occurrence().copy(providerId = "😀".repeat(512)).providerId)
        assertEquals("invalid_source_locator", assertFailsWith<IllegalArgumentException> {
            occurrence().copy(providerId = "😀".repeat(513))
        }.message)
        assertEquals("invalid_source_locator", assertFailsWith<IllegalArgumentException> { occurrence().copy(position = -1) }.message)
        assertEquals("platform_transaction_id_required", assertFailsWith<IllegalArgumentException> {
            occurrence().copy(sourceType = "alipay", providerId = null)
        }.message)
        assertEquals("invalid_source_locator", assertFailsWith<IllegalArgumentException> {
            occurrence().copy(documentPosition = "p0:t0:r1")
        }.message)
    }

    @Test
    fun unicodeAndEscapedLocatorKeysMatchFixedPythonDigests() {
        val vectors = listOf(
            "plain" to "79002ebc34bc6ee06130921cf99eb449d4f07f7ee6fcd5d44afc823d2fc3284a",
            "\u4e2d\u6587\ud83d\ude00" to "596e340d84d28a9bb98b25b12fa2ffaa202b341a1d23366d9034f467019820cb",
            "quote\"slash\\" to "73c1c29f5cea2f6f6dbf98eb615639a67635d7b14333351ee08d27465611668e",
            "\u0000\n\t\b\u000c\r" to "4bdef894cb4a814c604e25f434131dbaa7739df335243c2e06f811f1d4b58cfe",
            "\u2028\u2029" to "bb2075cc9c33a83615acd1a5f7108fb3a9ce48114f0bb89ea7282af6ab8a3ff1",
            "\ud835\udfd8\ud835\udfd9\ud835\udfda" to "60c22f9194dffd7cf5c31d7681274f5523b5af2283b7ebcfb0015a99ae3e4f28",
        )
        for ((text, expected) in vectors) {
            assertEquals(expected, SourceBindingKey("bank", "account", text, "provider_id", text).key)
        }
    }

    @Test
    fun identityDigestsRejectMalformedUtf8() {
        // Build at runtime: Kotlin/JS replaces isolated surrogates in source string constants.
        val high = charArrayOf(0xd800.toChar()).concatToString()
        val low = charArrayOf(0xdc00.toChar()).concatToString()
        assertEquals(0xd800, high.single().code)
        assertEquals(0xdc00, low.single().code)
        assertFails { occurrence().copy(fileDigest = high).occurrenceKey }
        assertFails { SourceBindingKey("bank", "account", low, "provider_id", "id").key }
    }
}
