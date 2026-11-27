package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.WORKSPACES
import com.finance.tracker.business.contracts.LedgerVersionDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.LedgerSnapshotReadPage
import com.finance.tracker.business.contracts.LedgerSnapshotSection
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import java.sql.Connection
import java.time.Instant

internal class LedgerReadService(private val database: Database, now: () -> Instant = Instant::now,
    private val afterRead: () -> Unit = {}, private val afterSnapshotVersionRead: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    private fun same(expected: Principal, actual: Principal) {
        if (expected.userId != actual.userId || expected.workspaceId != actual.workspaceId) throw AccessFailure(true)
    }
    fun version(token: String?, request: Principal? = null): LedgerVersionDto {
        val (principal, version) = database.read { connection ->
            val current = reads.context(connection, token, write = false)
            if (request != null) same(request, current)
            val table = WORKSPACES
            val value = connection.jooq(database.sqlite).select(table.LEDGER_SEQ).from(table)
                .where(table.ID.eq(current.workspaceId)).fetchOne(table.LEDGER_SEQ) ?: throw AccessFailure(true)
            current to LedgerVersionDto(value.toString())
        }
        afterRead()
        same(principal, reads.currentContext(token))
        return version
    }
    fun snapshot(token: String?, request: Principal? = null): LedgerSnapshotDto {
        val (principal, snapshot) = database.read { connection ->
            if (!database.sqlite) connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            val current = reads.context(connection, token, write = false)
            if (request != null) same(request, current)
            val workspace = requireNotNull(current.workspaceId)
            val value = connection.jooq(database.sqlite).select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES)
                .where(WORKSPACES.ID.eq(workspace)).fetchOne(WORKSPACES.LEDGER_SEQ) ?: throw AccessFailure(true)
            afterSnapshotVersionRead()
            current to LedgerSnapshotDto(workspace, value.toString(), CASH_RULES_VERSION, LedgerSnapshotRepository(connection, workspace, database.sqlite).facts())
        }
        afterRead()
        same(principal, reads.currentContext(token))
        return snapshot
    }

    internal fun snapshotPage(token: String?, request: Principal, page: ReadPageRequest): ByteArray {
        val section = LedgerSnapshotSection.entries.singleOrNull { it.wireName == page.position.section }
            ?: throw ReadPageFailure()
        val (principal, result) = database.read { connection ->
            if (!database.sqlite) connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            val current = reads.context(connection, token, write = false)
            same(request, current)
            val workspace = requireNotNull(current.workspaceId)
            if (workspace != page.workspace) throw AccessFailure(true)
            val version = connection.jooq(database.sqlite).select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES)
                .where(WORKSPACES.ID.eq(workspace)).fetchOne(WORKSPACES.LEDGER_SEQ) ?: throw AccessFailure(true)
            if (page.pin != null && page.pin != version.toString()) readPageChanged()
            afterSnapshotVersionRead()
            val repository = LedgerSnapshotRepository(connection, workspace, database.sqlite)
            val chunk = repository.page(section, page.position)
            current to LedgerSnapshotReadPage(workspace, version.toString(), CASH_RULES_VERSION,
                repository.categoryDirectoryRevision(), chunk)
        }
        afterRead()
        same(principal, reads.currentContext(token))
        return compatibleReadPageJson.encodeToString(result).encodeToByteArray()
    }

}
