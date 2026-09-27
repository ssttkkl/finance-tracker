package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InvitationViewModelTest {
    @Test
    fun loadsPreviewAndReturnsAcceptedSessionThroughState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = InvitationViewModel(FakeSessionRepository())
            viewModel.load("invite-token")
            advanceUntilIdle()

            assertFalse(viewModel.state.value.loading)
            assertEquals("Travel", viewModel.state.value.preview?.workspace?.name)
            assertNull(viewModel.state.value.errorCode)

            viewModel.accept("invite-token")
            advanceUntilIdle()

            assertNotNull(viewModel.state.value.acceptedSession)
            viewModel.consumeAcceptedSession()
            assertNull(viewModel.state.value.acceptedSession)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun terminalPreviewFailureDisablesRetryAndKeepsSafeCode() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeSessionRepository().apply {
                invitationPreviewFailure = DomainFailure("invitation_expired", 410, FailureCategory.RECOVERABLE)
            }
            val viewModel = InvitationViewModel(repository)
            viewModel.load("expired-token")
            advanceUntilIdle()

            assertEquals("invitation_expired", viewModel.state.value.errorCode)
            assertTrue(viewModel.state.value.terminalPreviewError)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
