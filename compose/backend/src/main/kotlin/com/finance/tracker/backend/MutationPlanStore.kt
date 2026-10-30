package com.finance.tracker.backend

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonObject

/** 维护计划的临时上下文；只保存冻结计划，不写财务事实。 */
internal class MutationPlanStore(
    private val ttlSeconds: Int = 1800,
    clock: () -> Instant = Instant::now,
    private val tokenFactory: (Int) -> String = ::secureUrlToken,
    private val staging: ParsedImportStore = InMemoryParsedImportStore(ttlSeconds, clock, tokenFactory = tokenFactory),
) {
    data class Context(
        val id: String, val workspaceId: String, val userId: String,
        val createdAt: Instant, val expiresAt: Instant, val revision: java.math.BigInteger, val cancelled: Boolean,
        val transactionId: String, val plan: JsonObject?, val previewInput: JsonObject?,
    )

    internal class NotFound : RuntimeException("plan_expired")
    internal class Forbidden : RuntimeException("plan_expired")
    internal class Expired : RuntimeException("plan_expired")
    internal class RevisionConflict : RuntimeException("plan_changed")
    internal class Closed : RuntimeException("plan_changed")

    init { require(ttlSeconds in 1..1800) { "mutation_plan_ttl_invalid" } }

    private fun <T> translate(action: () -> T): T = try { action() }
        catch (_: ParsedImportNotFound) { throw NotFound() }
        catch (_: ParsedImportForbidden) { throw Forbidden() }
        catch (_: ParsedImportExpired) { throw Expired() }
        catch (_: ParsedImportRevisionConflict) { throw RevisionConflict() }
        catch (_: ParsedImportClosed) { throw Closed() }

    private fun context(session: ParsedImportSession): Context {
        if (session.workflowKind != "mutation" || session.files.isNotEmpty()) throw NotFound()
        val data = session.contextData
        if (!session.cancelled && (data.keys != setOf("transaction_id", "version") ||
                data["version"] != 1 || data["transaction_id"] !is String ||
                !(data["transaction_id"] as String).matches(Regex("[1-9][0-9]{0,18}")) ||
                data["transaction_id"].toString().toLongOrNull() == null)) throw MutationPlanFailure("plan_changed")
        return Context(session.sessionId, session.workspaceId, session.userId, session.createdAt, session.expiresAt,
            session.revision, session.cancelled, data["transaction_id"] as? String ?: "",
            session.plan?.let { sourceJson(it) as JsonObject },
            if (session.previewInput.isEmpty()) null else sourceJson(session.previewInput) as JsonObject)
    }

    fun create(workspaceId: String, userId: String, transactionId: String): Context = translate {
        requireOwner(workspaceId, userId)
        require(transactionId.matches(Regex("[1-9][0-9]{0,18}")) && transactionId.toLongOrNull()?.let { it > 0 } == true) {
            "mutation_plan_transaction_invalid"
        }
        context(staging.create(workspaceId, userId, emptyList(), "mutation", mapOf("transaction_id" to transactionId, "version" to 1)))
    }

    fun get(id: String, workspaceId: String, userId: String): Context = translate { context(staging.get(id, workspaceId, userId)) }

    fun replace(id: String, workspaceId: String, userId: String, expectedRevision: Number,
        plan: JsonObject, previewInput: JsonObject): Context = translate {
        context(staging.replace(id, workspaceId, userId, expectedRevision, emptyList(),
            previewInput.mapValues { cashSourceValue(it.value) }, plan.mapValues { cashSourceValue(it.value) })!!)
    }

    fun cancel(id: String, workspaceId: String, userId: String, expectedRevision: Number): Context = translate {
        context(staging.cancel(id, workspaceId, userId, expectedRevision))
    }

    fun planId(contextId: String, planToken: String = tokenFactory(12)): String {
        if (!contextId.matches(Regex("[A-Za-z0-9_-]{43}")) || !planToken.matches(Regex("[A-Za-z0-9_-]{16}")))
            throw IllegalArgumentException("mutation_plan_id_invalid")
        return "mp_$contextId.$planToken"
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
