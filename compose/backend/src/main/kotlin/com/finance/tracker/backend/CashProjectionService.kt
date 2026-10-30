package com.finance.tracker.backend

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

internal class CashProjectionService(private val database:Database,private val workspace:String,
    private val now:()->Instant=Instant::now,private val beforePublish:(Connection)->Unit={},private val beforeFailedBuild:()->Unit={},
    private val authorize:(Connection)->Unit={},private val afterPublish:(Connection)->Unit={}) {
    fun status():JsonObject=database.read {ProjectionStateRepository(it,workspace,database.sqlite).status()}

    fun rebuild():JsonObject {
        var expected:CashProjectionFailureContext?=null
        try {
            return database.write {c ->
                val repository=CashProjectionRepository(CashSqlContext(c,workspace,database.sqlite),now)
                expected=repository.rebuildFailureContext()
                authorize(c)
                val result=publish(repository) {beforePublish(c)}
                afterPublish(c)
                authorize(c)
                result
            }
        } catch(failure:StorageFailure) {throw failure}
        catch(failure:AccessFailure) {throw failure}
        catch(failure:ProjectionStatusFailure) {throw failure}
        catch(failure:Exception) {
            expected?.let {context ->
                try {
                    beforeFailedBuild()
                    database.write {CashProjectionRepository(CashSqlContext(it,workspace,database.sqlite),now).recordFailedBuild(context,failureCode(failure))}
                } catch(_:Exception) {logger.warn("无法写入收支投影失败诊断")}
            }
            if(failure is CashProjectionFailure || failure is RuntimeException && failure.message.orEmpty().startsWith("projection.")) throw failure
            throw CashProjectionFailure("projection.failed")
        }
    }

    companion object {
        private val logger=LoggerFactory.getLogger(CashProjectionService::class.java)
        private fun failureCode(failure:Exception):String=when {
            failure is CashProjectionFailure ->failure.code
            failure is RuntimeException && failure.message.orEmpty().startsWith("projection.") ->failure.message!!
            else ->"projection.failed"
        }
        private fun publish(repository:CashProjectionRepository,beforePublish:()->Unit={}):JsonObject {
            val sources=repository.readValidatedSources()
            val build=buildCashProjections(sources.facts,sources.relations)
            val dataset=repository.createStagingDataset(sources.digest,CASH_PROJECTION_RULES_VERSION)
            val version=Math.addExact(repository.status().getValue("projection_version").jsonPrimitive.long,1)
            repository.replaceDataset(dataset,build,version)
            beforePublish()
            return repository.publishDataset(dataset,sources.digest,CASH_PROJECTION_RULES_VERSION,sources)
        }
        fun initializeInSession(context:CashSqlContext,now:()->Instant=context.now):JsonObject {
            val repository=CashProjectionRepository(context,now)
            repository.rebuildFailureContext()
            val state=repository.status()
            if(state.getValue("availability").jsonPrimitive.content=="ready" && state.getValue("active_dataset_id").let {it!=JsonNull && it.jsonPrimitive.content.isNotEmpty()}) return state
            return publish(repository)
        }
        fun maintainInSession(context:CashSqlContext,affectedFactIds:Set<Long>,lockedState:CashLockedProjectionState?=null):JsonObject {
            val repository=CashProjectionRepository(context);val state=lockedState ?: repository.requireReadyStateLock();state.requireFor(context)
            val ids=repository.sources.affectedTransactionClosure(affectedFactIds,state.datasetId)
            val sources=repository.sources.readSourcesForFacts(ids);val build=buildCashProjections(sources.facts,sources.relations)
            return repository.replaceActiveComponents(build,ids,state)
        }
        fun maintainIfReadyInSession(context:CashSqlContext,affectedFactIds:Set<Long>,newFactIds:Set<Long> = emptySet(),knownComponentIds:Set<Long>?=null):JsonObject? {
            val repository=CashProjectionRepository(context);val state=repository.readyStateLockOrNone()
            if(state==null) {
                val ids=repository.sources.confirmedTransactionClosure(affectedFactIds);val sources=repository.sources.readSourcesForFacts(ids);val build=buildCashProjections(sources.facts,sources.relations)
                context.entries.removeMembers(affectedFactIds-build.projections.flatMap {p ->p.storedMemberIds()}.toSet());context.entries.reconcile(build.projections);return null
            }
            var ids=repository.sources.affectedTransactionClosure(affectedFactIds,state.datasetId)
            if(knownComponentIds!=null) ids=repository.sources.affectedTransactionClosure(ids+repository.sources.parentIdsForComponents(knownComponentIds),state.datasetId)
            val sources=repository.sources.readSourcesForFacts(ids);val build=buildCashProjections(sources.facts,sources.relations)
            return repository.replaceActiveComponents(build,ids,state,newFactIds)
        }
        fun removeIfReadyInSession(context:CashSqlContext,affectedFactIds:Set<Long>):JsonObject? {
            val repository=CashProjectionRepository(context);val state=repository.readyStateLockOrNone()
            if(state==null) {context.entries.removeMembers(affectedFactIds);return null}
            return repository.removeActiveComponents(affectedFactIds,state)
        }
        fun maintainStandaloneFactIfReadyInSession(context:CashSqlContext,row:CashFactSqlRow):JsonObject? {
            val repository=CashProjectionRepository(context);val build=buildCashProjections(listOf(repository.factFromRow(row)),emptyList())
            context.entries.reconcile(build.projections);val state=repository.readyStateLockOrNone() ?: return null
            return repository.replaceActiveComponents(build,setOf(row.id),state,knownNewFactIds=setOf(row.id))
        }
        fun replaceStandaloneFactIfReadyInSession(context:CashSqlContext,row:CashFactSqlRow):JsonObject? {
            val repository=CashProjectionRepository(context);val state=repository.readyStateLockOrNone() ?: return null
            return repository.replaceStandaloneFact(row,state)
        }
        fun refreshDisplayFieldsIfReadyInSession(context:CashSqlContext,factId:Long,counterparty:String,note:String):JsonObject? {
            val repository=CashProjectionRepository(context);val state=repository.readyStateLockOrNone() ?: return null
            return repository.refreshDisplayFields(factId,counterparty,note,state)
        }
    }
}
