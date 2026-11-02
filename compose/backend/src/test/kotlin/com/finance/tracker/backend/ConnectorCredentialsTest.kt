package com.finance.tracker.backend

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*

class ConnectorCredentialsTest {
    private fun fixture(content: String? = null, block: (ConnectorCredentials, Path) -> Unit) {
        val directory = Files.createTempDirectory("ft-credentials-synthetic-")
        try {
            content?.let { directory.resolve("credentials.yaml").writeText(it) }
            block(ConnectorCredentials(mapOf("FT_CREDENTIALS_DIR" to directory.toString())), directory)
        } finally { directory.toFile().deleteRecursively() }
    }
    private fun failure(content: String?, expected: String, provider: String = "binance") = fixture(content) { loader, _ ->
        assertContains(assertFailsWith<IllegalArgumentException> { if (provider == "polymarket") loader.polymarket() else loader.exchange(provider) }.message!!, expected)
    }
    @Test fun happyPath() = fixture("binance:\n  api_key: my-key\n  api_secret: my-secret\n") { loader, _ -> assertEquals(mapOf("api_key" to "my-key", "api_secret" to "my-secret"), loader.exchange("binance")) }
    @Test fun password() = fixture("okx:\n  api_key: k\n  api_secret: s\n  password: passphrase\n") { loader, _ -> assertEquals("passphrase", loader.exchange("okx")["password"]) }
    @Test fun missingFile() = failure(null, "not found")
    @Test fun emptyFile() = failure("", "empty")
    @Test fun nonMapping() = failure(":::bad", "YAML mapping")
    @Test fun invalidSyntax() = failure("{bad: [unclosed", "Invalid YAML")
    @Test fun missingProvider() = failure("other_provider:\n  api_key: x\n", "Missing 'binance'")
    @Test fun missingSecret() = failure("binance:\n  api_key: x\n", "api_secret")
    @Test fun errorsNeverLeakSecret() = fixture("binance:\n  api_key: SUPER_SECRET_KEY_12345\n") { loader, _ -> assertFalse("SUPER_SECRET_KEY_12345" in assertFailsWith<IllegalArgumentException> { loader.exchange("binance") }.toString()) }
    @Test fun extraFieldsIgnored() = fixture("binance:\n  api_key: k\n  api_secret: s\n  extra_field: ignored\n") { loader, _ -> assertFalse("extra_field" in loader.exchange("binance")) }
    @Test fun chmodApplied() = fixture("binance:\n  api_key: k\n  api_secret: s\n") { loader, directory -> loader.exchange("binance"); assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("credentials.yaml")))) }
    @Test fun gitignoreCreated() = fixture("binance:\n  api_key: k\n  api_secret: s\n") { loader, directory -> loader.exchange("binance"); assertTrue(Files.exists(directory.resolve(".gitignore"))); assertContains(directory.resolve(".gitignore").readText(), "credentials.yaml") }
    @Test fun proxyWallet() = fixture("polymarket:\n  proxy_wallet: '0x1234567890abcdef1234567890abcdef12345678'\n") { loader, _ -> assertEquals("0x1234567890abcdef1234567890abcdef12345678", loader.polymarket()["proxy_wallet"]) }
    @Test fun wallet() = fixture("polymarket:\n  wallet: '0xabcdefabcdefabcdefabcdefabcdefabcdefabcd'\n") { loader, _ -> assertEquals("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", loader.polymarket()["wallet"]) }
    @Test fun missingBothWallets() = failure("polymarket:\n  other: x\n", "'wallet' or 'proxy_wallet'", "polymarket")
    @Test fun invalidWallet() = failure("polymarket:\n  wallet: 'not-an-address'\n", "valid Ethereum", "polymarket")
    @Test fun missingPolymarketSection() = failure("binance:\n  api_key: k\n", "Missing 'polymarket'", "polymarket")
    @Test fun unsafeTagsAndSyntaxDiagnosticsNeverExposeContent() {
        for (content in listOf("binance: !!java.net.URL ['https://SYNTHETIC_SECRET.invalid']", "binance: !!python/object:secret.SYNTHETIC_SECRET {}", "binance: [SYNTHETIC_SECRET",
            "binance: {api_key: !!float SYNTHETIC_SECRET, api_secret: synthetic}", "binance: {api_key: !!int SYNTHETIC_SECRET, api_secret: synthetic}",
            "binance: {api_key: 2026-02-30, api_secret: SYNTHETIC_SECRET}")) fixture(content) { loader, _ ->
            val failure = assertFailsWith<IllegalArgumentException> { loader.exchange("binance") }
            assertContains(failure.message!!, "Invalid YAML"); assertFalse("SYNTHETIC_SECRET" in failure.toString()); assertNull(failure.cause)
        }
    }
    @Test fun duplicateKeysDoNotLogSecretsAndGitignorePreservesExistingLines() = fixture("binance:\n  api_key: SYNTHETIC_FIRST\n  api_key: SYNTHETIC_LAST\n  api_secret: synthetic\n") { loader, directory ->
        directory.resolve(".gitignore").writeText("keep-me\n credentials.yaml \n")
        val output = java.io.ByteArrayOutputStream(); val original = System.err
        try { System.setErr(java.io.PrintStream(output)); assertEquals("SYNTHETIC_LAST", loader.exchange("binance")["api_key"]) } finally { System.setErr(original) }
        assertFalse(output.toString().contains("SYNTHETIC")); assertEquals("keep-me\n credentials.yaml \n", directory.resolve(".gitignore").readText())
    }
}
