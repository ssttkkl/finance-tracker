package com.finance.tracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.finance.tracker.client.NewCashTransfer
import com.finance.tracker.client.freezeCashTransfer
import com.finance.tracker.data.SqlCashCreationJournal
import com.finance.tracker.domain.CashCreationDraft
import com.finance.tracker.domain.CashCreationMode
import com.finance.tracker.domain.CashCreationRecovery
import com.finance.tracker.domain.CashOrdinaryInput
import com.finance.tracker.domain.CashTransferInput
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlCashCreationJournalTest {
    @Test
    fun preservesOneExactPendingCommandAcrossConnectionsInItsOriginalScope() = runTest {
        val file = File.createTempFile("cash-creation-journal", ".db")
        val scope = LedgerCacheScope("https://ledger.example.invalid", "synthetic", "one")
        val input = NewCashTransfer("1", "2", "0.123456789012345678", "CNY", "CNY", "2026-11-26", "12:00")
        val command = freezeCashTransfer(input, "From", "To", "original")
        val draft = CashCreationDraft(CashOrdinaryInput(emptyMap()), CashTransferInput(), CashCreationMode.TRANSFER)
        val original = CashCreationRecovery(command, draft)
        val different = original.copy(command = command.copy(id = "different"))
        try {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { db ->
                val journal = SqlCashCreationJournal.open(db)
                assertTrue(journal.swap(scope, "token", null, original))
                assertFalse(journal.swap(scope, "token", null, different))
            }
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { db ->
                val journal = SqlCashCreationJournal.open(db)
                assertEquals(original, journal.read(scope, "token"))
                assertNull(journal.read(scope, "later-token"))
                assertNull(journal.read(scope.copy(workspaceId = "two"), "token"))
                assertNull(journal.read(scope.copy(principal = "another"), "token"))
                assertFalse(journal.swap(scope, "token", different, null))
                assertEquals(original, journal.read(scope, "token"))
                assertTrue(journal.swap(scope, "token", original, null))
                assertNull(journal.read(scope, "token"))
            }
        } finally {
            file.delete()
        }
    }
}
