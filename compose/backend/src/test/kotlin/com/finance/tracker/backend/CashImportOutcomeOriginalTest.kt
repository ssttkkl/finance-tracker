package com.finance.tracker.backend

import java.lang.reflect.Proxy
import java.sql.Connection
import kotlin.test.*

class CashImportOutcomeOriginalTest {
    @Test fun rowResultsKeepPreviewKnownSkipAndActualSavedIdentity() {
        val plan = mapOf("row_decisions" to listOf(
            mapOf("row_ref" to "f:r1", "planned_action" to "skip", "code" to "fixed_field_conflict"),
            mapOf("row_ref" to "f:r2", "planned_action" to "create_posted", "operation_ref" to "op2"),
            mapOf("row_ref" to "f:r3", "planned_action" to "duplicate_in_batch", "operation_ref" to "op2")))
        val actual = mapOf("op2" to mapOf("outcome" to "created_posted", "transaction_id" to "12"))
        val before = sourceJson(listOf(plan, actual))
        val rows = CashPlanFinalizer.rowResults(plan, actual)
        assertEquals(listOf("f:r1" to "skipped", "f:r2" to "created_posted", "f:r3" to "duplicate_in_batch"),
            rows.map { it["item_ref"] to it["outcome"] })
        assertEquals("fixed_field_conflict", rows[0]["code"])
        assertEquals("12", rows[1]["transaction_id"])
        assertEquals("12", rows[2]["transaction_id"])
        assertEquals(before, sourceJson(listOf(plan, actual)))
    }

    @Test fun previewKnownGroupSkipDoesNotTriggerExecutionFailure() {
        val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "isClosed" -> false
                "getAutoCommit" -> false
                "toString" -> "NoSqlContractConnection"
                else -> error("Unexpected database access: ${method.name}")
            }
        } as Connection
        val writer = CashPlanRelationWriter(CashSqlContext(connection, "synthetic-workspace", true), "synthetic-user")
        val group = mapOf("suggestion_id" to "g", "parent_ids" to emptyList<String>(), "action" to "skip", "code" to "relation_context_changed")
        val plan = mapOf("relation_groups" to listOf(group))
        val affected = mutableSetOf<Long>()
        assertEquals(listOf(mapOf("result_kind" to "relation", "item_ref" to "g", "outcome" to "failed", "code" to "relation_context_changed")),
            writer.saveGroups(plan, plan, emptyMap(), emptyMap(), affected, emptySet()))
        assertTrue(affected.isEmpty())
    }

    @Test fun currentCashCommandsKeepLegacyImportExecutionSeparated() {
        val legacy = setOf("detect_import", "scan_import", "scan_import_batch", "preview_import", "preview_import_batch",
            "commit_import", "commit_import_batch", "get_import_commit_result", "detectImport", "scanImport", "scanImportBatch",
            "previewImport", "previewImportBatch", "commitImport", "commitImportBatch", "getImportCommitResult")
        for (type in listOf(ManualCashService::class.java, CashDraftSaveService::class.java, CashDeletionService::class.java,
            CashClassificationService::class.java, CashTransferService::class.java)) {
            assertTrue(type.methods.none { it.name.substringBefore('$') in legacy }, type.name)
        }
        val http = CashImportHttpService::class.java.methods.map { it.name.substringBefore('$') }.toSet()
        assertTrue(http.containsAll(setOf("create", "preview", "commit", "resolve")))
        val commands = CashImportCommandService::class.java.methods.map { it.name.substringBefore('$') }.toSet()
        assertTrue(commands.containsAll(setOf("commit", "resolve")))
    }

    @Test fun sharedSessionErrorsKeepOriginalBaseTypeAndMessages() {
        val errors: List<Pair<Throwable, String>> = listOf(
            ParsedImportNotFound() to "import_session_not_found", ParsedImportForbidden() to "import_session_forbidden",
            ParsedImportExpired() to "import_session_expired", ParsedImportStorageUnavailable() to "import_session_storage_unavailable")
        for ((error, code) in errors) {
            assertIs<ParsedImportFailure>(error)
            assertEquals(code, error.message)
            assertEquals(code, error.code)
        }
    }
}
