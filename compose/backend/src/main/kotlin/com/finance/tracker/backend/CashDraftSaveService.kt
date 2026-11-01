package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import kotlinx.serialization.json.*

internal class CashDraftSaveFailure(val code: String, val fields: List<String> = emptyList(), val policy: Boolean = false,
    var commitState: String? = null) : RuntimeException(code)

internal class CashDraftSaveService(private val database: Database, private val now: () -> Instant = Instant::now,
    private val beforeWorkspaceLock: (java.sql.Connection) -> Unit = {}, private val afterStep: (String) -> Unit = {},
    private val beforeOutcomeRead: () -> Unit = {}, private val maxInputBytes: Int = 64 * 1024 * 1024,
    private val receiptId: () -> String = { java.util.UUID.randomUUID().toString() }) {
    private val reads = ReadServices(database, now)
    private fun same(expected: Principal, actual: Principal) {
        if (expected.userId != actual.userId || expected.workspaceId != actual.workspaceId) throw AccessFailure(true)
    }
    private fun identifier(value: String?): Long = value?.takeIf { it.matches(Regex("[0-9]{1,19}")) }?.toLongOrNull()?.takeIf { it > 0 }
        ?: throw CashDraftSaveFailure("record_not_found")
    fun save(token: String?, transactionId: String?, expectedRevision: JsonElement?, patch: Any?, key: String?, requestPrincipal: Principal? = null): JsonObject {
        val boundary = CashImportCommitBoundary(afterStep)
        try {
            val transaction = identifier(transactionId)
            val revision = (expectedRevision as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }
                ?.content?.toLongOrNull()?.takeIf { it > 0 } ?: throw CashDraftSaveFailure("record_input_invalid")
            if (patch !is Map<*, *>) throw CashDraftSaveFailure("record_input_invalid")
            val values = listOf("cash-draft-save-v1", transaction.toString(), revision, patch)
            val digest = try {
                ParsedImportCodec().budget(values, maxInputBytes.toLong())
                val encoded = strictUtf8(sourceJson(values).toString())
                if (encoded.size > maxInputBytes) throw CashDraftSaveFailure("record_input_invalid")
                tokenDigest(encoded.toString(Charsets.UTF_8))
            } catch (_: ParsedImportDataError) { throw CashDraftSaveFailure("record_input_invalid") }
              catch (_: java.nio.charset.CharacterCodingException) { throw CashDraftSaveFailure("record_input_invalid") }
            @Suppress("UNCHECKED_CAST") val input = patch as Map<String, Any?>
            val requestKey = CashImportProtocol.idempotencyKey(key)
            val principal = reads.currentContext(token); if (requestPrincipal != null) same(principal, requestPrincipal)
            val receipt = database.writeGuarded(boundary::committing) { connection ->
                val workspace = principal.workspaceId!!
                beforeWorkspaceLock(connection); if (!connection.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
                val current = reads.lockedContext(connection, token, workspace, write = true); same(principal, current)
                val receipts = ReceiptRepository(connection, workspace, database.sqlite, now, receiptId)
                receipts.replay(current.userId, requestKey, digest, "save_draft")?.let { return@writeGuarded ReceiptRepositoryView.strip(it) }
                boundary.noPriorResult()
                val context = CashSqlContext(connection, workspace, database.sqlite, now)
                val t = CASH_TRANSACTIONS
                val actual = context.sql.select(t.ROW_REVISION).from(t).where(t.WORKSPACE_ID.eq(workspace)).and(t.ID.eq(transaction))
                    .and(t.DELETED_AT.isNull).fetchOne(t.ROW_REVISION) ?: throw CashDraftSaveFailure("record_not_found")
                if (actual != revision) throw ReceiptConflict("record_changed")
                val operation = MutationPlanBuilder(context).prepareDirectDraft(transaction, input)
                val state = CashProjectionRepository(context).requireReadyStateLock()
                val written = CashRecordWriter(context).save(operation, emptyMap()); afterStep("record_write")
                val maintained = CashProjectionService.maintainInSession(context, written.touched + transaction, state); afterStep("projection")
                val result = receipts.create(current.userId, requestKey, digest, "save_draft", buildJsonObject {
                    put("status", "completed"); put("summary", buildJsonObject { put("saved_draft", 1) })
                    put("projection_version", maintained.getValue("projection_version"))
                }, JsonArray(listOf(buildJsonObject {
                    put("result_kind", "row"); put("item_ref", transaction.toString()); put("transaction_id", transaction.toString()); put("outcome", "saved_draft")
                })))
                afterStep("receipt"); same(principal, reads.lockedContext(connection, token, workspace, write = true))
                ReceiptRepositoryView.strip(result)
            }
            val detail = try {
                beforeOutcomeRead(); same(principal, reads.currentContext(token))
                "available" to CashRecordDetailService(database, now).detail(token, transaction.toString())
            } catch (failure: CashRecordDetailFailure) { (if (failure.code == "record_not_found") "deleted" else "unavailable") to null }
              catch (_: Exception) { "unavailable" to null }
            return buildJsonObject { put("receipt", receipt); put("current_record_status", detail.first); put("current_record", detail.second ?: JsonNull) }
        } catch (failure: CashDraftSaveFailure) { if (boundary.commitState == "not_committed") failure.commitState = boundary.commitState; throw failure }
          catch (failure: MutationPlanFailure) { throw CashDraftSaveFailure(failure.code, commitState = boundary.commitState.takeIf { it == "not_committed" }) }
          catch (failure: CashPolicyError) { throw CashDraftSaveFailure(failure.code, failure.fields, policy = true, commitState = boundary.commitState.takeIf { it == "not_committed" }) }
          catch (failure: ReceiptConflict) { if (boundary.commitState == "not_committed") failure.commitState = boundary.commitState; throw failure }
          catch (failure: AccessFailure) { throw failure }
          catch (failure: CashImportProtocolError) { throw failure }
          catch (failure: Exception) { throw CashImportCommitFailure(boundary.commitState, boundary.diagnostic(), failure) }
    }
}
