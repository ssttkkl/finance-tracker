package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashImportAccountInput
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashMutationResultDto
import com.finance.tracker.business.contracts.CashMutationResultPageDto
import com.finance.tracker.business.contracts.CashWriteSetResultDto
import com.finance.tracker.business.contracts.CashWriteSetTransactionResultDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.StoredCashImportSubmission
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.business.domain.cashWriteSetJson
import com.finance.tracker.business.domain.page
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CashImportRecoveryEntryParityTest {
    @Test
    fun aConfirmedSizeRefusalCanRetireOnlyAfterASecondAuthoritativeReceiptRead() = runBlocking {
        val receipt = CashMutationReceiptDto("synthetic-receipt", operationKind = "cash_write_set", committed = true,
            createdAt = "2026-11-26T01:00:00Z", ledgerSeq = "2", writeSetResult = CashWriteSetResultDto(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            ))
        for (duringLookup in listOf("missing", "unavailable", "committed")) {
            val attempt = StoredCashImportSubmission("historical-frozen-batch", "{}")
            val events = mutableListOf<String>()
            val host = object : ClientImportSubmissionHost<CashMutationReceiptDto> {
                override suspend fun current() = ClientImportSubmissionState.Pending(attempt)
                override suspend fun receipt(key: String): CashMutationReceiptDto? {
                    assertEquals(attempt.key, key)
                    events += "lookup"
                    return if (events.size == 1) null else when (duringLookup) {
                        "unavailable" -> throw ClientNetworkUnavailable()
                        "committed" -> receipt
                        else -> null
                    }
                }
                override suspend fun submit(attempt: StoredCashImportSubmission): Nothing {
                    events += "submit"
                    throw ClientOperationFailure("payload_too_large", 413, commitState = "not_committed")
                }
                override suspend fun recordBaseRejection(attempt: StoredCashImportSubmission, reason: ClientOperationFailure) = error("Unused")
                override suspend fun rebase(attempt: StoredCashImportSubmission, reason: ClientOperationFailure) = error("Unused")
                override suspend fun discardRejectedAttempt(attempt: StoredCashImportSubmission, reason: ClientOperationFailure) {
                    events += "retire"
                }
                override suspend fun complete(attempt: StoredCashImportSubmission, receipt: CashMutationReceiptDto): CashMutationReceiptDto {
                    events += "complete"
                    return receipt
                }
            }
            if (duringLookup == "committed") {
                assertEquals(receipt, resumeCashImportSubmission(host))
                assertEquals(listOf("lookup", "submit", "lookup", "complete"), events)
            } else {
                val failure = assertFailsWith<ClientOperationFailure> { resumeCashImportSubmission(host) }
                if (duringLookup == "unavailable") {
                    assertEquals("unknown", failure.commitState)
                    assertEquals(listOf("lookup", "submit", "lookup"), events)
                } else {
                    assertEquals("not_committed", failure.commitState)
                    assertEquals(listOf("lookup", "submit", "lookup", "retire"), events)
                }
            }
        }
    }

    @Test
    fun directKotlinCallerResumesTheOriginalCashImportAfterResponseLoss() = runBlocking {
        val snapshot = Json.decodeFromString<LedgerSnapshotDto>(resource("cash-operation-snapshot.json").decodeToString())
        val workspace = WorkspaceDto(snapshot.workspaceId, "Synthetic", Role.EDITOR)
        val session = ClientStoredSession("test-token", SessionDto(UserDto("synthetic@example.test"), workspace.id, listOf(workspace)))
        val store = MemoryStore(session)
        val lines = resource("alipay-01.csv").toString(Charset.forName("GB18030")).split(Regex("\\r?\\n"))
        val bytes = (lines.take(7) + lines[9] + "").joinToString("\r\n").encodeToByteArray()

        var operationBody: String? = null
        var committed = false
        var posts = 0
        var ids = 0
        val receipt = CashMutationReceiptDto("synthetic-receipt", operationKind = "cash_write_set", committed = true,
            createdAt = "2026-11-26T01:00:00Z", status = "completed", summary = mapOf("created_posted" to 1L),
            ledgerSeq = "9007199254740996", writeSetResult = CashWriteSetResultDto(
                listOf(CashWriteSetTransactionResultDto("9007199254740992", "1", listOf("7100"))),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            ))
        val item = CashMutationResultDto("1", "row", "row:0", "created", transactionId = "9007199254740992")
        val transport = ClientHttp { call ->
            assertEquals(session.token, call.token)
            when (call.path.substringBefore('?')) {
                "/api/v1/auth/session" -> ClientHttpResponse(200, Json.encodeToString(session.session))
                "/api/v1/ledger/version" -> ClientHttpResponse(200, """{"ledger_seq":"${snapshot.ledgerSeq}"}""")
                "/api/v1/ledger/snapshot" -> ClientHttpResponse(200, Json.encodeToString(snapshot))
                "/api/v1/cash-mutations/receipts/by-key" -> {
                    assertTrue(call.path.endsWith("key=batch-1"))
                    if (committed) ClientHttpResponse(200, cashWriteSetJson.encodeToString(receipt))
                    else ClientHttpResponse(404, """{"error":{"code":"receipt_not_found"}}""")
                }
                "/api/v1/cash-import/write-set" -> {
                    posts++
                    assertEquals("batch-1", call.headers["Idempotency-Key"])
                    assertTrue(store.checkpoints > 0)
                    operationBody = (call.body as ClientHttpBody.Json).text
                    assertEquals(operationBody, store.batch?.submission?.request)
                    committed = true
                    throw ClientNetworkUnavailable()
                }
                "/api/v1/cash-mutations/receipts/synthetic-receipt/rows" ->
                    ClientHttpResponse(200, Json.encodeToString(CashMutationResultPageDto(listOf(item), null, emptyList(), 9007199254740996L)))
                "/api/v1/cash-mutations/receipts/synthetic-receipt/relations" ->
                    ClientHttpResponse(200, Json.encodeToString(CashMutationResultPageDto(emptyList(), null, emptyList(), 9007199254740996L)))
                else -> error("Unexpected API call")
            }
        }

        fun operations(): ClientCashImportOperations {
            val client = ClientSessionOperations(transport, store, { it }, { "2026-11-26T01:00:00Z" })
            val calendar = CashLedgerCalendar({ it == "UTC" }, { _, _ -> 0 }, { _, _ -> error("No dates requested") })
            return ClientCashImportOperations(client, store, calendar, { "batch-${++ids}" })
        }

        val start = operations()
        val prepared = start.prepare(workspace.id, listOf(CashImportFileSource("fixture-file", "synthetic.csv") {
            CashImportFileInput("fixture-file", "synthetic.csv", null, bytes)
        }))
        val mappings = prepared.context.sourceAccounts.map { CashImportAccountInput(it.sourceAccountRef, "10", 1) }
        assertEquals(1, mappings.size)
        val review = start.mapAccounts(workspace.id, prepared.batchId, prepared.revision, mappings, "UTC")
        assertTrue(review.plan.canCommit)
        val failure = assertFailsWith<ClientOperationFailure> {
            start.confirm(workspace.id, prepared.batchId, review.plan.sessionRevision)
        }
        assertEquals("result_unknown", failure.code)
        assertEquals("unknown", failure.commitState)
        assertEquals(cashWriteSetJson.encodeToString(review.calculation.request), operationBody)

        val reopened = operations()
        val recovered = assertIs<ClientCashImportResume.Committed>(reopened.resume(workspace.id, prepared.batchId))
        assertEquals(receipt, recovered.result.authoritativeReceipt())
        assertEquals(listOf(item), recovered.result.page("row", 0, 50).items)
        assertEquals(1, posts)
        assertEquals(receipt, reopened.result(workspace.id, prepared.batchId).authoritativeReceipt())
        assertEquals(emptyMap(), store.files)
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }

    private class MemoryStore(private val binding: ClientStoredSession) : ClientSessionStore, ClientCashImportStore {
        var checkpoints = 0
        var batch: ClientCashImportBatch? = null
        val files = mutableMapOf<String, String>()
        var state = ClientLedgerState()

        override suspend fun session(): ClientStoredSession = binding
        override suspend fun authenticate(previous: ClientStoredSession?, session: ClientStoredSession): Boolean = error("Unused")
        override suspend fun verify(previous: ClientStoredSession, session: ClientStoredSession): Boolean =
            previous.token == binding.token && session.session == binding.session

        override suspend fun ledgerState(session: ClientStoredSession, workspaceId: String): ClientLedgerState = state
        override suspend fun saveLedger(session: ClientStoredSession, expectedGeneration: String?, ledger: ClientStoredLedger): Boolean {
            if (state.generation != expectedGeneration) return false
            state = ClientLedgerState(ledger.snapshot.ledgerSeq, ledger)
            return true
        }

        override suspend fun forgetWorkspace(session: ClientStoredSession, workspaceId: String): Unit = error("Unused")
        override suspend fun clear(session: ClientStoredSession): Unit = error("Unused")

        override suspend fun batches(session: ClientStoredSession, workspaceId: String): List<ClientCashImportListing> =
            listOfNotNull(batch?.listing())

        override suspend fun read(session: ClientStoredSession, workspaceId: String, batchId: String): ClientCashImportBatch? =
            batch?.takeIf { it.id == batchId }

        override suspend fun compareAndSave(
            session: ClientStoredSession, workspaceId: String, previous: ClientCashImportBatch?, batch: ClientCashImportBatch,
            file: ClientCapturedCashImportFile?, removedFileIds: List<String>,
        ): Boolean {
            if (this.batch != previous) return false
            file?.let { files[it.storageId] = it.payload }
            removedFileIds.forEach(files::remove)
            this.batch = batch
            return true
        }

        override suspend fun readFile(session: ClientStoredSession, workspaceId: String, batchId: String, storageId: String): String? =
            files[storageId]

        override suspend fun compareAndDelete(session: ClientStoredSession, workspaceId: String, previous: ClientCashImportBatch): Boolean =
            error("Unused")

        override suspend fun checkpoint() { checkpoints++ }
    }
}
