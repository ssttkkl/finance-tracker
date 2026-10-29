package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.Role
import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.*

internal class ProjectionStateRepository(private val connection: Connection,private val workspace: String,private val sqlite: Boolean) {
    private val sql = connection.jooq(sqlite)
    private val state = CASH_PROJECTION_STATES
    fun status(): JsonObject {
        val row = sql.select(state.AVAILABILITY, state.PROJECTION_VERSION, state.SOURCE_REVISION, state.RULES_VERSION, state.LAST_BUILD_STATUS,
                state.ACTIVE_DATASET_ID, state.PROJECTION_COUNT, state.MEMBER_COUNT, state.LAST_ERROR_CODE, state.LAST_ERROR_SUMMARY, state.LAST_BUILD_ID)
            .from(state).where(state.WORKSPACE_ID.eq(workspace)).fetchOne()
        return if (row == null) buildJsonObject {
            put("availability", "uninitialized"); put("projection_version", 0); put("source_revision", 0); put("rules_version", CASH_PROJECTION_RULES_VERSION)
            put("last_build_status", "never"); put("active_dataset_id", JsonNull); put("projection_count", 0); put("member_count", 0)
            put("last_error_code", JsonNull); put("last_error_summary", JsonNull)
        } else buildJsonObject {
            for (field in listOf(state.AVAILABILITY, state.RULES_VERSION, state.LAST_BUILD_STATUS, state.ACTIVE_DATASET_ID,
                    state.LAST_ERROR_CODE, state.LAST_ERROR_SUMMARY, state.LAST_BUILD_ID)) put(field.name, row[field]?.let(::JsonPrimitive) ?: JsonNull)
            for (field in listOf(state.PROJECTION_VERSION, state.SOURCE_REVISION, state.PROJECTION_COUNT, state.MEMBER_COUNT)) put(field.name, row[field]!!)
        }
    }
    // The caller holds the workspace lock and the surrounding transaction.
    data class ReadyState(val datasetId:String,val projectionVersion:Long,val sourceRevision:Long)
    fun ready(): Pair<String,Long>?=readyState()?.let {it.datasetId to it.projectionVersion}
    fun readyState(): ReadyState? {
        val query = sql.select(state.AVAILABILITY, state.ACTIVE_DATASET_ID, state.PROJECTION_VERSION, state.SOURCE_REVISION)
            .from(state).where(state.WORKSPACE_ID.eq(workspace))
        val row = (if (sqlite) query else query.forUpdate()).fetchOne() ?: return null
        if (row[state.AVAILABILITY] != "ready") return null
        return row[state.ACTIVE_DATASET_ID]?.takeIf { it.isNotEmpty() }?.let { ReadyState(it, row[state.PROJECTION_VERSION]!!, row[state.SOURCE_REVISION]!!) }
    }
}

class ProjectionStatusFailure(val forbidden: Boolean=false): RuntimeException("projection_status_unavailable")

class ProjectionStatusService(private val database: Database,private val now: ()->Instant=Instant::now,private val beforeRead: ()->Unit={}) {
    fun status(token: String?): JsonObject {
        try {
            beforeRead()
            return database.read {c ->
                val reads=ReadServices(database,now);val principal=reads.context(c,token,write=false)
                if(principal.role!=Role.ADMIN) throw ProjectionStatusFailure(true)
                val value=ProjectionStateRepository(c,principal.workspaceId!!,database.sqlite).status()
                val current=reads.context(c,token,write=false)
                if(current.workspaceId!=principal.workspaceId || current.userId!=principal.userId || current.role!=Role.ADMIN) throw ProjectionStatusFailure(true)
                value
            }
        } catch(failure: AccessFailure) {throw failure}
        catch(failure: ProjectionStatusFailure) {throw failure}
        catch(_: Exception) {throw ProjectionStatusFailure()}
    }
}
