package com.finance.tracker.backend

import com.finance.tracker.business.contracts.Role
import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.*

internal class ProjectionStateRepository(private val connection: Connection,private val workspace: String,private val sqlite: Boolean) {
    fun status(): JsonObject=connection.prepareStatement("SELECT availability,projection_version,source_revision,rules_version,last_build_status,active_dataset_id,projection_count,member_count,last_error_code,last_error_summary,last_build_id FROM cash_projection_states WHERE workspace_id=?").use {
        it.setString(1,workspace);it.executeQuery().use {rows ->
            if(!rows.next()) buildJsonObject {
                put("availability","uninitialized");put("projection_version",0);put("source_revision",0);put("rules_version",CASH_PROJECTION_RULES_VERSION)
                put("last_build_status","never");put("active_dataset_id",JsonNull);put("projection_count",0);put("member_count",0)
                put("last_error_code",JsonNull);put("last_error_summary",JsonNull)
            } else buildJsonObject {
                for(field in listOf("availability","rules_version","last_build_status","active_dataset_id","last_error_code","last_error_summary","last_build_id"))
                    put(field,rows.getString(field)?.let(::JsonPrimitive) ?: JsonNull)
                for(field in listOf("projection_version","source_revision","projection_count","member_count")) put(field,rows.getLong(field))
            }
        }
    }
    // The caller holds the workspace lock and the surrounding transaction.
    data class ReadyState(val datasetId:String,val projectionVersion:Long,val sourceRevision:Long)
    fun ready(): Pair<String,Long>?=readyState()?.let {it.datasetId to it.projectionVersion}
    fun readyState(): ReadyState?=connection.prepareStatement("SELECT availability,active_dataset_id,projection_version,source_revision FROM cash_projection_states WHERE workspace_id=?"+(if(sqlite) "" else " FOR UPDATE")).use {
        it.setString(1,workspace);it.executeQuery().use {rows ->
            if(!rows.next() || rows.getString(1)!="ready") null
            else rows.getString(2)?.takeIf {it.isNotEmpty()}?.let {dataset ->ReadyState(dataset,rows.getLong(3),rows.getLong(4))}
        }
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
