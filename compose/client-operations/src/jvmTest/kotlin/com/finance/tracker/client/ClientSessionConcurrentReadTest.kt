package com.finance.tracker.client

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import kotlinx.serialization.json.Json
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClientSessionConcurrentReadTest {
    @Test
    fun lateSnapshotCannotReplaceNewerFactsInTheSameSession() {
        val store = Store()
        var first: Continuation<ClientHttpResponse>? = null
        var remoteRevision = "9007199254740995"
        val client = ClientSessionOperations(ClientHttp { request ->
            when (request.path.substringBefore('?')) {
                "/api/v1/auth/session" -> ClientHttpResponse(200, Json.encodeToString(session.session))
                "/api/v1/ledger/version" -> ClientHttpResponse(200, """{"ledger_seq":"$remoteRevision"}""")
                "/api/v1/ledger/snapshot" -> if (first == null) suspendCoroutine { first = it } else snapshot(remoteRevision)
                else -> error("Unexpected path")
            }
        }, store, { it }, { "2026-11-26T00:00:00Z" })

        var slow: Result<ClientLedgerRead>? = null
        suspend { client.ledger(null) }.startCoroutine(capture { slow = it })
        assertNotNull(first)
        assertNull(slow)
        remoteRevision = "9007199254740996"
        var fast: Result<ClientLedgerRead>? = null
        suspend { client.ledger(null) }.startCoroutine(capture { fast = it })
        assertEquals(remoteRevision, fast?.getOrThrow()?.ledger?.snapshot?.ledgerSeq)
        first.resume(snapshot("9007199254740995"))
        assertEquals("read_snapshot_changed", (slow?.exceptionOrNull() as? ClientOperationFailure)?.code)
        assertEquals(remoteRevision, store.cached.ledger?.snapshot?.ledgerSeq)
    }

    private fun snapshot(revision: String): ClientHttpResponse {
        val text = checkNotNull(javaClass.getResourceAsStream("/cash-operation-snapshot.json")).bufferedReader().use { it.readText() }
        return ClientHttpResponse(200, Json.encodeToString(Json.decodeFromString<LedgerSnapshotDto>(text).copy(ledgerSeq = revision)))
    }

    private fun <T> capture(complete: (Result<T>) -> Unit) = object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) = complete(result)
    }

    private class Store : ClientSessionStore {
        var cached = ClientLedgerState()
        override suspend fun session(): ClientStoredSession = session
        override suspend fun authenticate(previous: ClientStoredSession?, session: ClientStoredSession): Boolean = error("Unused")
        override suspend fun verify(previous: ClientStoredSession, session: ClientStoredSession): Boolean = previous.session == session.session
        override suspend fun ledgerState(session: ClientStoredSession, workspaceId: String): ClientLedgerState = cached
        override suspend fun saveLedger(session: ClientStoredSession, expectedGeneration: String?, ledger: ClientStoredLedger): Boolean {
            if (cached.generation != expectedGeneration) return false
            cached = ClientLedgerState(ledger.snapshot.ledgerSeq, ledger)
            return true
        }
        override suspend fun forgetWorkspace(session: ClientStoredSession, workspaceId: String) = error("Unused")
        override suspend fun clear(session: ClientStoredSession) = error("Unused")
    }

    private companion object {
        val session = ClientStoredSession("synthetic", SessionDto(UserDto("person@example.test"), "workspace-a",
            listOf(WorkspaceDto("workspace-a", "Example", Role.EDITOR))))
    }
}
