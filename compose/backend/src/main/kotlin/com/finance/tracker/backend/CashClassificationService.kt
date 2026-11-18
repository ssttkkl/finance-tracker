package com.finance.tracker.backend

import java.time.Instant
import kotlinx.serialization.json.*
import com.finance.tracker.backend.persistence.generated.tables.references.*

class CashClassificationService(private val database:Database,private val now:()->Instant=Instant::now,
    private val beforeWrite:()->Unit={},private val beforeWorkspaceLock:()->Unit={}) {
    private val reads=ReadServices(database,now)
    private fun iterable(value:JsonElement):List<JsonElement> = when(value) {
        is JsonArray ->value
        is JsonObject ->value.keys.map(::JsonPrimitive)
        is JsonPrimitive ->if(value.isString) value.content.codePoints().toArray().map {JsonPrimitive(String(Character.toChars(it)))} else error("value is not iterable")
        else ->error("value is not iterable")
    }
    internal fun setCurrentCategory(token: String?, projectionIds: JsonElement, version: JsonElement?, category: JsonElement?,
        versionRepresentation: String?, requestWorkspace: String?): JsonObject {
        val ids = iterable(projectionIds).map { pythonString(it).trim { c -> c.code in pythonWhitespace } }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) throw CategoryFailure("category.projections_required")
        val entries = ids.map { value ->
            if (!value.startsWith("cash-entry:") || value.length == "cash-entry:".length) throw CategoryFailure("projection.version_conflict")
            value.removePrefix("cash-entry:")
        }
        check(version != null && version != JsonNull) { "projection version is not integer-compatible" }
        val parsed = pythonIntegerValue(version, versionRepresentation)
        if (parsed.signum() < 0 || parsed > java.math.BigInteger.valueOf(Long.MAX_VALUE)) throw CategoryFailure("projection.version_conflict")
        return setEntryCategory(token, entries, parsed.toLong(), category?.takeIf { it != JsonNull }?.let(::pythonString), requestWorkspace)
    }

    /** Canonical command used by current ledger classification; no materialized browsing state is required. */
    internal fun setEntryCategory(token: String?, entryIds: List<String>, ledgerSeq: Long, categoryId: String?,
        requestWorkspace: String? = null): JsonObject {
        val ids = entryIds.distinct()
        if (ids.isEmpty()) throw CategoryFailure("category.projections_required")
        if (ledgerSeq < 0) throw CategoryFailure("projection.version_conflict")
        beforeWrite()
        return database.write { connection ->
            val workspace = requestWorkspace ?: reads.session(connection, token).workspaceId ?: throw AccessFailure(true)
            beforeWorkspaceLock()
            if (!connection.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
            reads.lockedContext(connection, token, workspace, write = true)
            try { connection.requireLedgerBase(workspace, ledgerSeq) }
            catch (_: LedgerBaseChanged) { throw CategoryFailure("projection.version_conflict") }
            val sql = connection.jooq(database.sqlite)
            if (categoryId != null && !sql.fetchExists(CASH_CATEGORIES,
                    CASH_CATEGORIES.WORKSPACE_ID.eq(workspace).and(CASH_CATEGORIES.ID.eq(categoryId))))
                throw CategoryFailure("category.not_found")
            val selected = try { CashEntryRepository(connection, workspace, database.sqlite).setCategories(ids, categoryId) }
            catch (failure: CashPolicyError) {
                if (failure.code != "entry_changed") throw failure
                throw CategoryFailure("projection.version_conflict")
            }
            val version = sql.select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES).where(WORKSPACES.ID.eq(workspace))
                .fetchSingle(WORKSPACES.LEDGER_SEQ)!!
            buildJsonObject {
                put("projection_version", version); put("projection_count", selected.first)
                put("updated_transaction_count", selected.second); put("category_id", categoryId?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
    }
}
