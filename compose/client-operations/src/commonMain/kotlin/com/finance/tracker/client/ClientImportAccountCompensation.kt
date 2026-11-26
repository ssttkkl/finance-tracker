package com.finance.tracker.client

import com.finance.tracker.business.contracts.AccountCommandViewDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ImportAccountEffect(
    val key: String,
    val order: Long,
    val before: AccountCommandViewDto?,
    val after: AccountCommandViewDto,
)

data class ImportAccountEffectChain(
    val effects: List<ImportAccountEffect>,
    val revision: Long,
    val blocked: Boolean,
)

enum class AccountCompensationKind { DEACTIVATE, DELETE, RESTORE }

data class AccountCompensationAction(
    val kind: AccountCompensationKind,
    val current: AccountCommandViewDto,
    val patch: JsonObject? = null,
)

interface ImportAccountCompensationHost {
    fun chains(): Map<String, ImportAccountEffectChain>
    fun hasUnresolvedWrite(accountId: String): Boolean
    suspend fun requireActive()
    fun canSubmit(): Boolean
    suspend fun read(accountId: String): AccountCommandViewDto
    suspend fun apply(effect: ImportAccountEffect, action: AccountCompensationAction): Boolean
    fun fail(effect: ImportAccountEffect, code: String, skipped: Boolean)
    fun succeeded(effect: ImportAccountEffect, complete: Boolean)
}

/** Reverse each continuous account chain in order, using the current revision for every write. */
suspend fun compensateImportAccounts(host: ImportAccountCompensationHost) {
    val paused = mutableSetOf<String>()
    while (true) {
        host.requireActive()
        val next = host.chains().entries.filter { (id, chain) -> chain.effects.isNotEmpty() && id !in paused }
            .maxByOrNull { it.value.effects.last().order } ?: break
        val (id, chain) = next
        val effect = chain.effects.last()
        try {
            if (host.hasUnresolvedWrite(id)) throw ClientOperationFailure("account_result_unknown")
            if (chain.blocked) throw ClientOperationFailure("account_changed")
            val current = host.read(id)
            host.requireActive()
            if (!host.canSubmit()) {
                host.fail(effect, "network_unavailable", false)
                return
            }
            if (current.accountId != id || current.deleted || current.accountRevision != chain.revision)
                throw ClientOperationFailure("account_changed")
            val action = compensationAction(effect, current)
            if (!host.apply(effect, action)) paused += id
            else host.succeeded(effect, action.kind != AccountCompensationKind.DEACTIVATE)
        } catch (failure: ClientOperationFailure) {
            host.fail(effect, failure.code, false)
            paused += id
        }
    }
    for ((id, chain) in host.chains()) {
        if (id in paused) chain.effects.dropLast(1).asReversed().forEach { host.fail(it, "account_changed", true) }
    }
}

private fun compensationAction(effect: ImportAccountEffect, current: AccountCommandViewDto): AccountCompensationAction {
    val before = effect.before
    if (before == null && !current.active) {
        if (current.capabilities["can_delete"] != true) throw ClientOperationFailure("account_in_use")
        return AccountCompensationAction(AccountCompensationKind.DELETE, current)
    }
    if (current.capabilities["can_update"] != true) throw ClientOperationFailure("workspace_forbidden", 403)
    if (before == null) return AccountCompensationAction(AccountCompensationKind.DEACTIVATE, current,
        buildJsonObject { put("active", false) })
    val after = effect.after
    return AccountCompensationAction(AccountCompensationKind.RESTORE, current, buildJsonObject {
        if (before.name != after.name) put("name", before.name)
        if (before.active != after.active) put("active", before.active)
        if (before.currencies != after.currencies) put("currencies", JsonArray(before.currencies.map(::JsonPrimitive)))
    })
}
