package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CashImportViewModelTest {
    @Test
    fun fileScanMappingPreviewAndCommitRemainInSharedState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeCashImportRepository()
            val viewModel = CashImportViewModel(repository)
            viewModel.receiveFiles(listOf(TestFile("statement.csv", "csv-body")))
            advanceUntilIdle()

            assertEquals(listOf("statement.csv"), viewModel.state.value.selectedFiles.map { it.identity.name })
            viewModel.scanSelectedFiles()
            advanceUntilIdle()
            assertEquals(ImportStage.MAPPING, viewModel.state.value.stage)
            assertEquals("import-token", viewModel.state.value.importToken)
            assertEquals(true, viewModel.state.value.mappingComplete())

            viewModel.loadPreview()
            advanceUntilIdle()
            val relation = viewModel.state.value.preview!!.relations.single()
            viewModel.setRelationDraft(
                relation,
                ImportRelationDraft("payment_mirror", "accepted", relation.candidates.single()),
            )
            viewModel.commitImport()
            advanceUntilIdle()

            assertEquals(ImportStage.SUCCESS, viewModel.state.value.stage)
            assertEquals(1, viewModel.state.value.result?.newRows)
            assertEquals("import-token", repository.lastPreviewToken)
            assertEquals("accepted", (repository.lastRelations.single().fields["status"] as StructuredText).value)
            assertEquals("cash-record-2", (repository.lastRelations.single().fields["secondary_record_id"] as StructuredText).value)
            assertEquals("import-token", repository.lastCommitToken)
            assertEquals(null, viewModel.state.value.importToken)
            assertNull(viewModel.state.value.idempotencyKey)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun rejectsDuplicateFilesAndKeepsResumeTokenOnPasswordError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeCashImportRepository().apply {
                scanFailure = DomainFailure(
                    code = "import_password_required",
                    status = 400,
                    category = FailureCategory.RECOVERABLE,
                    importToken = "resume-token",
                )
            }
            val viewModel = CashImportViewModel(repository)
            viewModel.receiveFiles(listOf(TestFile("first.csv", "same"), TestFile("copy.csv", "same")))
            advanceUntilIdle()
            assertEquals(1, viewModel.state.value.selectedFiles.size, "duplicate file selection should keep one item")

            viewModel.scanSelectedFiles()
            advanceUntilIdle()

            assertEquals("import_password_required", viewModel.state.value.errorCode)
            assertEquals("resume-token", viewModel.state.value.importToken)
            assertEquals(1, repository.scanCalls, "scan action should make one repository request")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun transientCommitFailureKeepsRecoverableImportStateAndReusesIdempotencyKey() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeCashImportRepository().apply {
                commitFailure = DomainFailure(
                    code = "temporarily_unavailable",
                    status = 503,
                    category = FailureCategory.RECOVERABLE,
                    importToken = "import-token",
                )
            }
            val viewModel = CashImportViewModel(repository)
            viewModel.receiveFiles(listOf(TestFile("statement.csv", "csv-body")))
            advanceUntilIdle()
            viewModel.scanSelectedFiles()
            advanceUntilIdle()
            viewModel.loadPreview()
            advanceUntilIdle()

            viewModel.commitImport()
            advanceUntilIdle()
            val idempotencyKey: String = assertNotNull(viewModel.state.value.idempotencyKey)
            assertEquals("temporarily_unavailable", viewModel.state.value.errorCode)
            assertEquals("import-token", viewModel.state.value.importToken)
            assertEquals(ImportStage.PREVIEW, viewModel.state.value.stage)

            repository.commitFailure = null
            viewModel.commitImport()
            advanceUntilIdle()

            assertEquals(idempotencyKey, repository.commitIdempotencyKeys[0])
            assertEquals(idempotencyKey, repository.commitIdempotencyKeys[1])
            assertEquals(ImportStage.SUCCESS, viewModel.state.value.stage)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class TestFile(
    override val name: String,
    private val body: String,
) : FileSource {
    override val mediaType: String = "text/csv"
    override val size: Long = body.encodeToByteArray().size.toLong()
    override suspend fun read(): ByteArray = body.encodeToByteArray()
}

private class FakeCashImportRepository : CashImportRepository {
    var scanCalls = 0
    var scanFailure: Throwable? = null
    var commitFailure: Throwable? = null
    var lastPreviewToken: String? = null
    var lastCommitToken: String? = null
    var lastRelations: List<StructuredObject> = emptyList()
    val commitIdempotencyKeys = mutableListOf<String?>()

    override suspend fun detectCashImport(file: FileSource, currency: String?, password: String?): ImportDetection = error("Not used")

    override suspend fun scanCashImport(
        files: List<FileSource>,
        currency: String?,
        passwords: Map<String, String>,
        importToken: String?,
    ): ImportScan {
        scanCalls++
        scanFailure?.let { throw it }
        val file = files.single()
        return ImportScan(
            importToken = "import-token",
            contract = "cash-import-v1",
            channel = "bank",
            channelLabel = "银行账单",
            ready = true,
            files = listOf(ImportFileScan(0, file.name, file.name, "digest", file.size ?: 0, status = "ready")),
            file = ImportFileDescriptor(file.name, "digest"),
            digest = "digest",
            accounts = listOf(Account(1, "现金账户", "cash", currencies = listOf("CNY"))),
            groups = listOf(
                ImportSourceGroup(
                    groupId = "group-1",
                    displayName = "现金账户",
                    currencies = listOf("CNY"),
                    rowCount = 1,
                    suggestion = ImportMappingSuggestion(accountId = 1),
                ),
            ),
        )
    }

    override suspend fun previewCashImport(
        importToken: String,
        source: String,
        currency: String?,
        passwords: Map<String, String>,
        mapping: List<ImportMappingDecision>?,
        batch: Boolean,
    ): ImportPreview {
        lastPreviewToken = importToken
        return ImportPreview(
            importToken = importToken,
            channel = "bank",
            channelLabel = "银行账单",
            file = ImportFileDescriptor("statement.csv", "digest"),
            items = listOf(
                ImportPreviewItem(
                    recordId = "cash-record-1",
                    occurredAt = "2026-09-26T10:00:00Z",
                    amount = "-10.00",
                    currency = "CNY",
                    accountName = "现金账户",
                    recordType = "consumption",
                    recordSubtype = "not_applicable",
                    status = "new",
                ),
            ),
            summary = ImportPreviewSummary(total = 1, new = 1, existing = 0, unsupported = 0),
            relations = listOf(
                ImportRelation(
                    id = "proposal-1",
                    kind = "payment_mirror",
                    label = "同笔支付",
                    subtype = "",
                    status = "pending",
                    automatic = false,
                    ruleId = "rule-1",
                    reason = "matched",
                    primary = relationRecord("cash-record-1"),
                    candidates = listOf(relationRecord("cash-record-2")),
                ),
            ),
        )
    }

    override suspend fun commitCashImport(
        importToken: String,
        source: String,
        currency: String?,
        passwords: Map<String, String>,
        previewDigest: String?,
        previewRelationDigest: String?,
        previewChannel: String?,
        relations: List<StructuredObject>?,
        mapping: List<ImportMappingDecision>?,
        idempotencyKey: String?,
        batch: Boolean,
    ): ImportCommitResult {
        commitIdempotencyKeys += idempotencyKey
        commitFailure?.let { throw it }
        lastCommitToken = importToken
        lastRelations = relations.orEmpty()
        return ImportCommitResult("Imported", 1, 0, channel = "bank", digest = "digest")
    }

    private fun relationRecord(id: String) = ImportRelationRecord(
        recordId = id,
        occurredAt = "2026-09-26T10:00:00Z",
        amount = "-10.00",
        currency = "CNY",
        accountName = "现金账户",
        recordType = "consumption",
        recordSubtype = "not_applicable",
        status = "new",
        preview = true,
    )
}
