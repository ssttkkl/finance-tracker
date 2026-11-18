package com.finance.tracker.backend

import java.math.BigInteger
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.*

internal data class ParsedImportSweep(val scanned: Int, val deleted: Int, val nextCursor: String?)
internal data class ParsedImportObject(val content: ByteArray, val etag: String)

internal abstract class ParsedImportStore(
    private val ttlSeconds: Int = 1800,
    clock: () -> Instant = Instant::now,
    protected val codec: ParsedImportCodec = ParsedImportCodec(),
    private val tokenFactory: (Int) -> String = MutationPlanStore::secureUrlToken,
) {
    protected val clock: () -> Instant = { clock().truncatedTo(java.time.temporal.ChronoUnit.MICROS) }
    init { require(ttlSeconds in 1..1800) { "import_session_storage_config_invalid" } }
    protected abstract fun readRaw(id: String): ParsedImportObject
    protected abstract fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant)
    protected abstract fun deleteRaw(id: String)
    abstract fun sweepExpired(cursor: String? = null, limit: Int = 100, shouldStop: () -> Boolean = { false }): ParsedImportSweep

    fun validateFile(file: ParsedImportFile) = codec.validateFile(file)

    fun create(workspaceId: String, userId: String, files: List<ParsedImportFile>, workflowKind: String = "import",
        contextData: Map<String, Any?> = emptyMap(), createdAt: Instant? = null, expiresAt: Instant? = null,
        previewInput: Map<String, Any?> = emptyMap()): ParsedImportSession {
        if (listOf(workspaceId, userId).any { it.isEmpty() || it.codePointCount(0, it.length) > 128 })
            throw ParsedImportDataError("import_session_owner_invalid")
        if (files.isEmpty() && workflowKind != "mutation") throw ParsedImportDataError("import_session_file_count_invalid")
        val now = clock()
        if ((createdAt == null) != (expiresAt == null)) throw ParsedImportDataError("import_session_lifetime_invalid")
        val created = createdAt ?: now
        val expires = expiresAt ?: now.plusSeconds(ttlSeconds.toLong())
        val duration = Duration.between(created, expires)
        if (created > now || expires <= now || duration.isNegative || duration.isZero || duration > Duration.ofSeconds(1800))
            throw ParsedImportDataError("import_session_lifetime_invalid")
        repeat(3) {
            val session = ParsedImportSession(tokenFactory(32), workspaceId, userId, created, expires, BigInteger.ONE, files,
                previewInput, workflowKind = workflowKind, contextData = contextData)
            val content = codec.encode(session)
            val decoded = codec.roundTrip(session.sessionId, content)
            try { publish(session, content, null); return decoded }
            catch (_: ParsedImportRevisionConflict) { /* Only a definitive key collision permits retry. */ }
        }
        throw ParsedImportStorageUnavailable()
    }

    private fun revision(value: Number): BigInteger = when (value) {
        is Byte, is Short, is Int, is Long, is BigInteger -> BigInteger(value.toString())
        else -> throw ParsedImportRevisionConflict()
    }
    private fun validId(id: String) { if (!id.matches(Regex("[A-Za-z0-9_-]{43}"))) throw ParsedImportNotFound() }
    private fun authorize(session: ParsedImportSession, workspace: String, user: String) {
        if (session.workspaceId != workspace || session.userId != user) throw ParsedImportForbidden()
        if (clock() >= session.expiresAt) { deleteRaw(session.sessionId); throw ParsedImportExpired() }
    }
    private fun read(id: String, workspace: String, user: String): Pair<ParsedImportSession, ParsedImportObject> {
        validId(id)
        val stored = readRaw(id)
        val session = codec.decode(id, stored.content)
        authorize(session, workspace, user)
        return session to stored
    }
    fun get(id: String, workspaceId: String, userId: String): ParsedImportSession = read(id, workspaceId, userId).first

    fun replace(id: String, workspaceId: String, userId: String, expectedRevision: Number, files: List<ParsedImportFile>,
        previewInput: Map<String, Any?>, plan: Map<String, Any?>?, contextData: Map<String, Any?>? = null,
        returnSession: Boolean = true): ParsedImportSession? {
        val (current, stored) = read(id, workspaceId, userId)
        if (current.revision != revision(expectedRevision)) throw ParsedImportRevisionConflict()
        if (current.cancelled) throw ParsedImportClosed()
        val updated = current.copy(revision = current.revision + BigInteger.ONE, files = files, previewInput = previewInput,
            plan = plan, contextData = contextData ?: current.contextData)
        val content = codec.encode(updated)
        val decoded = if (returnSession) codec.roundTrip(id, content) else null
        publish(updated, content, stored.etag)
        return decoded
    }

    fun cancel(id: String, workspaceId: String, userId: String, expectedRevision: Number): ParsedImportSession {
        val (current, stored) = read(id, workspaceId, userId)
        if (current.revision != revision(expectedRevision)) throw ParsedImportRevisionConflict()
        if (current.cancelled) return current
        val updated = current.copy(revision = current.revision + BigInteger.ONE, files = emptyList(), previewInput = emptyMap(),
            plan = null, cancelled = true, contextData = emptyMap())
        val content = codec.encode(updated)
        val decoded = codec.roundTrip(id, content)
        publish(updated, content, stored.etag)
        return decoded
    }

    private fun publish(session: ParsedImportSession, content: ByteArray, expected: String?) {
        if (clock() >= session.expiresAt) throw ParsedImportExpired()
        writeRaw(session.sessionId, content, expected, session.expiresAt)
        if (clock() >= session.expiresAt) { deleteRaw(session.sessionId); throw ParsedImportExpired() }
    }

    protected fun sweepArgs(cursor: String?, limit: Int) {
        if (limit !in 1..200 || cursor != null && cursor.codePointCount(0, cursor.length) !in 1..4096)
            throw ParsedImportDataError("import_session_sweep_invalid")
    }
    companion object {
        internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

internal class InMemoryParsedImportStore(ttlSeconds: Int = 1800, clock: () -> Instant = Instant::now,
    codec: ParsedImportCodec = ParsedImportCodec(), private val maxSessions: Int = 256,
    private val maxTotalBytes: Long = 256L * 1024 * 1024,
    tokenFactory: (Int) -> String = MutationPlanStore::secureUrlToken) : ParsedImportStore(ttlSeconds, clock, codec, tokenFactory) {
    private val lock = Any()
    private val entries = linkedMapOf<String, ByteArray>()
    init { require(maxSessions > 0 && maxTotalBytes > 0) { "import_session_storage_config_invalid" } }

    override fun readRaw(id: String): ParsedImportObject = synchronized(lock) {
        val content = entries[id] ?: throw ParsedImportNotFound()
        ParsedImportObject(content.copyOf(), digest(content))
    }
    override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) = synchronized(lock) {
        val old = entries[id]
        if (expected == null && old != null || expected != null && (old == null || digest(old) != expected))
            throw ParsedImportRevisionConflict()
        if (expected == null) {
            entries.entries.removeIf { (_, body) -> clock() >= expiry(body) }
            if (entries.size >= maxSessions) throw ParsedImportDataError("import_session_capacity_exceeded")
        }
        if (entries.values.sumOf { it.size.toLong() } - (old?.size ?: 0) + content.size > maxTotalBytes)
            throw ParsedImportDataError("import_session_capacity_exceeded")
        entries[id] = content.copyOf()
    }
    override fun deleteRaw(id: String) { synchronized(lock) { entries.remove(id) } }
    override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean): ParsedImportSweep {
        sweepArgs(cursor, limit)
        return synchronized(lock) {
            val keys = entries.keys.filter { cursor == null || it > cursor }.sorted()
            var scanned = 0; var deleted = 0; var last = cursor
            for (token in keys.take(limit)) {
                if (shouldStop()) return@synchronized ParsedImportSweep(scanned, deleted, last)
                if (clock() >= expiry(entries.getValue(token))) { entries.remove(token); deleted++ }
                scanned++; last = token
            }
            ParsedImportSweep(scanned, deleted, if (keys.size > limit) last else null)
        }
    }
    private fun expiry(bytes: ByteArray): Instant = try {
        pythonCashTimestamp(Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("expires_at").jsonPrimitive.content)
    } catch (_: IllegalArgumentException) { throw ParsedImportStorageUnavailable() }
    catch (_: NoSuchElementException) { throw ParsedImportStorageUnavailable() }
    catch (_: InvalidCashValue) { throw ParsedImportStorageUnavailable() }
}
