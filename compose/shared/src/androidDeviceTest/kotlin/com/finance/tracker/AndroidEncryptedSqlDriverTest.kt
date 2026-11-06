package com.finance.tracker

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.finance.tracker.data.AndroidLedgerDriverOpenCloseFailure
import com.finance.tracker.data.openAndroidLedgerSqlDriver
import java.io.File
import java.util.UUID
import net.zetetic.database.Logger
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidEncryptedSqlDriverTest {
    @Test fun realCipherKeepsExactTextAndAnEncryptedRollbackJournal() = withDirectory { directory ->
        val key = key()
        val driver = openAndroidLedgerSqlDriver(directory, key)
        key.fill(0)
        try {
            assertEquals("4.20.0 community", scalar(driver, "PRAGMA cipher_version"))
            assertEquals("libtomcrypt", scalar(driver, "PRAGMA cipher_provider"))
            assertEquals("delete", scalar(driver, "PRAGMA journal_mode"))
            assertEquals("2", scalar(driver, "PRAGMA temp_store"))
            driver.execute(null, "CREATE TABLE fact(note TEXT, amount TEXT)", 0)
            driver.execute(null, "INSERT INTO fact VALUES (?,?)", 2) { bindString(0, MARKER); bindString(1, AMOUNT) }
            assertTrue(handles(directory) > 0)
            val transaction = object : TransacterImpl(driver) {}
            assertFailsWith<IllegalStateException> {
                transaction.transaction {
                    driver.execute(null, "UPDATE fact SET note = ?", 1) { bindString(0, "synthetic rolled-back marker") }
                    val journal = File(directory, "ledger.db-journal").readBytes()
                    assertTrue(journal.isNotEmpty())
                    assertFalse(journal.decodeToString().contains(MARKER))
                    error("Synthetic transaction rollback")
                }
            }
            assertEquals(MARKER, scalar(driver, "SELECT note FROM fact"))
            assertEquals(AMOUNT, scalar(driver, "SELECT amount FROM fact"))
        } finally { driver.close() }
        assertEquals(0, handles(directory))
        val stored = File(directory, "ledger.db").readBytes().decodeToString()
        assertFalse(stored.startsWith("SQLite format 3"))
        assertFalse(stored.contains(MARKER)); assertFalse(stored.contains(AMOUNT))
        val reopened = openAndroidLedgerSqlDriver(directory, key())
        try { assertEquals(MARKER, scalar(reopened, "SELECT note FROM fact")) } finally { reopened.close() }
        driver.close()
        assertFails { scalar(driver, "SELECT note FROM fact") }
    }

    @Test fun wrongKeyNeverDeletesTheFileOrLeavesADataDescriptor() = withDirectory { directory ->
        val driver = openAndroidLedgerSqlDriver(directory, key())
        try { driver.execute(null, "CREATE TABLE fact(value TEXT)", 0) } finally { driver.close() }
        val file = File(directory, "ledger.db")
        val before = file.readBytes()
        repeat(3) { index ->
            val wrong = ByteArray(32) { (index + 1).toByte() }
            try {
                val failure = assertFails { openAndroidLedgerSqlDriver(directory, wrong) }
                assertFalse(failure is AndroidLedgerDriverOpenCloseFailure)
            } finally { wrong.fill(0) }
            assertContentEquals(before, file.readBytes())
            assertEquals(0, handles(directory))
        }
        openAndroidLedgerSqlDriver(directory, key()).close()
    }

    @Test fun ordinaryInitializationFailureIsPreservedAfterRealSdkCleanup() = withDirectory { directory ->
        val original = IllegalStateException("Synthetic post-key failure")
        assertSame(original, assertFails {
            openAndroidLedgerSqlDriver(directory, key(), afterKey = { throw original })
        })
        assertEquals(0, handles(directory))
        openAndroidLedgerSqlDriver(directory, key()).close()
    }

    @Test fun aReportedCloseFailureIsStickyEvenWhenTheSdkCloseReturns() = withDirectory { directory ->
        val reported = IllegalStateException("Synthetic SDK close acknowledgement failure")
        var closes = 0
        val driver = openAndroidLedgerSqlDriver(directory, key(), closeDatabase = {
            closes++; it.close()
            Logger.e("SQLiteConnectionPool", "Synthetic close error report", reported)
        })
        val first = assertFails { driver.close() }
        assertSame(reported, first.cause)
        assertSame(first, assertFails { driver.close() })
        assertEquals(1, closes)
        assertFails { scalar(driver, "SELECT 1") }
        assertEquals(0, handles(directory))
    }

    @Test fun initializationAndCloseDiagnosticsAreBothRetained() = withDirectory { directory ->
        val original = IllegalStateException("Synthetic initialization failure")
        val closing = IllegalStateException("Synthetic SDK close report")
        val error = assertFailsWith<AndroidLedgerDriverOpenCloseFailure> {
            openAndroidLedgerSqlDriver(directory, key(), afterKey = {
                Logger.e("SQLiteConnectionPool", "Synthetic cleanup error report", closing)
                throw original
            })
        }
        assertSame(original, error.cause)
        assertTrue(error.suppressedExceptions.any { it === closing || it.cause === closing })
        assertEquals(0, handles(directory))
    }

    @Test fun aLargeTextCellSurvivesTheActualSqlDelightAdapter() = withDirectory { directory ->
        val driver = openAndroidLedgerSqlDriver(directory, key())
        val text = "synthetic-large-ledger:" + "x".repeat(24 * 1024 * 1024)
        try {
            driver.execute(null, "CREATE TABLE fact(value TEXT)", 0)
            driver.execute(null, "INSERT INTO fact VALUES (?)", 1) { bindString(0, text) }
            assertTrue(text == scalar(driver, "SELECT value FROM fact"), "Large payload changed")
        } finally { driver.close() }
        val reopened = openAndroidLedgerSqlDriver(directory, key())
        try { assertTrue(text == scalar(reopened, "SELECT value FROM fact"), "Reopened large payload changed") }
        finally { reopened.close() }
    }

    @Test fun invalidKeyMaterialCreatesNoDatabase() = withDirectory { directory ->
        for (size in listOf(0, 31, 33)) assertFails { openAndroidLedgerSqlDriver(directory, ByteArray(size)) }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun anOrdinaryQueryConstraintFailureDoesNotPoisonTheOwner() = withDirectory { directory ->
        val driver = openAndroidLedgerSqlDriver(directory, key())
        try {
            driver.execute(null, "CREATE TABLE fact(value TEXT UNIQUE)", 0)
            driver.execute(null, "INSERT INTO fact VALUES (?)", 1) { bindString(0, MARKER) }
            assertFails { driver.execute(null, "INSERT INTO fact VALUES (?)", 1) { bindString(0, MARKER) } }
            assertEquals(MARKER, scalar(driver, "SELECT value FROM fact"))
        } finally { driver.close() }
        assertEquals(0, handles(directory))
    }

    private fun scalar(driver: SqlDriver, sql: String): String = driver.executeQuery(null, sql, { cursor ->
        check(cursor.next().value); QueryResult.Value(checkNotNull(cursor.getString(0)))
    }, 0).value

    private fun handles(directory: File): Int {
        val expected = File(directory, "ledger.db").canonicalPath
        return File("/proc/self/fd").listFiles()!!.count { descriptor ->
            try { File(Os.readlink(descriptor.path)).canonicalPath.let { it == expected || it.startsWith("$expected-") } }
            catch (_: android.system.ErrnoException) { false }
        }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "ledger-cipher-${UUID.randomUUID()}")
        assertTrue(directory.mkdir()); Os.chmod(directory.path, 0x1C0)
        try { block(directory) } finally { assertTrue(directory.deleteRecursively()) }
    }

    private fun key() = ByteArray(32) { (it + 17).toByte() }
    private companion object {
        const val MARKER = "synthetic-android-ledger-cleartext-marker"
        const val AMOUNT = "12345678901234567890.123456789012345678"
    }
}
