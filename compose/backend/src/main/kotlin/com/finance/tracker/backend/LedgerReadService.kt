package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.WORKSPACES
import com.finance.tracker.business.contracts.LedgerVersionDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
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

}
