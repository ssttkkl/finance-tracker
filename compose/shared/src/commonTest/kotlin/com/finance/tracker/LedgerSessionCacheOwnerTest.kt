package com.finance.tracker

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.LedgerVersionDto
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.business.domain.scanLocalCashFunding
import com.finance.tracker.business.domain.CashFundingSuggestion
import com.finance.tracker.business.contracts.LedgerStatementMappingFactDto
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.FailureCategory
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.LedgerSnapshotSource
import com.finance.tracker.domain.BoundedFileSource
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import kotlin.test.*

class LedgerSessionCacheOwnerTest {
    private val origin = "https://owner.example.test"
    private val session = SessionDto(UserDto("synthetic@example.test"), "workspace",
        listOf(WorkspaceDto("workspace", "Synthetic", Role.EDITOR)))
    private val scope = LedgerCacheScope(origin, ledgerOfflinePrincipal(session.user.email), "workspace")

    @Test fun importFundingReturnsGroupsAndExistingMappingSuggestionsFromOneCachedSnapshot() = runTest {
        val f = fixture(); val plan = LocalCashImportRebaseFixture()
        val groups = scanLocalCashFunding(plan.files.map { it.parsedFile() }).groups
        val group = groups.single(); val time = "2026-10-01T00:00:00Z"
        f.storage.facts = plan.source.copy(facts = plan.source.facts.copy(statementAccountMappings = listOf(
            LedgerStatementMappingFactDto("1", group.sourceType, "funding_group_v1", group.sourceAccountRef, "10", "user", "1", time, time))))
        try {
            f.owner.saveImportFiles("batch", plan.files)
            val before = f.storage.facts
            val (actual, suggestions) = f.owner.importFunding("batch")
            assertEquals(groups, actual)
            assertEquals(mapOf(group.sourceAccountRef to CashFundingSuggestion("10", 1, "confirmed_mapping")), suggestions)
            assertEquals(before, f.storage.facts)
            assertEquals(0, f.source.reads)
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun importFundingCannotPublishAcrossChangedCredentialsOrMissingCacheIdentity() = runTest {
        for (changed in listOf(false, true)) {
            val f = fixture(); val plan = LocalCashImportRebaseFixture(); f.storage.facts = plan.source
            try {
                f.owner.saveImportFiles("batch", plan.files)
                f.storage.afterImportRead = { if (changed) f.api.writeToken("later-token") else f.storage.saved = null }
                if (changed) assertFailsWith<CancellationException> { f.owner.importFunding("batch") }
                else assertFailsWith<IllegalStateException> { f.owner.importFunding("batch") }
                assertEquals(0, f.source.reads)
                assertEquals(plan.source, f.storage.facts)
            } finally { f.owner.close(); f.api.close() }
        }
    }

    @Test fun capturedCsvBatchReadsEachFileOnceAndFeedsTheCompleteProductionPreviewOffline() = runTest {
        val f = fixture(); f.storage.facts = LocalCashImportRebaseFixture().source
        var reads = 0
        try {
            val captured = f.owner.captureImportFiles("batch", listOf(
                localCashImportStatementInput("a") { reads++; localCashImportCsvBytes() }, localCashImportStatementInput("b") { reads++; localCashImportCsvBytes() }))
            assertEquals(2, reads)
            assertEquals(listOf("a", "b"), captured.map { it.fileRef })
            assertEquals(captured.map { it.encode() }, f.owner.importFiles("batch")!!.map { it.encode() })
            assertEquals(listOf("alipay", "alipay"), captured.map { it.parsedFile().document!!.sourceType })
            val choices = buildJsonObject {
                put("account_selections", buildJsonArray {
                    for (group in scanLocalCashFunding(captured.map { it.parsedFile() }).groups) add(buildJsonObject {
                        put("source_account_ref", group.sourceAccountRef); put("account_id", "10"); put("expected_account_revision", 1)
                    })
                })
            }
            val calculated = f.owner.calculateImport("batch", choices)
            assertEquals(JsonPrimitive(true), calculated.plan["can_commit"])
            assertEquals(1, calculated.request.changes.transactions.size)
            assertEquals(0, f.source.reads)
            assertEquals(2, reads)
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun captureFailureOrCancellationNeverSavesAPartialBatchOrReplacesExistingInputs() = runTest {
        for (failure in listOf(ApiFailure("statement_unreadable", 422), IllegalStateException("synthetic program fault"),
            CancellationException("synthetic cancellation"))) {
            val f = fixture(); val original = listOf(localCashImportFileFixture())
            try {
                f.owner.saveImportFiles("batch", original)
                val actual = assertFailsWith<Exception> { f.owner.captureImportFiles("batch", listOf(
                    localCashImportStatementInput("a") { localCashImportCsvBytes() }, localCashImportStatementInput("b") { throw failure })) }
                assertTrue(generateSequence(actual as Throwable?) { it.cause }.any { it === failure })
                assertEquals(original.map { it.encode() }, f.storage.batches.getValue("batch").map { it.encode() })
                assertEquals(listOf("batch"), f.storage.batches.keys.toList())
            } finally { f.owner.close(); f.api.close() }
        }
    }

    @Test fun captureFreezesTheInputOrderBeforeTheFirstIdentityAwait() = runTest {
        val f = fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val inputs = mutableListOf(localCashImportStatementInput("a") { localCashImportCsvBytes() }, localCashImportStatementInput("b") { localCashImportCsvBytes() })
        f.storage.afterReadSession = { entered.complete(Unit); release.await() }
        try {
            val capture = async { f.owner.captureImportFiles("batch", inputs) }
            entered.await(); inputs.clear(); release.complete(Unit)
            assertEquals(listOf("a", "b"), capture.await().map { it.fileRef })
        } finally { release.complete(Unit); f.owner.close(); f.api.close() }
    }

    @Test fun captureCannotSaveAfterLoginChangeIdentityLossOrCloseDuringTheRead() = runTest {
        for (mode in listOf("login", "identity", "close")) {
            val f = fixture()
            try {
                val input = localCashImportStatementInput("a") {
                    when (mode) {
                        "login" -> f.api.writeToken("later-token")
                        "identity" -> f.storage.saved = null
                        else -> f.owner.close()
                    }
                    localCashImportCsvBytes()
                }
                if (mode == "identity") assertFailsWith<IllegalStateException> { f.owner.captureImportFiles("batch", listOf(input)) }
                else assertFailsWith<CancellationException> { f.owner.captureImportFiles("batch", listOf(input)) }
                assertTrue(f.storage.batches.isEmpty())
            } finally { f.owner.close(); f.api.close() }
        }
    }

    @Test fun captureRejectsEmptyOversizedAndDuplicateInputListsBeforeReadingAnyFile() = runTest {
        val f = fixture(); var reads = 0
        fun input(ref: String) = localCashImportStatementInput(ref) { reads++; localCashImportCsvBytes() }
        try {
            for (inputs in listOf(emptyList(), List(21) { input("file$it") }, listOf(input("a"), input("a")))) {
                assertFailsWith<ApiFailure> { f.owner.captureImportFiles("batch", inputs) }
            }
            assertEquals(0, reads)
            assertTrue(f.storage.batches.isEmpty())
        } finally { f.owner.close(); f.api.close() }
    }


    @Test fun productionImportCalculationReadsOnlySavedFilesAndCachedFactsWithoutSavingAConfirmation() = runTest {
        val f = fixture(); val plan = LocalCashImportRebaseFixture()
        f.storage.facts = plan.source
        try {
            f.owner.saveImportFiles("batch", plan.files)
            val before = f.storage.facts
            val result = f.owner.calculateImport("batch", plan.input)
            assertEquals(plan.request, result.request)
            assertEquals(plan.review.conclusion(), result.review.conclusion())
            assertEquals(JsonPrimitive(true), result.plan["can_commit"])
            assertEquals(before, f.storage.facts)
            assertEquals(0, f.source.reads)
            assertEquals(listOf("batch"), f.storage.batches.keys.toList())
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun productionImportCalculationFreezesChoicesBeforeAwaitingTheCachedIdentity() = runTest {
        val f = fixture(); val plan = LocalCashImportRebaseFixture(); f.storage.facts = plan.source
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            f.owner.saveImportFiles("batch", plan.files)
            val values = plan.input.toMutableMap(); val choices = JsonObject(values)
            f.storage.afterReadSession = { entered.complete(Unit); release.await() }
            val calculating = async { f.owner.calculateImport("batch", choices) }
            entered.await()
            values["row_choices"] = Json.parseToJsonElement("""[{"row_ref":"stable:r1","action":"defer"}]""")
            release.complete(Unit)
            val result = calculating.await()
            assertEquals(plan.input, result.review.inputJson())
            assertEquals(JsonPrimitive(1), result.plan.getValue("summary").jsonObject["create_posted"])
            assertEquals(plan.request, result.request)
        } finally { release.complete(Unit); f.owner.close(); f.api.close() }
    }

    @Test fun productionImportCalculationCannotPublishAcrossChangedCredentialsOrLostIdentity() = runTest {
        for (credentialsChanged in listOf(false, true)) {
            val f = fixture(); val plan = LocalCashImportRebaseFixture(); f.storage.facts = plan.source
            try {
                f.owner.saveImportFiles("batch", plan.files)
                f.storage.afterImportRead = {
                    if (credentialsChanged) f.api.writeToken("later-token") else f.storage.saved = null
                }
                if (credentialsChanged) assertFailsWith<CancellationException> { f.owner.calculateImport("batch", plan.input) }
                else assertFailsWith<IllegalStateException> { f.owner.calculateImport("batch", plan.input) }
                assertEquals(plan.source, f.storage.facts)
                assertEquals(0, f.source.reads)
                assertEquals(listOf("batch"), f.storage.batches.keys.toList())
            } finally { f.owner.close(); f.api.close() }
        }
    }

    @Test fun productionImportCalculationRequiresARealCachedSnapshotAndAnOpenOwner() = runTest {
        val f = fixture(); val plan = LocalCashImportRebaseFixture()
        try {
            f.owner.saveImportFiles("batch", plan.files)
            f.storage.facts = null
            assertFailsWith<IllegalStateException> { f.owner.calculateImport("batch", plan.input) }
            f.owner.close()
            val accesses = f.storage.importAccesses
            assertFailsWith<CancellationException> { f.owner.calculateImport("batch", plan.input) }
            assertEquals(accesses, f.storage.importAccesses)
            assertEquals(0, f.source.reads)
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun offlineRecoveryOnlyOpensExistingMaterialsAndCanBrowseWithoutHttp() = runTest {
        val f = fixture()
        try {
            assertEquals(session, f.owner.readOfflineSession())
            assertEquals(listOf(true), f.openModes)
            assertEquals(0, f.source.reads)
            assertEquals(17, assertNotNull(f.owner.options()).recordTypes.size)
            assertEquals(0L, assertNotNull(f.owner.categories()).revision)
            assertEquals(listOf("Cash"), assertNotNull(f.owner.accounts()).map { it.name })
            assertEquals(f.snapshot.ledgerSeq, f.owner.page(CashFiltersDto())?.projectionVersion?.toString())
            assertEquals(0, f.source.reads)
            f.owner.close()
            assertEquals(1, f.storage.closes)
            assertEquals(f.snapshot, f.storage.facts)
            assertEquals(scope, f.directory.current(f.ticket))
            assertFailsWith<CancellationException> { f.owner.readOfflineSession() }
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun onlineRefreshCommitsFactsThenPublishesTheOriginalBoundSession() = runTest {
        val f = fixture(published = false, hasFacts = false)
        try {
            f.owner.refresh(session)
            assertEquals(listOf(false), f.openModes)
            assertEquals(2, f.source.reads)
            assertEquals(f.snapshot, f.storage.facts)
            assertEquals(session, f.storage.saved)
            assertEquals("original-token", f.storage.savedToken)
            assertEquals(scope, f.directory.current(f.ticket))
            assertNotNull(f.owner.page())
        } finally { f.owner.close(); f.api.close() }
    }


    @Test fun confirmedWriteRefreshReplacesCompleteFactsAndPreservesIdentity() = runTest {
        val f = fixture()
        try {
            assertNotNull(f.owner.readOfflineSession())
            val originalSession = f.storage.saved
            val next = (f.snapshot.ledgerSeq.toLong() + 1).toString()
            f.source.snapshot = f.snapshot.copy(ledgerSeq = next)
            f.owner.refreshFacts(next)
            assertEquals(next, f.owner.page()?.projectionVersion?.toString())
            assertEquals(f.source.snapshot, f.storage.facts)
            assertEquals(originalSession, f.storage.saved)
            assertEquals("original-token", f.storage.savedToken)
            assertEquals(2, f.source.reads)
            f.owner.refreshFacts(next)
            assertEquals(3, f.source.reads) // unchanged version does not download a snapshot
            assertEquals(scope, f.directory.current(f.ticket))
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun confirmedWriteRefreshRejectsFactsBelowTheReceiptVersion() = runTest {
        val f = fixture()
        try {
            assertNotNull(f.owner.readOfflineSession())
            assertFailsWith<IllegalStateException> { f.owner.refreshFacts((f.snapshot.ledgerSeq.toLong() + 1).toString()) }
            assertEquals(f.snapshot, f.storage.facts)
            assertEquals(session, f.storage.saved)
        } finally { f.owner.close(); f.api.close() }
    }
    @Test fun missingRecoveryIdentityDoesNotFabricateAnAuthenticatedSession() = runTest {
        val f = fixture()
        f.storage.saved = null
        try {
            assertNull(f.owner.readOfflineSession())
            assertNull(f.owner.page())
            assertEquals(0, f.source.reads)
            assertEquals(0, f.storage.closes)
        } finally { f.owner.close(); f.api.close() }
        assertEquals(1, f.storage.closes)
    }

    @Test fun aBoundIdentityLostAfterRecoveryCannotLeaveBrowsableFacts() = runTest {
        val f = fixture()
        try {
            assertEquals(session, f.owner.readOfflineSession())
            assertNotNull(f.owner.page())
            f.storage.saved = null
            assertNull(f.owner.page())
            assertNull(f.owner.categories()); assertNull(f.owner.options()); assertNull(f.owner.accounts())
            assertEquals(f.snapshot, f.storage.facts)
            assertEquals(0, f.storage.clears)
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun staleCredentialsAndDirectoryTicketsCannotOpenOrReadACache() = runTest {
        for (changeToken in listOf(true, false)) {
            val f = fixture()
            try {
                if (changeToken) f.api.writeToken("later-token") else f.directory.begin(origin)
                assertFailsWith<CancellationException> { f.owner.readOfflineSession() }
                assertTrue(f.openModes.isEmpty())
            } finally { f.owner.close(); f.api.close() }
        }
    }

    @Test fun currentReferenceMustStillIdentifyThisScopeForOfflineRecoveryAndPages() = runTest {
        val f = fixture()
        try {
            assertEquals(session, f.owner.readOfflineSession())
            val other = scope.copy(workspaceId = "other")
            f.directory.register(f.ticket, other); f.directory.publish(f.ticket, other)
            assertFailsWith<CancellationException> { f.owner.page() }
            assertFailsWith<CancellationException> { f.owner.readOfflineSession() }
            assertEquals(0, f.source.reads)
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun tokenChangesDuringReadCannotReturnTheOldIdentityOrPage() = runTest {
        val f = fixture()
        try {
            f.storage.afterReadSession = { f.api.writeToken("later-token") }
            assertFailsWith<CancellationException> { f.owner.readOfflineSession() }
            assertFailsWith<CancellationException> { f.owner.page() }
            assertEquals(1, f.openModes.size)
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun cancellationDuringOpenKeepsTheAcquiredStorageOwnedUntilExplicitClose() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeOpenReturn = { entered.complete(Unit); release.await() }
        try {
            val read = launch { f.owner.readOfflineSession() }
            entered.await(); read.cancel(); yield()
            assertFalse(read.isCompleted)
            assertEquals(0, f.storage.closes)
            release.complete(Unit); read.join()
            assertTrue(read.isCancelled)
            f.owner.close()
            assertEquals(1, f.storage.closes)
        } finally { release.complete(Unit); f.owner.close(); f.api.close() }
    }

    @Test fun closeFencesAPendingOpenAndWaitsForTheActualStorageClose() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val closing = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        f.beforeOpenReturn = { entered.complete(Unit); release.await() }
        f.storage.beforeClose = { closing.complete(Unit); finish.await() }
        try {
            val read = async { assertFailsWith<CancellationException> { f.owner.readOfflineSession() } }
            entered.await()
            val close = launch { f.owner.close() }
            yield(); assertFalse(close.isCompleted)
            release.complete(Unit); read.await(); closing.await()
            assertFalse(close.isCompleted)
            assertFailsWith<CancellationException> { f.owner.page() }
            finish.complete(Unit); close.join()
            assertEquals(1, f.storage.closes)
        } finally { release.complete(Unit); finish.complete(Unit); f.owner.close(); f.api.close() }
    }

    @Test fun failedCloseRetainsTheSameOwnerAndRetriesOnlyItsOriginalAction() = runTest {
        val f = fixture()
        val failure = IllegalStateException("synthetic close failure")
        try {
            f.owner.readOfflineSession()
            f.storage.beforeClose = { throw failure }
            val actual = assertFailsWith<IllegalStateException> { f.owner.close() }
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
            assertFailsWith<CancellationException> { f.owner.page() }
            f.storage.beforeClose = null
            f.owner.close(); f.owner.close()
            assertEquals(2, f.storage.closes)
            assertEquals(0, f.storage.clears)
            assertEquals(1, f.openModes.size)
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun closeDoesNotWaitForNetworkAndLateRejectionCannotErasePreservedFacts() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.source.beforeRead = { entered.complete(Unit); release.await(); throw DomainFailure("forbidden", 403, FailureCategory.RECOVERABLE) }
        try {
            val refresh = async { assertFailsWith<CancellationException> { f.owner.refresh(session) } }
            entered.await(); f.owner.close()
            assertEquals(1, f.storage.closes)
            assertEquals(0, f.storage.clears)
            release.complete(Unit); refresh.await()
            assertEquals(f.snapshot, f.storage.facts)
            assertEquals(0, f.storage.clears)
        } finally { release.complete(Unit); f.owner.close(); f.api.close() }
    }

    @Test fun revocationCleanupFailureIsRetainedAndCloseFinishesItsChosenErasure() = runTest {
        val f = fixture()
        val cleanup = IllegalStateException("synthetic erasure failure")
        f.source.beforeRead = { throw DomainFailure("forbidden", 403, FailureCategory.RECOVERABLE) }
        f.storage.beforeClear = { throw cleanup }
        try {
            val actual = assertFailsWith<IllegalStateException> { f.owner.refresh(session) }
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cleanup })
            f.storage.beforeClear = null
            f.owner.close()
            assertEquals(2, f.storage.clears)
            assertEquals(0, f.storage.closes)
            assertNull(f.storage.facts)
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun aNewLoginDuringRefreshCannotPublishTheOldIdentity() = runTest {
        val f = fixture(published = false, hasFacts = false)
        f.source.beforeRead = { f.api.writeToken("later-token") }
        try {
            assertFailsWith<CancellationException> { f.owner.refresh(session) }
            assertNull(f.storage.saved)
            assertNull(f.directory.current(f.ticket))
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun importFilesUseTheOriginalBoundCacheWithoutNetworkAndStopAfterClose() = runTest {
        val f = fixture()
        val files = listOf(localCashImportFileFixture())
        try {
            f.owner.saveImportFiles("batch", files)
            assertEquals(listOf(true), f.openModes)
            assertEquals(listOf("batch"), f.owner.importFileBatches())
            assertEquals(files.single().encode(), assertNotNull(f.owner.importFiles("batch")).single().encode())
            f.owner.removeImportFiles("batch")
            assertNull(f.owner.importFiles("batch"))
            assertEquals(0, f.source.reads)
            f.owner.close()
            val before = f.storage.importAccesses
            assertFailsWith<CancellationException> { f.owner.saveImportFiles("batch", files) }
            assertFailsWith<CancellationException> { f.owner.importFileBatches() }
            assertFailsWith<CancellationException> { f.owner.importFiles("batch") }
            assertFailsWith<CancellationException> { f.owner.removeImportFiles("batch") }
            assertEquals(before, f.storage.importAccesses)
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun importAccessRequiresCachedIdentityAndCannotReturnAcrossALaterLogin() = runTest {
        val missing = fixture(hasFacts = false)
        try {
            assertFailsWith<IllegalStateException> { missing.owner.importFileBatches() }
            assertEquals(0, missing.storage.importAccesses)
        } finally { missing.owner.close(); missing.api.close() }
        val f = fixture()
        try {
            f.owner.saveImportFiles("batch", listOf(localCashImportFileFixture()))
            f.storage.afterImportRead = { f.api.writeToken("later-token") }
            assertFailsWith<CancellationException> { f.owner.importFiles("batch") }
            val before = f.storage.importAccesses
            assertFailsWith<CancellationException> { f.owner.removeImportFiles("batch") }
            assertEquals(before, f.storage.importAccesses)
            assertEquals(listOf("batch"), f.storage.batches.keys.toList())
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun importSaveFreezesTheBatchBeforeAwaitingItsIdentityRead() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val files = mutableListOf(localCashImportFileFixture("a"), localCashImportFileFixture("b"))
        val expected = files.map { it.encode() }
        f.storage.afterReadSession = { entered.complete(Unit); release.await() }
        try {
            val saving = async { f.owner.saveImportFiles("batch", files) }
            entered.await(); files.reverse(); release.complete(Unit)
            saving.await()
            assertEquals(expected, f.storage.batches.getValue("batch").map { it.encode() })
        } finally { release.complete(Unit); f.owner.close(); f.api.close() }
    }

    @Test fun cancelledOpenCannotStartImportMutationsOrLoseStorageOwnership() = runTest {
        for (remove in listOf(false, true)) {
            val f = fixture()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val files = listOf(localCashImportFileFixture())
            f.storage.batches["original"] = files
            f.beforeOpenReturn = { entered.complete(Unit); release.await() }
            try {
                val operation = launch {
                    if (remove) f.owner.removeImportFiles("original") else f.owner.saveImportFiles("new", files)
                }
                entered.await(); operation.cancel(); release.complete(Unit); operation.join()
                assertTrue(operation.isCancelled)
                assertEquals(0, f.storage.importAccesses)
                assertEquals(listOf("original"), f.storage.batches.keys.toList())
                assertEquals(0, f.storage.closes)
                f.owner.close()
                assertEquals(1, f.storage.closes)
            } finally { release.complete(Unit); f.owner.close(); f.api.close() }
        }
    }

    private suspend fun fixture(published: Boolean = true, hasFacts: Boolean = true): Fixture {
        val f = Fixture()
        f.ticket = f.directory.begin(origin)
        f.directory.register(f.ticket, scope)
        if (published) f.directory.publish(f.ticket, scope)
        f.storage.facts = f.snapshot.takeIf { hasFacts }
        f.storage.saved = session.takeIf { published }
        f.owner = LedgerSessionCacheOwner(f.api, f.directory, f.ticket, scope,
            ApiCredentialSnapshot("original-token", origin), f.source) { existingOnly, registration ->
            f.openModes += existingOnly
            registration.beforeOpen()
            f.beforeOpenReturn?.invoke()
            f.storage
        }
        return f
    }

    private inner class Fixture {
        val snapshot = localCashLedgerOwnerSnapshot()
        val storage = Storage()
        val source = Source(snapshot)
        val directory = LedgerSessionDirectory(object : LedgerSessionLocatorStorage {
            var value: String? = null
            override suspend fun read(key: String) = value
            override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
                if (value != expected) return false
                value = replacement
                return true
            }
        })
        val api = FinanceApiClient({ origin }, object : TokenStore {
            var value = "original-token"
            override suspend fun get() = value
            override suspend fun set(value: String) { this.value = value }
            override suspend fun clear() = error("No implicit logout")
        }, MockEngine { error("No direct HTTP from this protocol fixture") })
        val openModes = mutableListOf<Boolean>()
        var beforeOpenReturn: (suspend () -> Unit)? = null
        lateinit var owner: LedgerSessionCacheOwner
        lateinit var ticket: LedgerSessionTicket
    }

    private class Source(var snapshot: LedgerSnapshotDto) : LedgerSnapshotSource {
        var reads = 0
        var beforeRead: (suspend () -> Unit)? = null
        override suspend fun version(workspaceId: String): LedgerVersionDto { reads++; beforeRead?.invoke(); return LedgerVersionDto(snapshot.ledgerSeq) }
        override suspend fun snapshot(workspaceId: String): LedgerSnapshotDto { reads++; beforeRead?.invoke(); return snapshot }
    }

    private class Storage : LedgerStorage {
        val batches = linkedMapOf<String, List<LocalCashImportFile>>()
        var importAccesses = 0
        var afterImportRead: (suspend () -> Unit)? = null
        override val imports = object : LedgerImportBatches {
            override suspend fun list(): List<String> { importAccesses++; return batches.keys.toList() }
            override suspend fun read(batchId: String): List<LocalCashImportFile>? {
                importAccesses++
                val result = batches[batchId]
                afterImportRead?.invoke()
                return result
            }
            override suspend fun save(batchId: String, files: List<LocalCashImportFile>) { importAccesses++; batches[batchId] = files }
            override suspend fun append(batchId: String, files: List<LocalCashImportFile>) = error("No mutation in this fixture")
            override suspend fun replaceFile(batchId: String, expected: LocalCashImportFile, replacement: LocalCashImportFile) = error("No mutation in this fixture")
            override suspend fun removeFile(batchId: String, expected: LocalCashImportFile) = error("No mutation in this fixture")
            override suspend fun remove(batchId: String) { importAccesses++; batches.remove(batchId) }
            override suspend fun readReview(batchId: String): com.finance.tracker.business.domain.LocalCashImportReview? = error("No review access in this fixture")
            override suspend fun saveReview(batchId: String, input: com.finance.tracker.business.domain.LocalCashImportReview) = error("No review access in this fixture")
            override suspend fun readSubmission(batchId: String): LocalCashImportSubmission? = error("No submission access in this fixture")
            override suspend fun prepareSubmission(batchId: String, input: LocalCashImportSubmission) = error("No submission access in this fixture")
            override suspend fun recordBaseRejection(batchId: String, expected: LocalCashImportSubmission) = error("No submission access in this fixture")
            override suspend fun retireRejectedSubmission(batchId: String, expected: LocalCashImportSubmission, newerBase: String, replacement: LocalCashImportSubmission?) = error("No submission access in this fixture")
            override suspend fun recordReceipt(batchId: String, receipt: com.finance.tracker.business.contracts.CashMutationReceiptDto) = error("No submission access in this fixture")
            override suspend fun archiveResult(batchId: String, serverItems: List<com.finance.tracker.business.contracts.CashMutationResultDto>?): LocalCashImportResult? = error("No import access")
            override suspend fun readResult(receiptId: String): LocalCashImportResult? = error("No import access")
            override suspend fun readResultByBatchId(batchId: String): LocalCashImportResult? = error("No import access")
            override suspend fun readResultByKey(key: String): LocalCashImportResult? = error("No import access")
            override suspend fun completeSubmission(batchId: String) = error("No submission access in this fixture")
        }
        var facts: LedgerSnapshotDto? = null
        var saved: SessionDto? = null
        var savedToken: String? = "original-token"
        var closes = 0
        var clears = 0
        var beforeClose: (suspend () -> Unit)? = null
        var beforeClear: (suspend () -> Unit)? = null
        var afterReadSession: (suspend () -> Unit)? = null
        override suspend fun read(scope: LedgerCacheScope) = facts
        override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) { facts = snapshot }
        override suspend fun readOfflineSession(scope: LedgerCacheScope, token: String): SessionDto? {
            val result = saved.takeIf { facts != null && token == savedToken }
            afterReadSession?.invoke()
            return result
        }
        override suspend fun saveOfflineSession(scope: LedgerCacheScope, token: String, session: SessionDto) {
            check(facts != null)
            saved = session; savedToken = token
        }
        override suspend fun close() { closes++; beforeClose?.invoke() }
        override suspend fun clear(scope: LedgerCacheScope) { clears++; beforeClear?.invoke(); facts = null; saved = null }
    }
}

internal fun localCashImportStatementInput(ref: String, bytesReader: suspend () -> ByteArray) = CashImportUploadInput(ref, object : BoundedFileSource {
    override val name = "statement.csv"
    override val mediaType = "text/csv"
    override val size: Long? = null
    override val lastModifiedAtMillis = 1730000000000L
    override suspend fun read(): ByteArray = error("unbounded read")
    override suspend fun readBounded(maxBytes: Int): ByteArray = bytesReader().also { assertTrue(it.size <= maxBytes) }
}, sourceHint = "alipay")

internal fun localCashImportCsvBytes() = """
    支付宝交易记录明细查询
    支付宝账户：[synthetic@example.test]
    起始时间：[2026-08-01 00:00:00] 终止时间：[2026-08-31 23:59:59]
    交易类型：[全部]
    共1笔记录
    交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号
    2026-08-15 10:00:00,日用百货,合成商户,account,合成商品,支出,20.00,支付宝余额,交易成功,0000123,merchant-1
""".trimIndent().encodeToByteArray()
