package com.finance.tracker.backend

import com.finance.tracker.business.contracts.Role
import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

class ProjectionRebuildFailure(val code:String,val domain:Boolean):RuntimeException(code)

class ProjectionRebuildService(private val database:Database,private val now:()->Instant=Instant::now,
    private val afterHint:()->Unit={},private val beforePublish:(Connection)->Unit={},private val beforeReturn:(Connection)->Unit={}) {
    fun rebuild(token:String?):JsonObject {
        try {
            val reads=ReadServices(database,now)
            val hint=reads.requireContext(token)
            if(hint.role!=Role.ADMIN) throw ProjectionStatusFailure(true)
            afterHint()
            return CashProjectionService(database,hint.workspaceId!!,now,beforePublish=beforePublish,authorize={c ->
                val current=reads.lockedContext(c,token,hint.workspaceId,write=false)
                if(current.userId!=hint.userId || current.role!=Role.ADMIN) throw ProjectionStatusFailure(true)
            },afterPublish=beforeReturn).rebuild()
        } catch(failure:AccessFailure) {throw failure}
        catch(failure:ProjectionStatusFailure) {throw failure}
        catch(failure:CashProjectionFailure) {throw ProjectionRebuildFailure(failure.code,failure is CashProjectionDomainFailure)}
        catch(failure:StorageFailure) {
            val label=when(failure.code) {
                "storage.config" ->"storage configuration is invalid"
                "storage.connect" ->"unable to connect to selected storage"
                "storage.schema" ->"database schema is not initialized or current"
                "storage.workspace" ->"workspace does not exist"
                "storage.readonly" ->"selected storage is read-only"
                "storage.busy" ->"selected storage is busy; retry after other writes complete"
                else ->"storage operation failed"
            }
            throw ProjectionRebuildFailure("${failure.code}: $label (${if(database.sqlite) "file SQLite" else "postgresql"})",false)
        }
        catch(failure:RuntimeException) {throw ProjectionRebuildFailure(failure.message?.takeIf {it.startsWith("projection.")} ?: "projection.failed",false)}
    }
}
