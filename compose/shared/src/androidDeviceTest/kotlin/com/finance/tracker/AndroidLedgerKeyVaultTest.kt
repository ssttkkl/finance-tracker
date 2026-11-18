package com.finance.tracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.system.Os
import com.finance.tracker.data.AndroidLedgerKeyVault
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidLedgerKeyVaultTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun alias(id: String) = "finance-tracker-ledger-cache-$id"
    private fun record(id: String) = File(context.noBackupFilesDir, "ledger-key-vault/records/$id.key")
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Test fun realKeystorePersistsAnEncryptedRecordAndDuplicateCreateKeepsTheKey() = withVault { id, vault ->
        assertNull(vault.read())
        assertFalse(keyStore().containsAlias(alias(id)))
        val key = vault.create()
        try {
            assertEquals(32, key.size)
            val encoded = record(id).readBytes()
            assertEquals(61, encoded.size)
            assertFalse(encoded.asList().windowed(32).contains(key.asList()))
            assertNull(keyStore().getKey(alias(id), null).encoded)
            for (read in listOf(vault.read(), AndroidLedgerKeyVault(context, id).read(), vault.create())) {
                val value = assertNotNull(read)
                try { assertContentEquals(key, value) } finally { value.fill(0) }
            }
            val copy = assertNotNull(vault.read())
            copy.fill(0)
            val next = assertNotNull(vault.read())
            try { assertContentEquals(key, next) } finally { next.fill(0) }
            assertContentEquals(encoded, record(id).readBytes())
        } finally { key.fill(0) }
        vault.erase(); vault.erase()
        assertNull(vault.read())
        assertFalse(record(id).exists())
        assertFalse(keyStore().containsAlias(alias(id)))
    }

    @Test fun missingWrappingKeyDoesNotGenerateAReplacement() = withVault { id, vault ->
        vault.create().fill(0)
        val before = record(id).readBytes()
        keyStore().deleteEntry(alias(id))
        repeat(2) {
            assertFails { vault.read() }
            assertFails { vault.create() }
            assertFalse(keyStore().containsAlias(alias(id)))
            assertContentEquals(before, record(id).readBytes())
        }
    }

    @Test fun damagedAndForeignRecordsFailWithoutRewritingEitherScope() = withVault { firstId, first ->
        withVault { otherId, other ->
            first.create().fill(0); other.create().fill(0)
            val original = record(firstId).readBytes()
            val otherRecord = record(otherId).readBytes()
            for (damaged in listOf(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
                original.copyOf().also { it[0] = 2 }, original.copyOf(60), original.copyOf(62), otherRecord)) {
                record(firstId).writeBytes(damaged)
                assertFails { first.read() }
                assertFails { first.create() }
                assertContentEquals(damaged, record(firstId).readBytes())
                assertContentEquals(otherRecord, record(otherId).readBytes())
            }
            record(firstId).writeBytes(original)
            first.erase()
            assertFalse(keyStore().containsAlias(alias(firstId)))
            assertTrue(keyStore().containsAlias(alias(otherId)))
            other.read()?.fill(0) ?: fail("Another scope was erased")
        }
    }

    @Test fun concurrentFirstCreationReturnsOneStableKey() = withVault { id, vault ->
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val results = (0..1).map {
            executor.submit<ByteArray> { check(start.await(10, TimeUnit.SECONDS)); AndroidLedgerKeyVault(context, id).create() }
        }
        val keys = mutableListOf<ByteArray>()
        try {
            start.countDown()
            results.forEach { keys.add(it.get(20, TimeUnit.SECONDS)) }
            assertContentEquals(keys[0], keys[1])
            val actual = assertNotNull(vault.read())
            try { assertContentEquals(keys[0], actual) } finally { actual.fill(0) }
        } finally {
            keys.forEach { it.fill(0) }
            executor.shutdownNow(); check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun fileRemovalRefusalPreservesTheKeyUntilExplicitRetry() = withVault { id, vault ->
        val original = vault.create()
        val parent = record(id).parentFile!!
        val before = record(id).readBytes()
        try {
            Os.chmod(parent.path, 0x140)
            assertFails { vault.erase() }
            assertTrue(keyStore().containsAlias(alias(id)))
            assertContentEquals(before, record(id).readBytes())
            val current = assertNotNull(vault.read())
            try { assertContentEquals(original, current) } finally { current.fill(0) }
        } finally { original.fill(0); Os.chmod(parent.path, 0x1C0) }
        vault.erase()
        assertFalse(keyStore().containsAlias(alias(id)))
        assertFalse(record(id).exists())
    }

    @Test fun symlinkRecordCannotReadOrOverwriteAnOutsideFile() = withVault { id, vault ->
        vault.create().fill(0)
        val outside = File.createTempFile("ledger-outside-", ".bin", context.cacheDir)
        val original = ByteArray(61) { 17 }
        outside.writeBytes(original)
        try {
            assertTrue(record(id).delete())
            Os.symlink(outside.path, record(id).path)
            assertFails { vault.read() }
            assertFails { vault.create() }
            vault.erase()
            assertContentEquals(original, outside.readBytes())
        } finally { assertTrue(outside.delete()) }
    }

    @Test fun erasureOnlyRemovesTheSelectedScopesOrphanCiphertext() = withVault { id, vault ->
        withVault { otherId, other ->
            vault.create().fill(0); other.create().fill(0)
            val orphan = File(record(id).parentFile, "$id-orphan.tmp").apply { writeBytes(byteArrayOf(1, 2)) }
            val otherOrphan = File(record(id).parentFile, "$otherId-orphan.tmp").apply { writeBytes(byteArrayOf(3, 4)) }
            vault.erase()
            assertFalse(orphan.exists())
            assertTrue(otherOrphan.exists())
            other.read()?.fill(0) ?: fail("Another scope was erased")
        }
    }

    @Test fun invalidIdsCannotAddressFilesOrKeystoreAliases() {
        for (id in listOf("", "../unsafe", "A".repeat(64), "0".repeat(63), "g".repeat(64))) {
            assertFails { AndroidLedgerKeyVault(context, id) }
        }
    }

    private fun withVault(block: (String, AndroidLedgerKeyVault) -> Unit) {
        val id = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        val vault = AndroidLedgerKeyVault(context, id)
        try { block(id, vault) } finally { vault.erase() }
    }
}
