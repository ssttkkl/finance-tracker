package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_SOURCE_BINDINGS
import kotlinx.serialization.json.JsonElement

/** Binding DML only; callers validate identity, owner, revisions and document evidence first. */
internal class CashSourceBindingValueWriter(private val context: CashSqlContext) {
    fun save(parentId: Long, key: SourceBindingKey, parserVersion: String, coverage: JsonElement,
        evidenceKey: String?, oldId: Long?): Pair<Long, Boolean> {
        context.requireActive()
        val table = CASH_SOURCE_BINDINGS
        val sql = context.sql
        val old = oldId?.let { sql.selectFrom(table).where(table.WORKSPACE_ID.eq(context.workspace).and(table.ID.eq(it))).fetchSingle() }
        val evidence = if (key.scopeKind == "account" && evidenceKey != null) evidenceKey else old?.get(table.STABLE_EVIDENCE_KEY)
        if (old == null) {
            context.connection.markLedgerChanged(context.workspace)
            val id = sql.insertInto(table).set(table.WORKSPACE_ID, context.workspace).set(table.TRANSACTION_ID, parentId)
                .set(table.SOURCE_TYPE, key.sourceType).set(table.SOURCE_SCOPE_KIND, key.scopeKind).set(table.SOURCE_SCOPE_KEY, key.scopeKey)
                .set(table.IDENTITY_KIND, key.identityKind).set(table.IDENTITY_KEY, key.identityKey).set(table.IDENTITY_STRENGTH, key.identityStrength)
                .set(table.STABLE_EVIDENCE_KEY, if (key.scopeKind == "account") evidenceKey else null)
                .set(table.STRATEGY_VERSION, parserVersion).set(table.COVERAGE, coverage).set(table.REVISION, 1L)
                .set(table.CREATED_AT, context.now()).set(table.UPDATED_AT, context.now()).returningResult(table.ID).fetchSingle().value1()!!
            return id to true
        }
        if (old[table.TRANSACTION_ID] == parentId && old[table.IDENTITY_STRENGTH] == key.identityStrength && old[table.STRATEGY_VERSION] == parserVersion &&
            old[table.STABLE_EVIDENCE_KEY] == evidence && CashRecordPolicy.valuesEqual(cashSourceValue(old[table.COVERAGE]), cashSourceValue(coverage))) return oldId to false
        context.connection.markLedgerChanged(context.workspace)
        sql.update(table).set(table.TRANSACTION_ID, parentId).set(table.IDENTITY_STRENGTH, key.identityStrength)
            .set(table.STRATEGY_VERSION, parserVersion).set(table.COVERAGE, coverage).set(table.STABLE_EVIDENCE_KEY, evidence)
            .set(table.REVISION, Math.addExact(old[table.REVISION]!!, 1L)).set(table.UPDATED_AT, context.now())
            .where(table.WORKSPACE_ID.eq(context.workspace).and(table.ID.eq(oldId))).execute()
        return oldId to true
    }
}
