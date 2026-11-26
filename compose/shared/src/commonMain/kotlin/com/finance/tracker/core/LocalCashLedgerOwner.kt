package com.finance.tracker.core

import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.CashCurrentMembersDto
import com.finance.tracker.business.contracts.CashCurrentRecordDto
import com.finance.tracker.business.contracts.CashMutationProjectionRefDto
import com.finance.tracker.business.contracts.CashSourceEvidenceDto
import kotlinx.serialization.json.*
import com.finance.tracker.business.contracts.CategoryDirectoryDto
import com.finance.tracker.business.contracts.LedgerOptionsDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.buildCashLedgerOptions
import com.finance.tracker.client.cashCategoryDirectory
import com.finance.tracker.business.domain.buildLocalCashAccountReferences
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.LedgerFactCache
import com.finance.tracker.domain.LedgerSnapshotSource
import com.finance.tracker.domain.LedgerSnapshotSync
import com.finance.tracker.domain.PreparedLedgerSnapshot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One authenticated scope's committed browsing state; the supplied cache owns encryption and erasure. */
internal class LocalCashLedgerOwner(private val scope: LedgerCacheScope, cache: LedgerFactCache, private val source: LedgerSnapshotSource) {
    private val sync = LedgerSnapshotSync(scope, cache, source)
    private val readerMutex = Mutex()
    private val background = createBackgroundCashLedger(scope.workspaceId)
    private data class Capture(val ledgerSeq: String, val rulesVersion: String, val reader: LocalCashLedgerReader)
    private var capture: Capture? = null // Access only while holding readerMutex.
    private fun active() = sync.requireActive()

    /** Null means no committed cache; an empty committed ledger returns a real page. Never contacts the server. */
    suspend fun page(filters: CashFiltersDto = CashFiltersDto(), timeZoneId: String? = null,
        cursor: String? = null, startIndex: Int? = null, expectedLedgerSeq: Long? = null, limit: Int = 20): CashPageDto? {
        val backgroundReader = background ?: return readSnapshot { snapshot ->
            reader(snapshot).page(filters, timeZoneId, cursor, startIndex, expectedLedgerSeq, limit)
        }
        return withBackgroundSnapshot { backgroundReader.page(it, filters, timeZoneId, cursor, startIndex, expectedLedgerSeq, limit) }
    }

    suspend fun projectionRefs(transactionIds: List<String>): LocalCashProjectionReferences? {
        val frozen = transactionIds.toList()
        if (frozen.isEmpty()) return sync.cachedVersion()?.let { LocalCashProjectionReferences(it, emptyList()) }
        if (background != null) return backgroundRead("refs", { put("ids", Json.encodeToJsonElement(frozen)) }) {
            LocalCashProjectionReferences(it.jsonObject.getValue("version").jsonPrimitive.content.toLong(),
                detailJson.decodeFromJsonElement<List<CashMutationProjectionRefDto>>(it.jsonObject.getValue("refs")))
        }
        return readSnapshot { reader(it).projectionRefs(frozen) }
    }

    /** Both reads reuse one derived capture; the caller holds readerMutex. */
    private fun reader(snapshot: LedgerSnapshotDto): LocalCashLedgerReader =
        (capture?.takeIf { it.ledgerSeq == snapshot.ledgerSeq && it.rulesVersion == snapshot.rulesVersion }
            ?: Capture(snapshot.ledgerSeq, snapshot.rulesVersion, LocalCashLedgerReader(snapshot)).also { capture = it }).reader

    suspend fun currentGroup(projectionId: String): CashCurrentMembersDto? =
        if (background == null) readSnapshot { reader(it).currentGroup(projectionId) }
        else backgroundRead("group", { put("id", projectionId) }) { detailJson.decodeFromJsonElement(it) }

    suspend fun record(transactionId: String, canWrite: Boolean): CashCurrentRecordDto? =
        if (background == null) readSnapshot { reader(it).record(transactionId, canWrite) }
        else backgroundRead("record", { put("id", transactionId); put("canWrite", canWrite) }) {
            detailJson.decodeFromJsonElement(it)
        }

    suspend fun sourceRecords(projectionId: String, canWrite: Boolean): LocalCashSourceRecords? =
        if (background == null) readSnapshot { reader(it).sourceRecords(projectionId, canWrite) }
        else backgroundRead("sources", { put("id", projectionId); put("canWrite", canWrite) }) {
            val result = it.jsonObject
            LocalCashSourceRecords(detailJson.decodeFromJsonElement<CashSourceEvidenceDto>(result.getValue("evidence")),
                detailJson.decodeFromJsonElement<Map<String, CashCurrentRecordDto>>(result.getValue("records")))
        }

    suspend fun categories(): CategoryDirectoryDto? =
        if (background == null) readSnapshot(::cashCategoryDirectory)
        else backgroundRead("categories", {}) { detailJson.decodeFromJsonElement(it) }

    suspend fun options(): LedgerOptionsDto? = sync.cachedVersion()?.let { buildCashLedgerOptions() }

    suspend fun accounts(): List<AccountReferenceDto>? =
        if (background == null) readSnapshot { buildLocalCashAccountReferences(it.facts.accounts) }
        else backgroundRead("accounts", {}) { detailJson.decodeFromJsonElement(it) }

    private suspend fun <T> backgroundRead(mode: String, args: JsonObjectBuilder.() -> Unit, decode: (JsonElement) -> T): T? =
        withBackgroundSnapshot { snapshot ->
            val query = buildJsonObject { put("mode", mode); args() }
            decode(checkNotNull(background).read(snapshot, query))
        }

    private suspend fun <T> withBackgroundSnapshot(read: suspend (String) -> T): T? = readerMutex.withLock {
        active()
        val text = sync.cachedText()
        if (text == null) {
            background?.close()
            return@withLock null
        }
        read(text).also { active() }
    }

    suspend fun importSelectionAccount(accountId: String): com.finance.tracker.business.contracts.LedgerAccountFactDto? =
        if (background == null) readSnapshot { snapshot ->
            snapshot.facts.accounts.singleOrNull { it.id == accountId && it.type in setOf("cash", "loan", "lend") }
        } else backgroundRead("selectionAccount", { put("id", accountId) }) {
            detailJson.decodeFromJsonElement<com.finance.tracker.business.contracts.LedgerAccountFactDto?>(it)
        }

    suspend fun snapshot(): LedgerSnapshotDto? = readSnapshot { it }
    suspend fun snapshotText(): String? = sync.cachedText()
    internal suspend fun preparedSnapshot() = sync.cachedPreparedSnapshot()
    suspend fun ledgerVersion(): Long? = sync.cachedVersion()

    /** Catalogs and pages share committed-fact eligibility and the existing read/close boundary. */
    private suspend fun <T> readSnapshot(read: (LedgerSnapshotDto) -> T): T? = readerMutex.withLock {
        active()
        val snapshot = sync.cached()
        active()
        if (snapshot == null) { capture = null; return@withLock null }
        val result = read(snapshot)
        active()
        result
    }

    /** The network runs outside the reader lock. Only successfully published complete facts become readable. */
    suspend fun refresh(committedLedgerSeq: String? = null) = refresh(committedLedgerSeq, com.finance.tracker.data.importSnapshotPreparation(source, scope.workspaceId))

    internal suspend fun refresh(committedLedgerSeq: String?, prepare: (suspend () -> PreparedLedgerSnapshot)?) {
        active()
        try {
            if (prepare == null) sync.refresh(committedLedgerSeq) else sync.refreshStored(committedLedgerSeq, prepare)
            active()
        } catch (failure: Throwable) {
            releaseReader()
            throw failure
        }
    }

    suspend fun close() {
        try { sync.close() } finally { releaseReader() }
    }

    suspend fun clear() {
        try { sync.clear() } finally { releaseReader() }
    }

    private suspend fun releaseReader() = withContext(NonCancellable) {
        readerMutex.withLock { capture = null; background?.close() }
    }
}

private val detailJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
