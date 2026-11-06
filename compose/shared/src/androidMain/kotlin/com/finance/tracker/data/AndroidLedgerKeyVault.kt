package com.finance.tracker.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The caller supplies a complete scope digest and only creates a key for an explicitly new database. */
internal class AndroidLedgerKeyVault(context: Context, private val keyId: String) {
    init {
        require(keyId.length == 64 && keyId.all { it in '0'..'9' || it in 'a'..'f' }) {
            "ledger_key_scope_invalid"
        }
    }

    private val root = File(context.applicationContext.noBackupFilesDir, "ledger-key-vault")
    private val records = File(root, "records")
    private val record = File(records, "$keyId.key")
    private val alias = "finance-tracker-ledger-cache-$keyId"
    private val authenticatedScope = "ledger-key-v1:$keyId".toByteArray(Charsets.UTF_8)

    fun read(): ByteArray? = keyOperation { readRecord() }

    fun create(): ByteArray = checkNotNull(keyOperation {
        readRecord()?.let { return@keyOperation it }
        val store = keyStore()
        val wrappingKey = if (store.containsAlias(alias)) wrappingKey(store) else {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build())
                generateKey()
            }
        }
        val key = ByteArray(32)
        var transferred = false
        try {
            SecureRandom().nextBytes(key)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            cipher.updateAAD(authenticatedScope)
            val ciphertext = cipher.doFinal(key)
            check(cipher.iv.size == 12 && ciphertext.size == 48) { "ledger_key_cipher_invalid" }
            persist(byteArrayOf(1) + cipher.iv + ciphertext)
            transferred = true
            key
        } finally { if (!transferred) key.fill(0) }
    })

    fun erase() = locked {
        unlink(record)
        val files = checkNotNull(records.listFiles()) { "ledger_key_directory_unavailable" }
        for (file in files) {
            if (file.name.startsWith("$keyId-") && file.name.endsWith(".tmp")) unlink(file)
        }
        syncDirectory()
        keyStore().deleteEntry(alias)
    }

    private fun readRecord(): ByteArray? {
        val descriptor = try { Os.open(record.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0) }
        catch (failure: ErrnoException) {
            if (failure.errno == OsConstants.ENOENT) return null
            throw failure
        }
        val encoded = ByteArray(61)
        FileInputStream(descriptor).use { input ->
            val stat = Os.fstat(descriptor)
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size == 61L) { "ledger_key_record_invalid" }
            check(stat.st_mode and 0x3F == 0) { "ledger_key_record_permissions_invalid" }
            DataInputStream(input).readFully(encoded)
            check(input.read() == -1) { "ledger_key_record_invalid" }
        }
        check(encoded[0] == 1.toByte()) { "ledger_key_record_invalid" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(keyStore()), GCMParameterSpec(128, encoded.copyOfRange(1, 13)))
        cipher.updateAAD(authenticatedScope)
        val key = cipher.doFinal(encoded, 13, 48)
        try {
            check(key.size == 32) { "ledger_key_record_invalid" }
            // Also confirms publication after a prior call failed following rename.
            syncDirectory()
            return key
        } catch (failure: Throwable) { key.fill(0); throw failure }
    }

    private fun wrappingKey(store: KeyStore): SecretKey =
        checkNotNull(store.getKey(alias, null) as? SecretKey) { "ledger_wrapping_key_missing" }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun persist(encoded: ByteArray) {
        val temporary = File.createTempFile("$keyId-", ".tmp", records)
        var failure: Throwable? = null
        try {
            Os.chmod(temporary.path, 0x180)
            FileOutputStream(temporary).use { output -> output.write(encoded); output.fd.sync() }
            Os.rename(temporary.path, record.path)
            syncDirectory()
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { unlink(temporary) }
            catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private fun syncDirectory() {
        val descriptor = Os.open(records.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        FileInputStream(descriptor).use {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "ledger_key_directory_invalid" }
            it.fd.sync()
        }
    }

    private fun keyOperation(operation: () -> ByteArray?): ByteArray? =
        acquireAndroidLedgerKey({ block -> locked(block) }, operation)

    private fun <T> locked(operation: () -> T): T = synchronized(monitor) {
        ensureDirectory(root)
        val locks = File(root, "locks")
        ensureDirectory(locks)
        ensureDirectory(records)
        val descriptor = Os.open(File(locks, "$keyId.lock").path,
            OsConstants.O_CREAT or OsConstants.O_RDWR or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0x180)
        FileOutputStream(descriptor).use { output ->
            check(OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) { "ledger_key_lock_invalid" }
            output.channel.lock().use { operation() }
        }
    }

    private fun ensureDirectory(directory: File) {
        try { Os.mkdir(directory.path, 0x1C0) }
        catch (failure: ErrnoException) { if (failure.errno != OsConstants.EEXIST) throw failure }
        val stat = Os.lstat(directory.path)
        check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_mode and 0x3F == 0) { "ledger_key_directory_invalid" }
    }

    private fun unlink(file: File) {
        try {
            val stat = Os.lstat(file.path)
            check(OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode)) { "ledger_key_file_invalid" }
            Os.remove(file.path)
        }
        catch (failure: ErrnoException) { if (failure.errno != OsConstants.ENOENT) throw failure }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val monitor = Any()
    }
}

internal fun acquireAndroidLedgerKey(lock: (() -> Unit) -> Unit, produce: () -> ByteArray?): ByteArray? {
    var key: ByteArray? = null
    var transferred = false
    try {
        lock { key = produce() }
        transferred = true
        return key
    } finally {
        if (!transferred) key?.fill(0)
    }
}
