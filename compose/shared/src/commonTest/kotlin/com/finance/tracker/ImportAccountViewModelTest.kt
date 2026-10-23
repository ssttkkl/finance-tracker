package com.finance.tracker

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ImportAccountViewModelTest {
    @Test fun creationPersistsKeyBeforeWireThenReadsCurrentAccountWithoutSelectingIt() = runTest { accountTask { vm, repo, recovery ->
        vm.restore(); advanceUntilIdle()
        repo.beforeWrite = { assertTrue(it in recovery.keys()) }
        vm.create("Created", "cash", listOf("CNY")); advanceUntilIdle()
        assertEquals(1, repo.writes.size)
        assertTrue(recovery.keys().isEmpty())
        assertEquals("Current name", vm.state.value.currentAccounts["1"]?.name)
        assertEquals(setOf("1"), vm.state.value.changedAccountIds)
        assertTrue(vm.state.value.needsExitDecision)
    } }

    @Test fun failedLocalPersistenceNeverSendsAccountCommand() = runTest { accountTask { vm, repo, recovery ->
        vm.restore(); advanceUntilIdle(); recovery.failAdd = true
        vm.create("Created", "cash", listOf("CNY")); advanceUntilIdle()
        assertTrue(repo.writes.isEmpty())
        assertEquals("account_recovery_unavailable", vm.state.value.errorCode)
        assertFalse(vm.state.value.needsExitDecision)
    } }

    @Test fun unknownCreationRetriesOnlyExactOriginalAndRefreshOnlyQueries() = runTest { accountTask { vm, repo, recovery ->
        vm.restore(); advanceUntilIdle(); repo.unknownWrite = true
        vm.create("Original", "cash", listOf("CNY")); advanceUntilIdle()
        val original = repo.writes.single()
        assertEquals(listOf(original.key), vm.state.value.pendingKeys)
        assertTrue(vm.state.value.currentAccounts.isEmpty())
        vm.resolve(original.key); advanceUntilIdle()
        assertEquals(listOf(original.key), vm.state.value.pendingKeys)
        vm.create("Replacement", "cash", listOf("USD")); advanceUntilIdle()
        assertEquals(1, repo.writes.size)
        vm.retryOriginal(original.key); advanceUntilIdle()
        assertEquals(listOf(original, original), repo.writes)
        vm.detach()
        val restored = ImportAccountViewModel(repo, recovery)
        restored.restore(); advanceUntilIdle(); restored.retryOriginal(original.key); advanceUntilIdle()
        assertEquals(2, repo.writes.size)
        assertEquals(listOf(original.key), restored.state.value.pendingKeys)
        assertTrue(recovery.keys().contains(original.key))
    } }

    @Test fun knownReceiptIsFollowedByCurrentReadAndDoesNotInventUndoHistory() = runTest { accountTask { vm, repo, recovery ->
        recovery.add("prior-key")
        repo.receipts["prior-key"] = accountReceipt()
        vm.restore(); advanceUntilIdle()
        assertEquals("Current name", vm.state.value.currentAccounts["1"]?.name)
        assertTrue(vm.state.value.changedAccountIds.isEmpty())
        assertFalse(vm.state.value.needsExitDecision)
        assertTrue(recovery.keys().isEmpty())
    } }

    @Test fun twoUpdatesUndoInReverseWithContinuouslyAdvancingRevisions() = runTest { accountTask { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        val first = accountValue(name = "Original", revision = 1)
        repo.current = first
        vm.update(first, accountPatch("Second")); advanceUntilIdle()
        val second = repo.current
        vm.update(second, accountPatch("Third")); advanceUntilIdle()
        vm.undo(); advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L, 4L), repo.writes.map { it.revision })
        assertEquals(listOf("Second", "Third", "Second", "Original"), repo.writes.map { (it.patch!!.fields["name"] as StructuredText).value })
        assertEquals("Original", repo.current.name)
        assertFalse(vm.state.value.needsExitDecision)
    } }

    @Test fun newAccountUndoDeactivatesBeforeDeletingAndShowsPartialFailure() = runTest { accountTask { vm, repo, _ ->
        vm.restore(); advanceUntilIdle(); vm.create("Created", "cash", listOf("CNY")); advanceUntilIdle()
        repo.deleteFailure = DomainFailure("account_in_use", 409, FailureCategory.RECOVERABLE)
        vm.undo(); advanceUntilIdle()
        assertEquals(listOf("create", "update", "delete"), repo.writes.map { it.kind })
        assertFalse(repo.current.active)
        assertEquals("account_in_use", vm.state.value.undoFailures["1"])
        assertTrue(vm.state.value.needsExitDecision)
        repo.deleteFailure = null
        vm.undo(); advanceUntilIdle()
        assertEquals(listOf("create", "update", "delete", "delete"), repo.writes.map { it.kind })
        assertFalse(vm.state.value.needsExitDecision)
    } }

    @Test fun concurrentAccountEditPreventsCompensationWithoutOverwritingOtherChanges() = runTest { accountTask { vm, repo, _ ->
        vm.restore(); advanceUntilIdle(); repo.current = accountValue(name = "Original", revision = 1)
        vm.update(repo.current, accountPatch("Our change")); advanceUntilIdle()
        repo.current = repo.current.copy(name = "Other change", revision = 3)
        vm.undo(); advanceUntilIdle()
        assertEquals(1, repo.writes.size)
        assertEquals("Other change", repo.current.name)
        assertEquals("account_changed", vm.state.value.undoFailures["1"])
        assertTrue(vm.state.value.needsExitDecision)
    } }

    @Test fun successfulWriteWithLostCurrentReadNeverRepeatsWriteOrDuplicatesUndoStep() = runTest { accountTask { vm, repo, recovery ->
        vm.restore(); advanceUntilIdle(); repo.currentReadFailure = true
        vm.create("Created", "cash", listOf("CNY")); advanceUntilIdle()
        val key = recovery.keys().single()
        assertEquals(setOf("1"), vm.state.value.changedAccountIds)
        assertTrue(vm.state.value.currentAccounts.isEmpty())
        repo.currentReadFailure = false
        vm.retryOriginal(key); advanceUntilIdle()
        assertEquals(1, repo.writes.size)
        vm.undo(); advanceUntilIdle()
        assertEquals(listOf("create", "update", "delete"), repo.writes.map { it.kind })
    } }

    @Test fun commandDeeplyFreezesPatchWhileRequestIsSuspended() = runTest { accountTask { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        val values = mutableListOf<StructuredValue>(StructuredText("USD"))
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        repo.beforeWrite = { started.complete(Unit); release.await() }
        vm.update(repo.current, StructuredObject(mapOf("currencies" to StructuredArray(values))))
        started.await(); values[0] = StructuredText("EUR"); release.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(StructuredText("USD")), (repo.writes.single().patch!!.fields["currencies"] as StructuredArray).values)
    } }

    @Test fun lateSuccessfulWriteAfterDetachCannotRestoreOldOwnersState() = runTest { accountTask { vm, repo, recovery ->
        vm.restore(); advanceUntilIdle()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        repo.beforeWrite = { withContext(NonCancellable) { started.complete(Unit); release.await() } }
        vm.create("Created", "cash", listOf("CNY")); started.await()
        vm.detach(); release.complete(Unit); advanceUntilIdle()
        assertFalse(vm.state.value.ready)
        assertTrue(vm.state.value.currentAccounts.isEmpty())
        assertTrue(vm.state.value.changedAccountIds.isEmpty())
        assertEquals(1, recovery.keys().size)
    } }

    @Test fun unrelatedReceiptCannotResolveAnUnknownAccountCommand() = runTest { accountTask { vm, repo, recovery ->
        recovery.add("prior-key")
        repo.receipts["prior-key"] = accountReceipt("cash_import")
        vm.restore(); advanceUntilIdle()
        assertEquals(listOf("prior-key"), vm.state.value.pendingKeys)
        assertEquals(listOf("prior-key"), recovery.keys())
        assertTrue(vm.state.value.currentAccounts.isEmpty())
        assertEquals("account_result_invalid", vm.state.value.errorCode)
    } }

    @Test fun unknownCompensationPausesOnlyOriginalChainUntilSameRequestCompletes() = runTest { accountTask { vm, repo, _ ->
        vm.restore(); advanceUntilIdle(); repo.current = accountValue(name = "Original", revision = 1)
        vm.update(repo.current, accountPatch("Changed")); advanceUntilIdle()
        repo.unknownWrite = true
        vm.undo(); advanceUntilIdle()
        val command = repo.writes.last()
        assertEquals(2, repo.writes.size)
        assertEquals(listOf(command.key), vm.state.value.pendingKeys)
        vm.undo(); advanceUntilIdle()
        assertEquals(2, repo.writes.size)
        assertTrue(vm.state.value.needsExitDecision)
        repo.unknownWrite = false
        vm.retryOriginal(command.key); advanceUntilIdle()
        assertEquals(command, repo.writes.last())
        assertFalse(vm.state.value.needsExitDecision)
    } }

    @Test fun accountKeyClearFailureDoesNotDuplicateSuccessfulCompensationEvidence() = runTest { accountTask { vm, repo, recovery ->
        vm.restore(); advanceUntilIdle(); recovery.failComplete = true
        vm.create("Created", "cash", listOf("CNY")); advanceUntilIdle()
        val key = recovery.keys().single()
        recovery.failComplete = false
        vm.resolve(key); advanceUntilIdle()
        assertEquals(1, repo.writes.size)
        vm.undo(); advanceUntilIdle()
        assertEquals(listOf("create", "update", "delete"), repo.writes.map { it.kind })
        assertFalse(vm.state.value.needsExitDecision)
    } }

    @Test fun otherAccountsCanUndoWhileOneAccountHasAnUnknownCompensation() = runTest { accountTask { vm, repo, _ ->
        vm.restore(); advanceUntilIdle(); repo.current = accountValue(name = "One", revision = 1)
        repo.second = accountValue(name = "Two", revision = 1).copy(accountId = "2")
        vm.update(repo.current, accountPatch("One changed")); advanceUntilIdle()
        vm.update(repo.second!!, accountPatch("Two changed")); advanceUntilIdle()
        repo.unknownIds += "2"
        vm.undo(); advanceUntilIdle()
        assertEquals("One", repo.current.name)
        assertEquals("Two changed", repo.second?.name)
        assertEquals(setOf("2"), vm.state.value.changedAccountIds)
        assertEquals(1, vm.state.value.pendingKeys.size)
    } }

    @Test fun permissionRefusalAfterUnknownWriteCannotEraseOriginalRecoveryKey() = runTest { accountTask { vm, repo, recovery ->
        vm.restore(); advanceUntilIdle(); repo.unknownWrite = true
        vm.create("Created", "cash", listOf("CNY")); advanceUntilIdle()
        val key = recovery.keys().single()
        repo.writeFailure = DomainFailure("workspace_forbidden", 403, FailureCategory.RECOVERABLE)
        vm.retryOriginal(key); advanceUntilIdle()
        assertEquals(listOf(key), recovery.keys())
        assertEquals(listOf(key), vm.state.value.pendingKeys)
        assertTrue(vm.state.value.currentAccounts.isEmpty())
    } }

    @Test fun successfulReceiptForNowDeletedAccountCompletesRecoveryWithoutInventingCurrentAccount() = runTest { accountTask { vm, repo, recovery ->
        recovery.add("prior-key"); repo.receipts["prior-key"] = accountReceipt()
        repo.accountFailure = DomainFailure("account_not_found", 404, FailureCategory.RECOVERABLE)
        vm.restore(); advanceUntilIdle()
        assertTrue(recovery.keys().isEmpty())
        assertTrue(vm.state.value.pendingKeys.isEmpty())
        assertTrue(vm.state.value.currentAccounts.isEmpty())
        assertTrue(vm.state.value.accounts.isEmpty())
        assertFalse(vm.state.value.needsExitDecision)
    } }

    @Test fun mismatchedCurrentAccountFailureStatusCannotCompleteRecovery() = runTest { accountTask { vm, repo, recovery ->
        recovery.add("prior-key"); repo.receipts["prior-key"] = accountReceipt()
        repo.accountFailure = DomainFailure("account_not_found", 503, FailureCategory.RECOVERABLE)
        vm.restore(); advanceUntilIdle()
        assertEquals(listOf("prior-key"), recovery.keys())
        assertEquals(listOf("prior-key"), vm.state.value.pendingKeys)
    } }

    @Test fun interleavedAccountRenamesUndoInTrueGlobalReverseOrder() = runTest { accountTask { vm, repo, _ ->
        vm.restore(); advanceUntilIdle(); repo.current = accountValue(name = "One", revision = 1)
        repo.second = accountValue(name = "Two", revision = 1).copy(accountId = "2")
        vm.update(repo.current, accountPatch("Temporary")); advanceUntilIdle()
        vm.update(repo.second!!, accountPatch("One")); advanceUntilIdle()
        vm.update(repo.current, accountPatch("Two")); advanceUntilIdle()
        vm.undo(); advanceUntilIdle()
        assertEquals("One", repo.current.name)
        assertEquals("Two", repo.second?.name)
        assertFalse(vm.state.value.needsExitDecision)
        assertEquals(listOf("Temporary", "One", "Two", "Temporary", "Two", "One"), repo.writes.map { (it.patch!!.fields["name"] as StructuredText).value })
    } }

    @Test fun delayedLocalClearAfterDefiniteRefusalCannotPublishIntoDetachedState() = runTest { accountTask { vm, repo, recovery ->
        vm.restore(); advanceUntilIdle()
        repo.writeFailure = DomainFailure("workspace_forbidden", 403, FailureCategory.RECOVERABLE)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        recovery.beforeComplete = { withContext(NonCancellable) { started.complete(Unit); release.await() } }
        vm.create("Created", "cash", listOf("CNY")); started.await()
        vm.detach(); release.complete(Unit); advanceUntilIdle()
        assertNull(vm.state.value.errorCode)
        assertFalse(vm.state.value.ready)
    } }

    @Test fun unknownEarlierUpdateKeepsItsOriginalOrderWhenReceiptArrivesAfterLaterAccountEdit() = runTest { accountTask { vm, repo, _ ->
        vm.restore(); advanceUntilIdle(); repo.current = accountValue(name = "One", revision = 1)
        repo.second = accountValue(name = "Two", revision = 1).copy(accountId = "2")
        repo.loseNextResponse = true
        vm.update(repo.current, accountPatch("Temporary")); advanceUntilIdle()
        val key = repo.writes.single().key
        repo.receipts[key] = accountReceipt("account_update").copy(accountRevision = 2, account = repo.current)
        vm.update(repo.second!!, accountPatch("One")); advanceUntilIdle()
        vm.resolve(key); advanceUntilIdle()
        vm.undo(); advanceUntilIdle()
        assertEquals("One", repo.current.name)
        assertEquals("Two", repo.second?.name)
        assertFalse(vm.state.value.needsExitDecision)
        assertEquals(listOf("Temporary", "One", "Two", "One"), repo.writes.map { (it.patch!!.fields["name"] as StructuredText).value })
    } }

    @Test fun resolvingFullBatchExposesRemainingKeysForAnotherReadOnlyBatch() = runTest { accountTask { vm, repo, recovery ->
        repeat(65) { recovery.add("prior-$it"); repo.receipts["prior-$it"] = accountReceipt() }
        vm.restore(); advanceUntilIdle()
        assertEquals(1, recovery.keys().size)
        assertEquals(1, vm.state.value.pendingKeys.size)
        vm.resolvePending(); advanceUntilIdle()
        assertTrue(recovery.keys().isEmpty())
        assertTrue(vm.state.value.pendingKeys.isEmpty())
        assertTrue(repo.writes.isEmpty())
    } }

    private suspend fun TestScope.accountTask(block: suspend TestScope.(ImportAccountViewModel, AccountRepository, AccountRecovery) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = AccountRepository(); val recovery = AccountRecovery(); var key = 0
            val vm = ImportAccountViewModel(repo, recovery) { "account-key-${++key}" }
            block(vm, repo, recovery)
        } finally { Dispatchers.resetMain() }
    }
}

internal fun accountValue(name: String = "Created", revision: Long = 1, active: Boolean = true, deleted: Boolean = false) =
    ImportAccount("1", name, "cash", active, listOf("CNY"), revision, deleted,
        mapOf("can_update" to true, "can_delete" to !active), "account-receipt", true)
internal fun accountPatch(name: String) = StructuredObject(mapOf("name" to StructuredText(name)))
internal fun accountReceipt(operation: String = "account_create") = ImportReceipt("account-receipt", null, operation, true,
    "now", "completed", emptyMap(), emptyMap(), null, null, null, null, null, null, "1", 1, accountValue())
private data class AccountWrite(val kind: String, val key: String, val revision: Long? = null, val name: String? = null,
    val type: String? = null, val currencies: List<String>? = null, val patch: StructuredObject? = null)
private class AccountRecovery : ImportAccountRecovery {
    var beforeComplete: suspend () -> Unit = {}
    val pending = linkedSetOf<String>(); var failAdd = false; var failComplete = false
    override suspend fun keys() = pending.toList().take(64)
    override suspend fun add(key: String) { if (failAdd) throw DomainFailure("account_recovery_unavailable", 0, FailureCategory.RECOVERABLE); pending += key }
    override suspend fun complete(key: String) { beforeComplete(); if (failComplete) error("storage"); pending -= key }
    override suspend fun verify(key: String) = key in pending
}
private class AccountRepository : CashImportLifecycleRepository by TaskRepository() {
    var current = accountValue(name = "Current name")
    var second: ImportAccount? = null
    val unknownIds = mutableSetOf<String>()
    var loseNextResponse = false
    var unknownWrite = false
    var currentReadFailure = false
    var accountFailure: DomainFailure? = null
    var deleteFailure: DomainFailure? = null
    var writeFailure: DomainFailure? = null
    val writes = mutableListOf<AccountWrite>()
    val receipts = mutableMapOf<String, ImportReceipt>()
    var beforeWrite: suspend (String) -> Unit = {}
    override suspend fun accounts() = if (accountFailure?.code == "account_not_found" && accountFailure?.status == 404) emptyList() else listOf(ImportAccountListItem(current.accountId, current.name, current.type, current.active, current.currencies))
    override suspend fun account(accountId: String): ImportAccount {
        accountFailure?.let { throw it }
        if (currentReadFailure) throw DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE)
        val value = if (accountId == "2") second!! else current
        return value.copy(capabilities = mapOf("can_update" to true, "can_delete" to !value.active))
    }
    override suspend fun receiptByKey(key: String) = receipts[key] ?: throw DomainFailure("receipt_not_found", 404, FailureCategory.RECOVERABLE)
    override suspend fun createAccount(name: String, type: String, currencies: List<String>, idempotencyKey: String): ImportAccount {
        beforeWrite(idempotencyKey); writes += AccountWrite("create", idempotencyKey, name = name, type = type, currencies = currencies)
        writeFailure?.let { throw it }
        if (unknownWrite) throw DomainFailure("account_result_unknown", 503, FailureCategory.RECOVERABLE)
        return accountValue()
    }
    override suspend fun updateAccount(accountId: String, revision: Long, patch: StructuredObject, idempotencyKey: String): ImportAccount {
        beforeWrite(idempotencyKey); writes += AccountWrite("update", idempotencyKey, revision, patch = patch)
        if (unknownWrite || accountId in unknownIds) throw DomainFailure("account_result_unknown", 503, FailureCategory.RECOVERABLE)
        val prior = if (accountId == "2") second!! else current
        val other = if (accountId == "2") current else second
        if ((patch.fields["name"] as? StructuredText)?.value == other?.name && other != null)
            throw DomainFailure("account_name_unavailable", 409, FailureCategory.RECOVERABLE)
        assertEquals(prior.revision, revision)
        val result = prior.copy(name = (patch.fields["name"] as? StructuredText)?.value ?: prior.name,
            active = (patch.fields["active"] as? StructuredBoolean)?.value ?: prior.active,
            currencies = (patch.fields["currencies"] as? StructuredArray)?.values?.map { (it as StructuredText).value } ?: prior.currencies,
            revision = revision + 1)
        if (accountId == "2") second = result else current = result
        if (loseNextResponse) { loseNextResponse = false; throw DomainFailure("account_result_unknown", 503, FailureCategory.RECOVERABLE) }
        return result
    }
    override suspend fun deleteAccount(accountId: String, revision: Long, idempotencyKey: String): ImportAccount {
        beforeWrite(idempotencyKey); writes += AccountWrite("delete", idempotencyKey, revision)
        deleteFailure?.let { throw it }
        assertFalse(current.active); assertEquals(current.revision, revision)
        current = current.copy(deleted = true, revision = revision + 1)
        return current
    }
}
