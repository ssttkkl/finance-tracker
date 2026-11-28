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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClientSessionConcurrentReadTest {
    @Test
    fun lateSnapshotCannotReplaceNewerFactsInTheSameSession() {
        val store = Store()
        store.cached = ClientLedgerState("original", ClientStoredLedger(facts("9007199254740994"), "earlier"))
        store.index = true
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
        assertEquals(0, store.fullReads)
    }

    @Test
    fun theSameVersionAndOfflineReadAuthenticateFactsWhileMissingIndexFallsBack() {
        val store = Store().apply {
            cached = ClientLedgerState("original", ClientStoredLedger(facts("3"), "earlier"))
            index = true
        }
        var failure: Exception? = null
        var remote = "3"
        val client = ClientSessionOperations(ClientHttp { request ->
            failure?.let { throw it }
            when (request.path.substringBefore('?')) {
                "/api/v1/auth/session" -> ClientHttpResponse(200, Json.encodeToString(session.session))
                "/api/v1/ledger/version" -> ClientHttpResponse(200, """{"ledger_seq":"$remote"}""")
                "/api/v1/ledger/snapshot" -> snapshot(remote)
                else -> error("Unexpected path")
            }
        }, store, { it }, { "today" })
        fun read(): ClientLedgerRead {
            var result: Result<ClientLedgerRead>? = null
            suspend { client.ledger(null) }.startCoroutine(capture { result = it })
            return checkNotNull(result).getOrThrow()
        }
        assertEquals("3", read().ledger.snapshot.ledgerSeq)
        assertEquals(1, store.fullReads)
        failure = ClientNetworkUnavailable(IllegalStateException("offline"))
        assertEquals(true, read().offline)
        assertEquals(2, store.fullReads)
        failure = null
        store.index = false
        remote = "4"
        assertEquals("4", read().ledger.snapshot.ledgerSeq)
        assertEquals(3, store.fullReads)
        store.index = true
        store.ledgerReadsFail = true
        assertEquals("local_state_unreadable", assertFailsWith<ClientOperationFailure> { read() }.code)
    }

    private fun snapshot(revision: String): ClientHttpResponse = ClientHttpResponse(200, Json.encodeToString(facts(revision)))

    private fun facts(revision: String): LedgerSnapshotDto {
        val text = checkNotNull(javaClass.getResourceAsStream("/cash-operation-snapshot.json")).bufferedReader().use { it.readText() }
        return Json.decodeFromString<LedgerSnapshotDto>(text).copy(ledgerSeq = revision)
    }

    private fun <T> capture(complete: (Result<T>) -> Unit) = object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) = complete(result)
    }

    private class Store : ClientSessionStore {
        var cached = ClientLedgerState()
        var index = false
        var fullReads = 0
        var ledgerReadsFail = false
        override suspend fun session(): ClientStoredSession = session
        override suspend fun authenticate(previous: ClientStoredSession?, session: ClientStoredSession): Boolean = error("Unused")
        override suspend fun verify(previous: ClientStoredSession, session: ClientStoredSession): Boolean = previous.session == session.session
        override suspend fun ledgerState(session: ClientStoredSession, workspaceId: String): ClientLedgerState {
            fullReads++
            if (ledgerReadsFail) throw ClientOperationFailure("local_state_unreadable")
            return cached
        }
        override suspend fun indexedLedgerVersion(session: ClientStoredSession, workspaceId: String): ClientLedgerCacheVersion? =
            if (index) cached.ledger?.let { ClientLedgerCacheVersion(checkNotNull(cached.generation), it.snapshot.ledgerSeq) } else null
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
