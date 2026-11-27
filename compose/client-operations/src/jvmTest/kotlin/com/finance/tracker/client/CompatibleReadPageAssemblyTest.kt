package com.finance.tracker.client

import com.finance.tracker.business.contracts.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class CompatibleReadPageAssemblyTest {
    @Test
    fun pagedSnapshotOnlyPublishesACompleteVersionAndFallsBackOnlyWithoutNegotiation() = runBlocking {
        val legacyJson = checkNotNull(javaClass.getResourceAsStream("/cash-operation-snapshot.json")).use { it.readBytes().decodeToString() }
        val legacy = Json.decodeFromString<LedgerSnapshotDto>(legacyJson)
        val snapshot = legacy.copy(ledgerSeq = (legacy.ledgerSeq.toLong() + 1).toString())
        val sourceFacts = Json.encodeToJsonElement(snapshot.facts).jsonObject
        val sections = LedgerSnapshotSection.entries.map { it.wireName }
        val workspace = WorkspaceDto(legacy.workspaceId, "Fixture", Role.EDITOR)
        val session = ClientStoredSession("synthetic-token", SessionDto(UserDto("fixture@example.invalid"),
            workspace.id, listOf(workspace)))
        for (mode in listOf("complete", "old-server", "closed", "missing", "duplicate", "premature-end", "wrong-scope", "changed-version")) {
            val cache = SnapshotStore(session, legacy)
            val paths = mutableListOf<String>()
            var pageIndex = 0
            val http = ClientHttp { request ->
                val path = request.path.substringBefore('?')
                paths += path
                when (path) {
                    "/api/v1/auth/session" -> ClientHttpResponse(200, Json.encodeToString(session.session), mode != "old-server")
                    "/api/v1/ledger/version" -> ClientHttpResponse(200, Json.encodeToString(LedgerVersionDto(snapshot.ledgerSeq)))
                    "/api/v1/ledger/snapshot" -> {
                        assertEquals("old-server", mode)
                        ClientHttpResponse(200, Json.encodeToString(snapshot))
                    }
                    LEDGER_SNAPSHOT_PAGE_PATH -> {
                        if (mode == "closed" && pageIndex == 1) throw ClientNetworkUnavailable()
                        if (mode == "missing" && pageIndex == 1)
                            return@ClientHttp ClientHttpResponse(404, """{"error":{"code":"read_page_missing"}}""")
                        val index = if (mode == "duplicate" && pageIndex == 1) 0 else pageIndex
                        val section = sections[index]
                        val data = sourceFacts.getValue(section).jsonArray
                        val next = sections.getOrNull(index + 1)?.let { ReadPagePosition(it, 0) }
                        if (pageIndex != 0) assertTrue(request.path.contains("&ledger_seq=${snapshot.ledgerSeq}&section=$section") || mode == "duplicate")
                        val page = LedgerSnapshotReadPage(
                            if (mode == "wrong-scope" && pageIndex == 1) "other-workspace" else workspace.id,
                            if (mode == "changed-version" && pageIndex == 1) (snapshot.ledgerSeq.toLong() + 1).toString() else snapshot.ledgerSeq,
                            snapshot.rulesVersion, snapshot.facts.categoryDirectoryRevision,
                            ReadPageChunk(ReadPagePosition(section, 0), data.size, data, null,
                                if (mode == "premature-end" && pageIndex == 1) null else next),
                        )
                        pageIndex++
                        ClientHttpResponse(200, Json.encodeToString(page))
                    }
                    else -> error("Unexpected route $path")
                }
            }
            val operations = ClientSessionOperations(http, cache, { it }, { "2026-11-27T01:00:00Z" })
            when (mode) {
                "complete", "old-server" -> {
                    val result = operations.ledger(workspace.id)
                    assertEquals(snapshot, result.ledger.snapshot)
                    assertEquals(1, cache.writes)
                    assertEquals(if (mode == "old-server") 0 else sections.size, pageIndex)
                }
                "closed" -> {
                    val result = operations.ledger(workspace.id)
                    assertTrue(result.offline)
                    assertEquals(legacy, result.ledger.snapshot)
                    assertEquals(0, cache.writes)
                }
                else -> {
                    assertFailsWith<ClientOperationFailure>(mode) { operations.ledger(workspace.id) }
                    assertEquals(0, cache.writes, mode)
                    assertEquals(legacy, cache.current.ledger?.snapshot)
                }
            }
            if (mode != "old-server") assertTrue("/api/v1/ledger/snapshot" !in paths)
        }
    }

    @Test
    fun aMissingOrChangedContinuationCannotStandInForAnAbsentOriginalReceipt() = runBlocking {
        val body = checkNotNull(javaClass.getResourceAsStream("/cash-operation-snapshot.json")).use { it.readBytes().decodeToString() }
        val ledger = Json.decodeFromString<LedgerSnapshotDto>(body)
        val workspace = WorkspaceDto(ledger.workspaceId, "Fixture", Role.VIEWER)
        val session = ClientStoredSession("synthetic-token", SessionDto(UserDto("fixture@example.invalid"),
            workspace.id, listOf(workspace)), supportsCompatibleReadPages = true)
        val sections = CashImportReceiptSection.entries.map { it.wireName }
        val receipt = CashMutationReceiptDto("original-receipt", operationKind = "cash_write_set", committed = true,
            createdAt = "2026-11-27T01:00:00Z", ledgerSeq = "9007199254740993", pendingItemCount = 0)
        val transaction = CashWriteSetTransactionResultDto("9007199254740993", Long.MAX_VALUE.toString(), listOf("2"))
        val header = buildJsonObject {
            put("receipt", Json.encodeToJsonElement(receipt))
            put("has_pending_transaction_ids", true)
        }
        for (mode in listOf("complete", "missing-first", "missing-continuation", "changed-receipt", "wrong-version", "premature-end")) {
            val cache = SnapshotStore(session, ledger)
            var index = 0
            val requests = mutableListOf<String>()
            val http = ClientHttp { request ->
                assertEquals("GET", request.method)
                assertEquals("origin-key", request.headers["Idempotency-Key"])
                assertEquals(CASH_IMPORT_RECEIPT_PAGE_PATH, request.path.substringBefore('?'))
                requests += request.path
                val here = index++
                if (mode == "missing-first" && here == 0 || mode == "missing-continuation" && here == 1)
                    return@ClientHttp ClientHttpResponse(404, """{"error":{"code":"receipt_not_found"}}""")
                val field = sections[here]
                val values = when (here) {
                    0 -> listOf(header)
                    1 -> listOf(Json.encodeToJsonElement(transaction))
                    else -> emptyList()
                }
                val next = if (mode == "premature-end" && here == 1) null
                else sections.getOrNull(here + 1)?.let { ReadPagePosition(it, 0) }
                val page = CashImportReceiptReadPage(workspace.id,
                    if (mode == "changed-receipt" && here == 1) "other-receipt" else receipt.receiptId,
                    if (mode == "wrong-version" && here == 1) "9007199254740994" else checkNotNull(receipt.ledgerSeq),
                    ReadPageChunk(ReadPagePosition(field, 0), values.size, values, null, next))
                ClientHttpResponse(200, Json.encodeToString(page))
            }
            val operations = ClientSessionOperations(http, cache, { it }, { "2026-11-27T01:00:00Z" })
            when (mode) {
                "complete" -> {
                    val mounted = assertNotNull(operations.cashImportReceipt(session, workspace.id, "origin-key"))
                    assertEquals(receipt.copy(writeSetResult = CashWriteSetResultDto(listOf(transaction), emptyList(),
                        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
                        pendingTransactionIds = emptyList()), mounted)
                    assertEquals(sections.size, requests.size)
                }
                "missing-first" -> assertNull(operations.cashImportReceipt(session, workspace.id, "origin-key"))
                else -> {
                    val failure = assertFailsWith<ClientOperationFailure> {
                        operations.cashImportReceipt(session, workspace.id, "origin-key")
                    }
                    assertEquals("protocol_error", failure.code)
                    assertEquals(2, requests.size)
                }
            }
            assertEquals(0, cache.writes)
        }
    }

    @Test
    fun fragmentOffsetsAndCollectionTotalsAreVerifiedBeforeAnElementIsExposed() {
        val read = mutableListOf<JsonElement>()
        val assembly = ReadPageAssembly(listOf("facts", "other")) { _, value -> read += value }
        val value = JsonPrimitive("😀 现金 \\ row")
        val body = value.toString().encodeToByteArray()
        val chunks = listOf(body.copyOfRange(0, 2), body.copyOfRange(2, body.size))
        val start = ReadPagePosition("facts", 0)
        val second = start.copy(fragmentOffset = chunks[0].size)
        fun fragment(position: ReadPagePosition, data: ByteArray, next: ReadPagePosition?) =
            ReadPageChunk(position, 1, emptyList(), ReadPageFragment(body.size, Base64.encode(data)), next)
        assembly.append(fragment(start, chunks[0], second))
        assertEquals(emptyList(), read)
        assertFailsWith<ClientOperationFailure> { assembly.append(fragment(start, chunks[1], ReadPagePosition("other", 0))) }
        assembly.append(fragment(second, chunks[1], ReadPagePosition("other", 0)))
        assertEquals<List<JsonElement>>(listOf(value), read)
        assembly.append(ReadPageChunk(ReadPagePosition("other", 0), 0, emptyList(), null, null))
        assertNull(assembly.expected)

        val missing = ReadPageAssembly(listOf("facts", "other")) { _, _ -> }
        assertFailsWith<ClientOperationFailure> {
            missing.append(ReadPageChunk(start, 2, listOf(value), null, ReadPagePosition("other", 0)))
        }
    }

    private class SnapshotStore(private val original: ClientStoredSession, ledger: LedgerSnapshotDto) : ClientSessionStore {
        var writes = 0
        var current = ClientLedgerState("original", ClientStoredLedger(ledger, "2026-11-26T01:00:00Z"))
        override suspend fun session() = original
        override suspend fun authenticate(previous: ClientStoredSession?, session: ClientStoredSession): Boolean = error("Unused")
        override suspend fun verify(previous: ClientStoredSession, session: ClientStoredSession) = true
        override suspend fun ledgerState(session: ClientStoredSession, workspaceId: String) = current
        override suspend fun saveLedger(session: ClientStoredSession, expectedGeneration: String?, ledger: ClientStoredLedger): Boolean {
            assertEquals("original", expectedGeneration)
            writes++
            current = ClientLedgerState("next", ledger)
            return true
        }
        override suspend fun forgetWorkspace(session: ClientStoredSession, workspaceId: String): Unit = error("Unused")
        override suspend fun clear(session: ClientStoredSession): Unit = error("Unused")
    }
}
