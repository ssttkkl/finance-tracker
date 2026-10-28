package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CashCreationViewModelTest {
    private val accounts = listOf(Account(1, "Daily", "cash", currencies = listOf("CNY")), Account(2, "Bank", "cash", currencies = listOf("CNY")))
    @Test fun switchingKeepsBothInputsAndOnlySendsCurrentMode() = runTest { task { vm, repo ->
        vm.open(accounts, "2026-10-24T12:30")
        val initial = assertNotNull(vm.state.value.draft)
        vm.update(initial.copy(ordinary = CashOrdinaryInput(initial.ordinary.values + ("amount" to "2.5000") + ("note" to "ordinary"))))
        val ordinary = vm.state.value.draft!!.ordinary
        vm.update(vm.state.value.draft!!.copy(mode = CashCreationMode.TRANSFER, transfer = initial.transfer.copy(amount = "9.000", note = "transfer")))
        vm.update(vm.state.value.draft!!.copy(mode = CashCreationMode.ORDINARY))
        assertEquals(ordinary, vm.state.value.draft!!.ordinary)
        assertEquals("9.000", vm.state.value.draft!!.transfer.amount)
        vm.save(accounts, "UTC"); advanceUntilIdle()
        assertEquals(1, repo.records.size); assertTrue(repo.transfers.isEmpty())
        assertEquals(StructuredText("-2.5000"), repo.records.single().fields["amount"])
        assertNull(vm.state.value.draft); assertEquals(1, vm.state.value.savedRevision)
    } }
    @Test fun unknownResponsePreservesInputsAndDoesNotSendAgain() = runTest { task { vm, repo ->
        vm.open(accounts, "2026-10-24T12:30"); val initial = vm.state.value.draft!!
        vm.update(initial.copy(mode = CashCreationMode.TRANSFER, transfer = initial.transfer.copy(amount = "9")))
        repo.failure = DomainFailure("network_unavailable", 0, FailureCategory.RECOVERABLE)
        vm.save(accounts, "UTC"); advanceUntilIdle()
        assertTrue(vm.state.value.unknown); assertNotNull(vm.state.value.draft)
        vm.save(accounts, "UTC"); advanceUntilIdle(); assertEquals(1, repo.transfers.size)
        assertTrue(repo.records.isEmpty())
    } }
    @Test fun invalidAndKnownFailureKeepDraftWithoutUnknownRecovery() = runTest { task { vm, repo ->
        vm.open(accounts, "2026-10-24T12:30"); vm.save(accounts, "UTC"); advanceUntilIdle()
        assertEquals("invalid_amount", vm.state.value.errorCode); assertTrue(repo.records.isEmpty())
        val draft = vm.state.value.draft!!; vm.update(draft.copy(ordinary = CashOrdinaryInput(draft.ordinary.values + ("amount" to "1"))))
        repo.failure = DomainFailure("invalid_record", 422, FailureCategory.RECOVERABLE)
        vm.save(accounts, "UTC"); advanceUntilIdle()
        assertFalse(vm.state.value.unknown); assertNotNull(vm.state.value.draft); assertEquals(0, vm.state.value.savedRevision)
    } }
    @Test fun savingCannotCloseChangeOrSubmitTwice() = runTest { task { vm, repo ->
        vm.open(accounts, "2026-10-24T12:30"); val initial = vm.state.value.draft!!
        vm.update(initial.copy(ordinary = CashOrdinaryInput(initial.ordinary.values + ("amount" to "1"))))
        repo.gate = CompletableDeferred(); vm.save(accounts, "UTC"); runCurrent()
        assertTrue(vm.state.value.busy); assertFalse(vm.close())
        vm.update(initial); vm.save(accounts, "UTC"); assertEquals("1", vm.state.value.draft!!.ordinary.values["amount"])
        repo.gate!!.complete(Unit); advanceUntilIdle(); assertEquals(1, repo.records.size)
    } }
    private suspend fun TestScope.task(block: suspend TestScope.(CashCreationViewModel, CreationRepo) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { val repo = CreationRepo(); block(CashCreationViewModel(repo), repo) } finally { Dispatchers.resetMain() }
    }
}
private class CreationRepo : CashCreationRepository {
    val records = mutableListOf<StructuredObject>(); val transfers = mutableListOf<StructuredObject>()
    var gate: CompletableDeferred<Unit>? = null; var failure: DomainFailure? = null
    override suspend fun createRecord(input: StructuredObject) { records += input; gate?.await(); failure?.let { throw it } }
    override suspend fun createTransfer(input: StructuredObject) { transfers += input; gate?.await(); failure?.let { throw it } }
}
