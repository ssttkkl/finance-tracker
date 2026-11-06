@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.finance.tracker

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.sqliter.DatabaseConnection
import co.touchlab.sqliter.DatabaseManager
import co.touchlab.sqliter.createDatabaseManager
import com.finance.tracker.data.openAppleLedgerSqlDriver
import com.finance.tracker.data.AppleLedgerDriverOpenCloseFailure
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.F_GETPATH
import platform.posix.SEEK_END
import platform.posix._SC_OPEN_MAX
import platform.posix.fclose
import platform.posix.fcntl
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.rewind
import platform.posix.sysconf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertSame

class AppleEncryptedSqlDriverTest {
    @Test
    fun failedOpenWithAnUncertainCloseRetainsBothDiagnostics() = withDirectory { directory, marker ->
        val opening = IllegalStateException("Synthetic opening failure")
        val closing = IllegalStateException("Synthetic close acknowledgement failure")
        val key = syntheticKey()
        try {
            val failure = assertFailsWith<AppleLedgerDriverOpenCloseFailure> {
                failAppleLedgerDriverOpening(directory, key, opening, closing)
            }
            assertSame(opening, failure.cause)
            assertTrue(failure.suppressedExceptions.any { it === closing })
            assertEquals(0, databaseHandles(marker))
        } finally { key.fill(0) }
    }

    @Test
    fun realCipherPersistsExactTextAndEncryptsTheActiveRollbackJournal() = withDirectory { directory, _ ->
        val key = syntheticKey()
        val driver = openAppleLedgerSqlDriver(directory, key)
        key.fill(0)
        try {
            assertEquals("4.20.0 community", scalar(driver, "PRAGMA cipher_version"))
            assertEquals("delete", scalar(driver, "PRAGMA journal_mode"))
            assertEquals("2", scalar(driver, "PRAGMA temp_store"))
            driver.execute(null, "CREATE TABLE fact(note TEXT, amount TEXT)", 0)
            driver.execute(null, "INSERT INTO fact VALUES (?,?)", 2) {
                bindString(0, MARKER)
                bindString(1, AMOUNT)
            }
            val transactions = object : TransacterImpl(driver) {}
            assertFailsWith<IllegalStateException> {
                transactions.transaction {
                    driver.execute(null, "UPDATE fact SET note='rolled-back-native-marker-775'", 0)
                    val journal = bytes("$directory/ledger.db-journal")
                    assertTrue(journal.isNotEmpty())
                    assertFalse(journal.decodeToString().contains(MARKER))
                    error("Synthetic transaction abort")
                }
            }
            assertEquals(MARKER, scalar(driver, "SELECT note FROM fact"))
            assertEquals(AMOUNT, scalar(driver, "SELECT amount FROM fact"))
        } finally {
            driver.close()
        }
        val stored = bytes("$directory/ledger.db").decodeToString()
        assertFalse(stored.startsWith("SQLite format 3"))
        assertFalse(stored.contains(MARKER))
        assertFalse(stored.contains(AMOUNT))
        val reopened = openAppleLedgerSqlDriver(directory, syntheticKey())
        try {
            assertEquals(MARKER, scalar(reopened, "SELECT note FROM fact"))
            assertEquals(AMOUNT, scalar(reopened, "SELECT amount FROM fact"))
        } finally {
            reopened.close()
        }
        driver.close()
        assertFails { scalar(driver, "SELECT * FROM fact") }
    }

    @Test
    fun repeatedWrongKeyOpenClosesEveryNativeHandleAndPreservesTheFile() = withDirectory { directory, marker ->
        openAppleLedgerSqlDriver(directory, syntheticKey()).also { driver ->
            try {
                driver.execute(null, "CREATE TABLE fact(note TEXT)", 0)
                driver.execute(null, "INSERT INTO fact VALUES (?)", 1) { bindString(0, MARKER) }
            } finally {
                driver.close()
            }
        }
        val before = bytes("$directory/ledger.db")
        assertEquals(0, databaseHandles(marker))
        repeat(3) {
            assertFails { openAppleLedgerSqlDriver(directory, ByteArray(32) { 77 }) }
            assertEquals(0, databaseHandles(marker), "Failed open leaked a database descriptor")
            assertContentEquals(before, bytes("$directory/ledger.db"))
        }
        val reopened = openAppleLedgerSqlDriver(directory, syntheticKey())
        try {
            assertEquals(MARKER, scalar(reopened, "SELECT note FROM fact"))
        } finally {
            reopened.close()
        }
        assertEquals(0, databaseHandles(marker))
    }

    @Test
    fun invalidKeyIsRejectedBeforeCreatingAFile() = withDirectory { directory, marker ->
        for (size in listOf(0, 31, 33)) {
            assertFails { openAppleLedgerSqlDriver(directory, ByteArray(size)) }
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$directory/ledger.db"))
            assertEquals(0, databaseHandles(marker))
        }
    }

    @Test
    fun closeErrorRemainsUncertainEvenAfterTheVendorClosedFlagBecomesTrue() = withDirectory { directory, marker ->
        val closeFailure = IllegalStateException("Synthetic close acknowledgement failure")
        val driver = openAppleLedgerSqlDriver(directory, syntheticKey()) { configuration ->
            val real = createDatabaseManager(configuration)
            object : DatabaseManager by real {
                override fun createMultiThreadedConnection(): DatabaseConnection {
                    val connection = real.createMultiThreadedConnection()
                    return object : DatabaseConnection by connection {
                        override fun close() {
                            connection.close()
                            assertTrue(connection.closed)
                            throw closeFailure
                        }
                    }
                }
            }
        }
        assertSame(closeFailure, assertFails { driver.close() })
        assertEquals(0, databaseHandles(marker))
        assertSame(closeFailure, assertFails { driver.close() })
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath("$directory/ledger.db"))
    }

    private fun scalar(driver: SqlDriver, sql: String): String? = driver.executeQuery(null, sql, { cursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
    }, 0).value

    private fun syntheticKey() = ByteArray(32) { (it + 1).toByte() }

    private fun withDirectory(block: (String, String) -> Unit) {
        val marker = "ledger-cipher-test-${NSUUID().UUIDString}"
        val directory = NSTemporaryDirectory() + marker
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        try {
            block(directory, marker)
        } finally {
            assertTrue(NSFileManager.defaultManager.removeItemAtPath(directory, null))
        }
    }

    private fun databaseHandles(marker: String): Int = memScoped {
        val limit = sysconf(_SC_OPEN_MAX)
        check(limit > 0 && limit <= Int.MAX_VALUE)
        val path = allocArray<kotlinx.cinterop.ByteVar>(1024)
        (0 until limit.toInt()).count { descriptor ->
            fcntl(descriptor, F_GETPATH, path) == 0 && path.toKString().contains(marker)
        }
    }

    private fun bytes(path: String): ByteArray {
        val file = fopen(path, "rb") ?: error("Synthetic database file absent")
        try {
            check(fseek(file, 0, SEEK_END) == 0)
            val size = ftell(file)
            check(size > 0 && size <= Int.MAX_VALUE)
            rewind(file)
            return ByteArray(size.toInt()).also { value ->
                value.usePinned { check(fread(it.addressOf(0), 1u, size.toULong(), file) == size.toULong()) }
            }
        } finally {
            fclose(file)
        }
    }

    private companion object {
        const val MARKER = "private-native-marker-239"
        const val AMOUNT = "-12345678901234567890.123456789012345678"
    }
}

/** Real connection lifecycle with a failed open and a failed acknowledgement after actual close. */
internal fun failAppleLedgerDriverOpening(directory: String, key: ByteArray,
    opening: Throwable, closing: Throwable): SqlDriver = openAppleLedgerSqlDriver(directory, key) { configuration ->
    val onCreate = configuration.lifecycleConfig.onCreateConnection
    createDatabaseManager(configuration.copy(lifecycleConfig = configuration.lifecycleConfig.copy(onCreateConnection = { real ->
        val connection = object : DatabaseConnection by real {
            override fun close() { real.close(); throw closing }
        }
        onCreate(connection)
        throw opening
    })))
}
