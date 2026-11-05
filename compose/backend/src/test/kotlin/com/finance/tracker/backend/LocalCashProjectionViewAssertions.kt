package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_PROJECTIONS
import com.finance.tracker.business.contracts.CashProjectionDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.buildLocalCashProjectionViews
import java.sql.Connection
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals

/** Compare every existing public field; the new read version deliberately uses ledger_seq. */
internal fun assertLocalCashProjectionViews(connection: Connection, database: Database, workspace: String, snapshot: LedgerSnapshotDto) {
    val (dataset, publishedVersion) = requireNotNull(ProjectionStateRepository(connection, workspace, database.sqlite).ready())
    val table = CASH_PROJECTIONS
    val renderer = CurrentProjectionReadRepository(connection, workspace, database.sqlite) {}
    val native = connection.jooq(database.sqlite).selectFrom(table)
        .where(table.WORKSPACE_ID.eq(workspace)).and(table.DATASET_ID.eq(dataset)).fetch().map { record ->
            Json.decodeFromJsonElement(CashProjectionDto.serializer(), renderer.listItem(record, dataset, publishedVersion))
                .copy(projectionVersion = snapshot.ledgerSeq.toLong())
        }.associateBy { it.projectionId }
    val local = buildLocalCashProjectionViews(snapshot).associateBy { it.projectionId }
    assertEquals(native, local, "complete ledger read model with explicit ledger version adaptation")
}
