package com.finance.tracker.data

import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException

/** Read details from the published local scope; preserve the real server command boundary. */
internal class LocalCashMaintenanceRepository(
    private val delegate: CashMaintenanceRepository,
    private val reader: suspend () -> LedgerLocalReader?,
) : CashMaintenanceRepository by delegate {
    override suspend fun record(transactionId: String) = local { it.record(transactionId) }.toDomain()
    override suspend fun currentGroup(projectionId: String) = local { it.currentGroup(projectionId) }.let { group ->
        CashCurrentGroup(group.projectionVersion, group.members.map { CashCurrentMember(it.transactionId, it.rowRevision, it.confirmationStatus) })
    }
    override suspend fun sources(projectionId: String) = local { it.sourceRecords(projectionId) }.toDomain()

    private suspend fun <T> local(read: suspend (LedgerLocalReader) -> T?): T = readLocalCash(reader, read)
}

internal suspend fun <T> readLocalCash(reader: suspend () -> LedgerLocalReader?, read: suspend (LedgerLocalReader) -> T?): T = try {
    val active = reader() ?: throw DomainFailure("projection.unavailable", 503, FailureCategory.RECOVERABLE)
    read(active) ?: throw DomainFailure("projection.unavailable", 503, FailureCategory.RECOVERABLE)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: CashProjectionDomainFailure) {
    val status = when (failure.code) {
        "read_snapshot_changed" -> 409
        "invalid_filter", "invalid_cursor" -> 400
        "record_not_found", "projection_not_found" -> 404
        else -> 0
    }
    throw DomainFailure(failure.code, status,
        if (failure.code == "internal") FailureCategory.UNKNOWN else FailureCategory.RECOVERABLE, cause = failure)
} catch (failure: Throwable) {
    throw failure.toDomainFailure()
}
