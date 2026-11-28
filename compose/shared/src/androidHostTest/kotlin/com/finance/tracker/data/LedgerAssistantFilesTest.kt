package com.finance.tracker.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerAssistantFilesTest {
    @Test
    fun fileChangesAndReceiptsAreAtomicAndConversationScoped() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerAssistantConversations.open(driver)
            val initial = LedgerAssistantConversation("report", "2026-11-28T00:00:00Z")
            assertTrue(store.swap(null, initial))
            val file = newLedgerAssistantTemporaryFile("报表.csv", "\"月份\",\"-88\"\r\n")
            val next = initial.copy(revision = 1)
            val receipt = LedgerAssistantFileReceipt("call-1", "fingerprint", JsonObject(emptyMap()))
            assertTrue(store.commitSkillRun(initial, next, emptySet(), listOf(file), receipt, 100L))
            assertEquals(file.text, store.readSkillFile(initial.id, file.metadata.id, 100L)?.text)
            assertNull(store.readSkillFile("other", file.metadata.id, 100L))
            assertEquals(receipt, store.skillReceipt(initial.id, "call-1"))
            assertFalse(store.commitSkillRun(initial, next, setOf(file.metadata.id), emptyList(),
                receipt.copy(callId = "stale-call"), 101L))
            assertNull(store.skillReceipt(initial.id, "stale-call"))
            assertEquals(file.text, store.readSkillFile(initial.id, file.metadata.id, 101L)?.text)
            assertTrue(store.delete(next))
            assertNull(store.readSkillFile(initial.id, file.metadata.id, 102L))
            assertNull(store.skillReceipt(initial.id, "call-1"))
        }
    }
    @Test
    fun immutableAttachmentSurvivesTemporaryExpiryAndReopening() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerAssistantConversations.open(driver)
            val initial = LedgerAssistantConversation("report", "2026-11-28T00:00:00Z")
            assertTrue(store.swap(null, initial))
            val file = newLedgerAssistantTemporaryFile("表格.csv", "=1+1,88")
            val frozen = LedgerAssistantFileContent(file.metadata.copy(id = "attachment", attached = true), file.text)
            val next = initial.copy(revision = 1, messages = listOf(LedgerAssistantMessage(attachment = frozen.metadata)))
            val receipt = LedgerAssistantFileReceipt("publish", "fingerprint", JsonObject(emptyMap()))
            assertTrue(store.commitSkillRun(initial, next, emptySet(), listOf(file, frozen), receipt, 100L))
            val reopened = SqlLedgerAssistantConversations.open(driver)
            val afterExpiry = 100L + LEDGER_ASSISTANT_FILE_TTL_MS
            assertEquals(listOf(frozen.metadata), reopened.skillFiles(initial.id, afterExpiry))
            assertNull(reopened.readSkillFile(initial.id, file.metadata.id, afterExpiry))
            assertEquals(frozen.metadata, reopened.list().single().messages.single().attachment)
            assertEquals("\"'=1+1\",\"88\"\r\n", reopened.readSkillFile(initial.id, "attachment", afterExpiry)?.text)
        }
    }

    @Test
    fun transactionRejectsExpiredOrChangedSourceAndRollsBackAfterSwap() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            var ledgerVersion = 1L
            val store = SqlLedgerAssistantConversations.open(driver) { ledgerVersion }
            val initial = LedgerAssistantConversation("report", "2026-11-28T00:00:00Z")
            assertTrue(store.swap(null, initial))
            val file = newLedgerAssistantTemporaryFile("report.txt", "private")
            val saved = initial.copy(revision = 1)
            val receipt = LedgerAssistantFileReceipt("run", "fingerprint", JsonObject(emptyMap()))
            assertTrue(store.commitSkillRun(initial, saved, emptySet(), listOf(file), receipt, 10L, expectedLedgerVersion = 1L))
            val next = saved.copy(revision = 2)
            val attachment = LedgerAssistantFileContent(file.metadata.copy(id = "sent", attached = true), file.text)
            val send = receipt.copy(callId = "send")
            ledgerVersion++
            assertEquals("ledger_changed", assertFailsWith<LedgerAssistantScriptFailure> {
                store.commitSkillRun(saved, next, emptySet(), listOf(attachment), send, 11L, expectedLedgerVersion = 1L)
            }.reason)
            assertEquals("file_expired", assertFailsWith<LedgerAssistantScriptFailure> {
                store.commitSkillRun(saved, next, emptySet(), listOf(attachment), send, 10L + LEDGER_ASSISTANT_FILE_TTL_MS,
                    requiredSource = file.metadata.id)
            }.reason)
            assertFailsWith<Exception> {
                store.commitSkillRun(saved, next, emptySet(), listOf(attachment, attachment), send, 11L)
            }
            assertEquals(listOf(saved), store.list())
            assertNull(store.readSkillFile(initial.id, "sent", 11L))
            assertNull(store.skillReceipt(initial.id, "send"))
        }
    }

    @Test
    fun sqliteFullLeavesNoPartialAttachmentOrReceiptAndCanRetry() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            var insertError: Int? = null
            val observedDriver = object : SqlDriver by driver {
                override fun execute(
                    identifier: Int?, sql: String, parameters: Int,
                    binders: (SqlPreparedStatement.() -> Unit)?,
                ): QueryResult<Long> = try {
                    driver.execute(identifier, sql, parameters, binders)
                } catch (failure: java.sql.SQLException) {
                    if (sql.trimStart().startsWith("INSERT INTO local_ledger_assistant_files")) {
                        insertError = failure.errorCode
                    }
                    throw failure
                }
            }
            val store = SqlLedgerAssistantConversations.open(observedDriver)
            val initial = LedgerAssistantConversation("report", "2026-11-28T00:00:00Z")
            assertTrue(store.swap(null, initial))
            val pages = driver.executeQuery(null, "PRAGMA page_count", { rows ->
                check(rows.next().value)
                QueryResult.Value(checkNotNull(rows.getLong(0)))
            }, 0).value
            driver.execute(null, "PRAGMA max_page_count = ${pages + 4}", 0)
            val file = newLedgerAssistantTemporaryFile("report.txt", "账".repeat(100_000))
            val attachment = LedgerAssistantFileContent(file.metadata.copy(attached = true), file.text)
            val next = initial.copy(revision = 1, messages = listOf(LedgerAssistantMessage(attachment = attachment.metadata)))
            val receipt = LedgerAssistantFileReceipt("send", "fixture", JsonObject(emptyMap()))
            assertFailsWith<java.sql.SQLException> {
                store.commitSkillRun(initial, next, emptySet(), listOf(attachment), receipt, 100L)
            }
            assertEquals(13, insertError, "SQLite must report SQLITE_FULL at the attachment insert")
            assertEquals(listOf(initial), store.list())
            assertNull(store.readSkillFile(initial.id, attachment.metadata.id, 100L))
            assertNull(store.skillReceipt(initial.id, receipt.callId))
            driver.execute(null, "PRAGMA max_page_count = ${pages + 1024}", 0)
            assertTrue(store.commitSkillRun(initial, next, emptySet(), listOf(attachment), receipt, 100L))
            assertEquals(attachment.text, store.readSkillFile(initial.id, attachment.metadata.id, 100L)?.text)
            assertEquals(listOf(next), store.list())
            assertEquals(receipt, store.skillReceipt(initial.id, receipt.callId))
        }
    }

}
