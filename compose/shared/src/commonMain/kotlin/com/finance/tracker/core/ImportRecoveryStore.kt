package com.finance.tracker.core

import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.FailureCategory
import com.finance.tracker.domain.ImportFrozenCommand
import io.ktor.util.hex
import io.ktor.util.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

data class ImportRecoveryScope(val apiOrigin: String, val principal: String, val workspaceId: String)

sealed interface ImportRecoveryPointer {
    data class Session(val sessionId: String) : ImportRecoveryPointer
    data class Submission(val sessionId: String, val command: ImportFrozenCommand) : ImportRecoveryPointer
    data class Receipt(val receiptId: String) : ImportRecoveryPointer
}

interface ImportRecoveryStore {
    suspend fun load(): ImportRecoveryPointer?
    suspend fun save(value: ImportRecoveryPointer)
    suspend fun clear()
}

interface ImportRecoveryStorage {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun remove(key: String)
}

expect fun createPlatformImportRecoveryStorage(): ImportRecoveryStorage

class ScopedImportRecoveryStore(scope: ImportRecoveryScope, private val storage: ImportRecoveryStorage) : ImportRecoveryStore {
    private val mutex = Mutex()
    private val key: String
    init {
        require(scope.apiOrigin.isNotBlank() && scope.apiOrigin.length <= 2048 && scope.principal.isNotBlank() &&
            scope.principal.length <= 320 && scope.workspaceId.isNotBlank() && scope.workspaceId.length <= 128) { "import_recovery_scope_invalid" }
        val namespace = listOf(scope.apiOrigin, scope.principal, scope.workspaceId).joinToString("") { "${it.length}:$it" }
        // 摘要只作本机命名空间，不替代服务端的所有者及权限校验。
        key = "finance-tracker:cash-import:v1:" + hex(sha256(namespace.encodeToByteArray()))
    }

    override suspend fun load(): ImportRecoveryPointer? = mutex.withLock {
        val text = access { storage.read(key) } ?: return@withLock null
        if (text.length > 4096) invalidRecovery()
        val value = try { Json.decodeFromString<StoredImportPointer>(text) }
            catch (_: Exception) { invalidRecovery() }
        value.toPointer()
    }

    override suspend fun save(value: ImportRecoveryPointer) = mutex.withLock {
        val stored = when (value) {
            is ImportRecoveryPointer.Session -> StoredImportPointer(1, "session", sessionId = value.sessionId)
            is ImportRecoveryPointer.Submission -> StoredImportPointer(1, "submission", sessionId = value.sessionId,
                planId = value.command.planId, revision = value.command.revision, key = value.command.idempotencyKey)
            is ImportRecoveryPointer.Receipt -> StoredImportPointer(1, "receipt", receiptId = value.receiptId)
        }
        stored.toPointer()
        access { storage.write(key, Json.encodeToString(stored)) }
    }

    override suspend fun clear() = mutex.withLock { access { storage.remove(key) } }

    private suspend fun <T> access(action: suspend () -> T): T = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw DomainFailure("import_recovery_unavailable", 0, FailureCategory.RECOVERABLE) }
}

@Serializable(with = StoredImportPointerSerializer::class)
private data class StoredImportPointer(
    val version: Int, val kind: String, val sessionId: String? = null, val planId: String? = null,
    val revision: Long? = null, val key: String? = null, val receiptId: String? = null,
) {
    fun toPointer(): ImportRecoveryPointer {
        if (version != 1) invalidRecovery()
        fun session(): String = sessionId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) } ?: invalidRecovery()
        return when (kind) {
            "session" -> {
                if (planId != null || revision != null || key != null || receiptId != null) invalidRecovery()
                ImportRecoveryPointer.Session(session())
            }
            "submission" -> {
                if (receiptId != null || revision == null || revision <= 0 || key == null ||
                    !key.matches(Regex("[A-Za-z0-9_:.~-]{1,255}"))) invalidRecovery()
                val source = session()
                val match = planId?.let { Regex("ip_([A-Za-z0-9_-]{43})\\.([A-Za-z0-9_-]{16})").matchEntire(it) } ?: invalidRecovery()
                if (match.groupValues[1] != source) invalidRecovery()
                ImportRecoveryPointer.Submission(source, ImportFrozenCommand(planId, revision, key))
            }
            "receipt" -> {
                if (sessionId != null || planId != null || revision != null || key != null) invalidRecovery()
                ImportRecoveryPointer.Receipt(receiptId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,36}")) } ?: invalidRecovery())
            }
            else -> invalidRecovery()
        }
    }
}

private object StoredImportPointerSerializer : KSerializer<StoredImportPointer> {
    private val names = listOf("version", "kind", "sessionId", "planId", "revision", "key", "receiptId")
    override val descriptor = buildClassSerialDescriptor("StoredImportPointer") {
        names.forEachIndexed { index, name -> element<JsonElement>(name, isOptional = index > 1) }
    }
    override fun deserialize(decoder: Decoder): StoredImportPointer {
        val fields = mutableMapOf<Int, JsonElement>()
        decoder.decodeStructure(descriptor) {
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                if (index !in names.indices || index in fields) invalidRecovery()
                fields[index] = decodeSerializableElement(descriptor, index, JsonElement.serializer())
            }
        }
        fun text(index: Int): String? = fields[index]?.let {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: invalidRecovery()
        }
        fun number(index: Int): Long? = fields[index]?.let {
            val primitive = it as? JsonPrimitive ?: invalidRecovery()
            if (primitive.isString || !primitive.content.matches(Regex("[0-9]+"))) invalidRecovery()
            primitive.content.toLongOrNull() ?: invalidRecovery()
        }
        val version = number(0)?.takeIf { it == 1L }?.toInt() ?: invalidRecovery()
        return StoredImportPointer(version, text(1) ?: invalidRecovery(), text(2), text(3), number(4), text(5), text(6))
    }
    override fun serialize(encoder: Encoder, value: StoredImportPointer) {
        encoder.encodeSerializableValue(JsonObject.serializer(), buildJsonObject {
            put("version", value.version); put("kind", value.kind)
            value.sessionId?.let { put("sessionId", it) }
            value.planId?.let { put("planId", it) }
            value.revision?.let { put("revision", it) }
            value.key?.let { put("key", it) }
            value.receiptId?.let { put("receiptId", it) }
        })
    }
}

private fun invalidRecovery(): Nothing = throw DomainFailure("import_recovery_invalid", 0, FailureCategory.RECOVERABLE)
