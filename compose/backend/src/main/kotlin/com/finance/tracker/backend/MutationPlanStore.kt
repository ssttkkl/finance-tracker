package com.finance.tracker.backend

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonObject

/** 维护计划的临时上下文；只保存冻结计划，不写财务事实。 */
internal class MutationPlanStore(
    private val ttlSeconds: Int = 1800,
    private val clock: () -> Instant = Instant::now,
    private val tokenFactory: (Int) -> String = ::secureUrlToken,
) {
    data class Context(
        val id: String, val workspaceId: String, val userId: String,
        val createdAt: Instant, val expiresAt: Instant, val revision: Long, val cancelled: Boolean,
        val transactionId: String, val plan: JsonObject?, val previewInput: JsonObject?,
    )

    internal class NotFound : RuntimeException("plan_expired")
    internal class Forbidden : RuntimeException("plan_expired")
    internal class Expired : RuntimeException("plan_expired")
    internal class RevisionConflict : RuntimeException("plan_changed")
    internal class Closed : RuntimeException("plan_changed")

    private val lock = Any()
    private val contexts = linkedMapOf<String, Context>()

    init {
        if (ttlSeconds !in 1..1800) throw IllegalArgumentException("mutation_plan_ttl_invalid")
    }

    fun create(workspaceId: String, userId: String, transactionId: String): Context = synchronized(lock) {
        requireOwner(workspaceId, userId)
        require(transactionId.matches(Regex("[1-9][0-9]{0,18}")) && transactionId.toLongOrNull()?.let { it > 0 } == true) {
            "mutation_plan_transaction_invalid"
        }
        val now = clock()
        var id: String
        do { id = tokenFactory(32) } while (id.length != 43 || id in contexts)
        val context = Context(id, workspaceId, userId, now, now.plusSeconds(ttlSeconds.toLong()), 1, false,
            transactionId, null, null)
        contexts[id] = context
        context
    }

    fun get(id: String, workspaceId: String, userId: String): Context = synchronized(lock) {
        val context = readValid(id)
        if (context.workspaceId != workspaceId || context.userId != userId) throw Forbidden()
        context
    }

    fun replace(id: String, workspaceId: String, userId: String, expectedRevision: Long,
        plan: JsonObject, previewInput: JsonObject): Context = synchronized(lock) {
        val current = readValid(id)
        if (current.workspaceId != workspaceId || current.userId != userId) throw Forbidden()
        if (expectedRevision != current.revision) throw RevisionConflict()
        if (current.cancelled) throw Closed()
        val updated = current.copy(revision = current.revision + 1, plan = plan, previewInput = previewInput)
        contexts[id] = updated
        updated
    }

    /** 原实现重复取消是幂等的；已取消上下文不再推进修订。 */
    fun cancel(id: String, workspaceId: String, userId: String, expectedRevision: Long): Context = synchronized(lock) {
        val current = readValid(id)
        if (current.workspaceId != workspaceId || current.userId != userId) throw Forbidden()
        if (expectedRevision != current.revision) throw RevisionConflict()
        if (current.cancelled) return current
        val updated = current.copy(revision = current.revision + 1, cancelled = true, plan = null, previewInput = null)
        contexts[id] = updated
        updated
    }

    fun planId(contextId: String, planToken: String = tokenFactory(12)): String {
        if (!contextId.matches(Regex("[A-Za-z0-9_-]{43}")) || !planToken.matches(Regex("[A-Za-z0-9_-]{16}")))
            throw IllegalArgumentException("mutation_plan_id_invalid")
        return "mp_$contextId.$planToken"
    }

    private fun readValid(id: String): Context {
        if (!id.matches(Regex("[A-Za-z0-9_-]{43}"))) throw NotFound()
        val context = contexts[id] ?: throw NotFound()
        if (!clock().isBefore(context.expiresAt)) {
            contexts.remove(id)
            throw Expired()
        }
        return context
    }

    private fun requireOwner(workspaceId: String, userId: String) {
        if (workspaceId.isEmpty() || workspaceId.length > 128 || userId.isEmpty() || userId.length > 128)
            throw IllegalArgumentException("mutation_plan_owner_invalid")
    }

    companion object {
        private val random = SecureRandom()

        internal fun secureUrlToken(bytes: Int): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

        /** `mp_<43-char context>.<16-char token>`；其它形式一律 plan_not_found。 */
        internal fun contextId(planId: Any?): String {
            val match = (planId as? String)?.let { Regex("mp_([A-Za-z0-9_-]{43})\\.([A-Za-z0-9_-]{16})").matchEntire(it) }
                ?: throw MutationPlanFailure("plan_not_found")
            return match.groupValues[1]
        }

        internal fun ttlFromEnvironment(value: String?): Int {
            val ttl = if (value == null) 1800 else value.toIntOrNull()
                ?: throw IllegalArgumentException("FT_IMPORT_STAGING_TTL_SECONDS is invalid")
            if (ttl !in 1..1800) throw IllegalArgumentException("FT_IMPORT_STAGING_TTL_SECONDS must be between 1 and 1800")
            return ttl
        }
    }
}
