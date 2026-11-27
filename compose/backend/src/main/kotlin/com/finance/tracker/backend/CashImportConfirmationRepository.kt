package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_IMPORT_CONFIRMATIONS
import com.finance.tracker.backend.persistence.generated.tables.references.CASH_MUTATION_RECEIPTS
import com.finance.tracker.backend.persistence.generated.tables.references.WORKSPACES
import com.finance.tracker.business.contracts.CashImportConfirmation
import com.finance.tracker.business.contracts.CashImportConfirmationRejection
import com.finance.tracker.business.contracts.CashImportConfirmationStatus
import java.sql.Connection

internal class CashImportExecutionGrant(val user: String, val workspace: String, val sessionId: String)
internal data class CashImportOperationKey(val workspace: String, val user: String, val key: String)
internal class CashImportClaimLost : RuntimeException("cash_import_claim_lost")
internal class CashImportCancelNotRecorded : RuntimeException("import_cancel_not_recorded")
internal class CashImportConfirmationMissing : RuntimeException("import_confirmation_not_found")
internal class CashImportClaim(val identity: CashImportOperationKey, val generation: Long)
internal class CashImportReceiptIdentity(val id: String, val digest: String, val kind: String)

internal class CashImportConfirmationRepository(connection: Connection, private val sqlite: Boolean) {
    private val sql = connection.jooq(sqlite)
    private val jobs = CASH_IMPORT_CONFIRMATIONS

    private fun scope(id: CashImportOperationKey) =
        jobs.WORKSPACE_ID.eq(id.workspace).and(jobs.USER_ID.eq(id.user)).and(jobs.OPERATION_KEY.eq(id.key))

    fun receipt(id: CashImportOperationKey): CashImportReceiptIdentity? {
        val receipts = CASH_MUTATION_RECEIPTS
        return sql.select(receipts.ID, receipts.REQUEST_DIGEST, receipts.OPERATION_KIND).from(receipts)
            .where(receipts.WORKSPACE_ID.eq(id.workspace)).and(receipts.USER_ID.eq(id.user))
            .and(receipts.IDEMPOTENCY_KEY.eq(id.key)).fetchOne()?.let {
                CashImportReceiptIdentity(it[receipts.ID]!!, it[receipts.REQUEST_DIGEST]!!, it[receipts.OPERATION_KIND]!!)
            }
    }

    fun digest(id: CashImportOperationKey): String? =
        sql.select(jobs.REQUEST_DIGEST).from(jobs).where(scope(id)).fetchOne(jobs.REQUEST_DIGEST)

    fun view(id: CashImportOperationKey): CashImportConfirmation? {
        val row = sql.select(jobs.STATE, jobs.FAILURE_CODE, jobs.FAILURE_STATUS).from(jobs).where(scope(id)).fetchOne()
        if (row == null || row[jobs.STATE] == "committed") {
            val receipt = receipt(id)?.takeIf { it.kind == "cash_write_set" }
            check(row == null || receipt != null) { "cash_import_receipt_missing" }
            return receipt?.let { CashImportConfirmation(id.key, CashImportConfirmationStatus.COMMITTED, it.id) }
        }
        val rejection = row[jobs.FAILURE_CODE]?.let { CashImportConfirmationRejection(it, row[jobs.FAILURE_STATUS]!!.toInt()) }
        return CashImportConfirmation(id.key, CashImportConfirmationStatus.valueOf(row[jobs.STATE]!!.uppercase()), rejection = rejection)
    }

    fun insert(id: CashImportOperationKey, grant: CashImportExecutionGrant, body: CashWriteSetRequestBody,
        base: String, rules: String, payload: String, now: Long) {
        sql.insertInto(jobs).set(jobs.WORKSPACE_ID, id.workspace).set(jobs.USER_ID, id.user).set(jobs.OPERATION_KEY, id.key)
            .set(jobs.AUTHORIZATION_SESSION_ID, grant.sessionId).set(jobs.REQUEST_DIGEST, body.digest)
            .set(jobs.BASE_LEDGER_SEQ, base).set(jobs.RULES_VERSION, rules).set(jobs.STATE, "accepted")
            .set(jobs.PAYLOAD, payload).set(jobs.ACCEPTED_AT_MS, now)
            .onConflict(jobs.WORKSPACE_ID, jobs.USER_ID, jobs.OPERATION_KEY).doNothing().execute()
    }

    fun next(now: Long): List<CashImportClaim> =
        sql.select(jobs.WORKSPACE_ID, jobs.USER_ID, jobs.OPERATION_KEY, jobs.GENERATION).from(jobs)
            .where(jobs.STATE.eq("accepted").or(jobs.STATE.`in`("running", "cancel_requested").and(jobs.LEASE_UNTIL_MS.le(now))))
            .orderBy(jobs.LEASE_UNTIL_MS.asc().nullsFirst(), jobs.ACCEPTED_AT_MS, jobs.WORKSPACE_ID, jobs.USER_ID, jobs.OPERATION_KEY)
            .limit(32).fetch { row ->
                CashImportClaim(CashImportOperationKey(row[jobs.WORKSPACE_ID]!!, row[jobs.USER_ID]!!, row[jobs.OPERATION_KEY]!!),
                    row[jobs.GENERATION]!!)
            }

    fun tryWorkspace(workspace: String): Boolean {
        val row = sql.select(WORKSPACES.ID).from(WORKSPACES).where(WORKSPACES.ID.eq(workspace))
        return (if (sqlite) row else row.forUpdate().skipLocked()).fetchOne() != null
    }

    fun claim(candidate: CashImportClaim, now: Long, lease: Long): CashImportClaim? {
        val id = candidate.identity
        val generation = Math.incrementExact(candidate.generation)
        val count = sql.update(jobs).set(jobs.STATE, "running").set(jobs.GENERATION, generation).set(jobs.LEASE_UNTIL_MS, lease)
            .where(scope(id)).and(jobs.GENERATION.eq(candidate.generation))
            .and(jobs.STATE.eq("accepted").or(jobs.STATE.eq("running").and(jobs.LEASE_UNTIL_MS.le(now)))).execute()
        return if (count == 1) CashImportClaim(id, generation) else null
    }

    fun load(claim: CashImportClaim): Pair<CashImportExecutionGrant, CashWriteSetRequestBody>? =
        sql.select(jobs.AUTHORIZATION_SESSION_ID, jobs.REQUEST_DIGEST, jobs.PAYLOAD).from(jobs)
            .where(scope(claim.identity)).and(jobs.GENERATION.eq(claim.generation)).and(jobs.STATE.eq("running")).fetchOne()?.let {
                val grant = CashImportExecutionGrant(claim.identity.user, claim.identity.workspace, it[jobs.AUTHORIZATION_SESSION_ID]!!)
                val body = kotlinx.serialization.json.Json.parseToJsonElement(it[jobs.PAYLOAD]!!) as kotlinx.serialization.json.JsonObject
                grant to CashWriteSetRequestBody(body, it[jobs.REQUEST_DIGEST]!!)
            }

    fun verify(claim: CashImportClaim, now: Long) {
        val active = sql.fetchExists(sql.selectOne().from(jobs).where(scope(claim.identity))
            .and(jobs.GENERATION.eq(claim.generation)).and(jobs.STATE.eq("running")).and(jobs.LEASE_UNTIL_MS.gt(now)))
        if (!active) throw CashImportClaimLost()
    }

    fun finish(claim: CashImportClaim, state: String, expected: String = "running", rejection: CashImportConfirmationRejection? = null): Boolean =
        sql.update(jobs).set(jobs.STATE, state).setNull(jobs.PAYLOAD).setNull(jobs.LEASE_UNTIL_MS)
            .set(jobs.FAILURE_CODE, rejection?.code).set(jobs.FAILURE_STATUS, rejection?.httpStatus?.toLong())
            .where(scope(claim.identity)).and(jobs.GENERATION.eq(claim.generation)).and(jobs.STATE.eq(expected)).execute() == 1

    fun backOff(claim: CashImportClaim, until: Long) {
        sql.update(jobs).set(jobs.LEASE_UNTIL_MS, until).where(scope(claim.identity))
            .and(jobs.GENERATION.eq(claim.generation)).and(jobs.STATE.eq("running")).execute()
    }

    fun requestCancel(id: CashImportOperationKey, current: CashImportConfirmation): CashImportConfirmation {
        val state = when (current.status) {
            CashImportConfirmationStatus.ACCEPTED -> "accepted"
            CashImportConfirmationStatus.RUNNING -> "running"
            else -> return current
        }
        val update = if (state == "accepted") sql.update(jobs).set(jobs.STATE, "cancelled").setNull(jobs.PAYLOAD)
            else sql.update(jobs).set(jobs.STATE, "cancel_requested")
        if (update.where(scope(id)).and(jobs.STATE.eq(state)).execute() != 1) throw CashImportCancelNotRecorded()
        return checkNotNull(view(id))
    }
}
