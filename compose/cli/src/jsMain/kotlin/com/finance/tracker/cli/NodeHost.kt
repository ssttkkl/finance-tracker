package com.finance.tracker.cli

import com.finance.tracker.client.CashLedgerCalendar
import com.finance.tracker.client.CashImportFileInput
import com.finance.tracker.client.CashImportFileSource
import com.finance.tracker.client.ClientCapturedCashImportFile
import com.finance.tracker.client.ClientCashImportBatch
import com.finance.tracker.client.ClientCashImportListing
import com.finance.tracker.client.ClientCashImportOperations
import com.finance.tracker.client.ClientCashImportReviewedCommand
import com.finance.tracker.client.ClientCashImportStore
import com.finance.tracker.client.ClientImportAccountOperations
import com.finance.tracker.client.ClientCashCommandStore
import com.finance.tracker.client.ClientCashWriteOperations
import com.finance.tracker.client.ClientPendingCommand
import com.finance.tracker.client.ClientCashMaintenanceOperations
import com.finance.tracker.business.domain.CASH_STATEMENT_MAX_BYTES
import com.finance.tracker.client.ClientHttp
import com.finance.tracker.client.ClientHttpBody
import com.finance.tracker.client.ClientHttpResponse
import com.finance.tracker.client.ClientAccessOperations
import com.finance.tracker.client.ClientLedgerState
import com.finance.tracker.client.ClientNetworkUnavailable
import com.finance.tracker.client.ClientOperationFailure
import com.finance.tracker.client.ClientSessionOperations
import com.finance.tracker.client.ClientSessionStore
import com.finance.tracker.client.ClientStoredLedger
import com.finance.tracker.client.ClientStoredSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.time.Instant

@JsModule("./host.mjs")
private external object NodeHost {
    fun serverOrigin(value: String): String?
    fun request(origin: String, method: String, path: String, token: String?, body: dynamic, headers: dynamic): Promise<dynamic>
    fun statementBody(metadata: String, ref: String, name: String, mediaType: String, bytes: ByteArray): dynamic
    fun timeZoneExists(zone: String): Boolean
    fun timeZoneOffset(epochSeconds: String, zone: String): Int
    fun startOfDay(date: String, zone: String): String
    fun openStore(origin: String, createKey: Boolean): dynamic
    fun splitLargeImportRequest(text: String, field: String, threshold: Int): dynamic
    fun encodeFrozenImportBatch(metadata: String, request: String): String
    fun readStatement(path: String, limit: Int): dynamic
}

internal val nodeCalendar = CashLedgerCalendar(
    NodeHost::timeZoneExists, NodeHost::timeZoneOffset,
    { date, zone -> Instant.parse(NodeHost.startOfDay(date, zone)) },
)

private suspend fun <T> Promise<T>.await(): T = suspendCoroutine { continuation ->
    then({ continuation.resume(it) }, { continuation.resumeWithException(it) })
}

internal suspend fun <T> withClient(
    server: String?,
    createKey: Boolean = false,
    action: suspend (ClientSessionOperations) -> T,
): T {
    return withHost(server, createKey) { session, _ -> action(session) }
}

internal suspend fun <T> withCashImport(server: String?, reportProgress: Boolean = false, action: suspend (ClientCashImportOperations) -> T): T =
    withHost(server, false) { session, store ->
        action(ClientCashImportOperations(session, store, nodeCalendar, ::newReference, progress = { stage ->
            if (reportProgress) process.stderr.write("ft import: $stage\n")
        }))
    }

internal suspend fun <T> withImportAccounts(server: String?, action: suspend (ClientImportAccountOperations) -> T): T =
    withHost(server, false) { session, store ->
        val imports = ClientCashImportOperations(session, store, nodeCalendar, ::newReference)
        val writes = ClientCashWriteOperations(session, store, ::newReference)
        action(ClientImportAccountOperations(imports, writes, session))
    }

internal suspend fun <T> withCashWrites(server: String?, action: suspend (ClientCashWriteOperations) -> T): T =
    withHost(server, false) { session, store -> action(ClientCashWriteOperations(session, store, ::newReference)) }

internal suspend fun <T> withMaintenance(server: String?, action: suspend (ClientCashMaintenanceOperations) -> T): T =
    withClient(server) { action(ClientCashMaintenanceOperations(it)) }

private suspend fun <T> withHost(
    server: String?,
    createKey: Boolean,
    action: suspend (ClientSessionOperations, NodeSessionStore) -> T,
): T {
    val origin = requireServerOrigin(server)
    val database = try {
        NodeHost.openStore(origin, createKey)
    } catch (failure: Throwable) {
        throw ClientOperationFailure("local_state_unreadable", cause = failure)
    }
    (database.error as String?)?.let { throw ClientOperationFailure(it) }

    val store = NodeSessionStore(database)
    val operations = ClientSessionOperations(nodeTransport(origin), store, ::encodeUrlComponent, { Date().toISOString() })
    var committed = false
    try {
        return action(operations, store).also { committed = true }
    } catch (failure: ClientOperationFailure) {
        // A checked operation can have verified loss of access. Persist its local invalidation before reporting it.
        committed = true
        throw failure
    } catch (failure: ClientNetworkUnavailable) {
        committed = true
        throw failure
    } finally {
        database.close(committed || store.accessInvalidated)
    }
}

internal suspend fun previewInvitation(server: String?, token: String) =
    ClientAccessOperations(nodeTransport(requireServerOrigin(server)), ::encodeUrlComponent).previewInvitation(token)

private fun requireServerOrigin(server: String?): String {
    val address = server ?: (process.env.FT_CLI_SERVER as String?) ?: throw CliFailure("missing_parameter", "--server")
    return NodeHost.serverOrigin(address) ?: throw CliFailure("invalid_parameter", "--server")
}

private fun nodeTransport(origin: String) = ClientHttp { request ->
        val headers = js("Object.create(null)")
        for ((key, value) in request.headers) headers[key] = value
        val body = when (val input = request.body) {
            is ClientHttpBody.Json -> input.text.also { headers["Content-Type"] = "application/json" }
            is ClientHttpBody.Statement -> NodeHost.statementBody(input.metadata, input.ref, input.name, input.mediaType, input.bytes)
            null -> null
        }
        val result = NodeHost.request(origin, request.method, request.path, request.token, body, headers).await()
        val status = result.status as Int
        if (status == 0) throw ClientNetworkUnavailable(IllegalStateException(result.diagnostic as String))
        if (status == -1) throw ClientOperationFailure("transport_error", cause = IllegalStateException(result.diagnostic as String))
        ClientHttpResponse(status, result.body as String)
    }
private const val largeImportRequestLength = 1024 * 1024

private class NodeSessionStore(private val database: dynamic) : ClientSessionStore, ClientCashImportStore, ClientCashCommandStore {
    private var observedReview: ObservedReview? = null
    private var savedConfirmation: SavedConfirmation? = null
    private var checkpointedConfirmation: SavedConfirmation? = null

    private data class SavedConfirmation(val key: String, val stamp: String, val batch: ClientCashImportBatch)

    private data class ObservedReview(
        val key: String, val parentStamp: String?, val reviewedStamp: String?, val batch: dynamic,
    )

    var accessInvalidated = false
        private set

    override suspend fun session(): ClientStoredSession? = read("session")

    override suspend fun authenticate(previous: ClientStoredSession?, session: ClientStoredSession): Boolean {
        val current = session()
        if (!sameState(previous, current)) return false
        if (current?.token != session.token || current.session.user != session.session.user) database.clear()
        database.write("session", Json.encodeToString(session))
        return true
    }

    override suspend fun verify(previous: ClientStoredSession, session: ClientStoredSession): Boolean {
        if (!sameState(previous, session())) return false
        val retain = session.session.workspaces.map { it.id }.toSet()
        previous.session.workspaces.filter { it.id !in retain }.forEach { forgetWorkspace(previous, it.id) }
        database.write("session", Json.encodeToString(session))
        return true
    }

    private fun sameState(first: ClientStoredSession?, second: ClientStoredSession?): Boolean =
        first?.token == second?.token && first?.session == second?.session

    override suspend fun ledgerState(session: ClientStoredSession, workspaceId: String): ClientLedgerState =
        (read<ClientLedgerState>(slot(session, workspaceId)) ?: ClientLedgerState()).also {
            if (it.ledger?.snapshot?.workspaceId?.let { id -> id != workspaceId } == true)
                throw ClientOperationFailure("local_state_unreadable")
        }

    override suspend fun saveLedger(session: ClientStoredSession, expectedGeneration: String?, ledger: ClientStoredLedger): Boolean {
        val workspaceId = ledger.snapshot.workspaceId
        if (!sameState(session, session()) || ledgerState(session, workspaceId).generation != expectedGeneration) return false
        database.write(slot(session, workspaceId), Json.encodeToString(ClientLedgerState(newReference(), ledger)))
        return true
    }

    override suspend fun forgetWorkspace(session: ClientStoredSession, workspaceId: String) {
        val workspaceSlot = slot(session, workspaceId)
        database.write(workspaceSlot, Json.encodeToString(ClientLedgerState(newReference())))
        database.removeByPrefix("$workspaceSlot.")
        accessInvalidated = true
    }

    override suspend fun clear(session: ClientStoredSession) {
        if (session().let { it?.token == session.token && it.session.user == session.session.user }) {
            database.clear()
            accessInvalidated = true
        }
    }

    private fun slot(session: ClientStoredSession, workspaceId: String): String =
        database.workspaceSlot(session.token, session.session.user.email, workspaceId) as String

    override suspend fun batches(session: ClientStoredSession, workspaceId: String): List<ClientCashImportListing> {
        val prefix = "${slot(session, workspaceId)}.import."
        return database.slots(prefix).unsafeCast<Array<String>>()
            .filterNot { ".file." in it }.map { key -> checkNotNull(read<ClientCashImportBatch>(key)).listing() }
    }

    override suspend fun read(session: ClientStoredSession, workspaceId: String, batchId: String): ClientCashImportBatch? {
        observedReview = null
        val key = "${slot(session, workspaceId)}.import.$batchId"
        val checkpointed = checkpointedConfirmation
        checkpointedConfirmation = null
        if (checkpointed?.key == key && stateStamp(key) == checkpointed.stamp) return checkpointed.batch
        val batch = readPreviewBatch(key, batchId) ?: return null
        if (batch.planId == null || batch.submission != null || batch.result != null) return batch
        val reviewedSlot = "$key.file.reviewed"
        val review = readImportRequest<StoredReviewedCommand>(reviewedSlot, "command") { stored, request ->
            stored.copy(command = stored.command.copy(request = request))
        }
        if (review == null || review.planId != batch.planId || review.revision != batch.revision ||
            review.ledgerSeq != batch.ledgerSeq) return batch
        val reviewed = batch.copy(reviewed = review.command)
        observedReview = ObservedReview(key, stateStamp(key), stateStamp(reviewedSlot), weakReference(reviewed))
        return reviewed
    }

    override suspend fun readPreview(session: ClientStoredSession, workspaceId: String, batchId: String): ClientCashImportBatch? =
        readPreviewBatch("${slot(session, workspaceId)}.import.$batchId", batchId)

    override suspend fun pendingConfirmationKey(session: ClientStoredSession, workspaceId: String, batchId: String): String? {
        val key = "${slot(session, workspaceId)}.import.$batchId"
        val indexed = read<PendingConfirmationIndex>("$key.file.confirmation") ?: return null
        if (indexed.stamp != stateStamp(key)) return null
        if (!com.finance.tracker.business.domain.isCashWriteSetKey(indexed.operationKey))
            throw ClientOperationFailure("local_state_unreadable")
        return indexed.operationKey
    }

    private fun stateStamp(key: String): String? = database.stateStamp(key) as String?

    private fun readPreviewBatch(key: String, batchId: String): ClientCashImportBatch? =
        readImportRequest<ClientCashImportBatch>(key, "submission") { stored, request ->
            stored.copy(submission = checkNotNull(stored.submission).copy(request = request))
        }?.also {
            if (it.id != batchId) throw ClientOperationFailure("local_state_unreadable")
        }

    override suspend fun compareAndSave(
        session: ClientStoredSession,
        workspaceId: String,
        previous: ClientCashImportBatch?,
        batch: ClientCashImportBatch,
        file: ClientCapturedCashImportFile?,
        removedFileIds: List<String>,
    ): Boolean {
        if (!sameState(session, session())) return false
        savedConfirmation = null
        val prefix = "${slot(session, workspaceId)}.import.${batch.id}"
        val observed = observedReview
        val current = if (batch.submission != null && observed != null && observed.batch.deref() === previous &&
            observed.key == prefix && stateStamp(prefix) == observed.parentStamp &&
            stateStamp("$prefix.file.reviewed") == observed.reviewedStamp) previous else read(session, workspaceId, batch.id)
        observedReview = null
        if (current != previous) return false
        if (current?.submission == null && batch.submission != null && hasPendingImportAccountCommand(session, workspaceId, batch.id))
            throw ClientOperationFailure("account_result_unknown", commitState = "unknown")
        file?.let { database.write("$prefix.file.${it.storageId}", it.payload) }
        database.write(prefix, encodeImportBatch(batch))
        val reviewed = batch.reviewed
        if (reviewed == null) database.remove("$prefix.file.reviewed")
        else if (reviewed != current?.reviewed || batch.planId != current.planId || batch.revision != current.revision ||
            batch.ledgerSeq != current.ledgerSeq) {
            database.write("$prefix.file.reviewed", Json.encodeToString(StoredReviewedCommand(
                checkNotNull(batch.planId), batch.revision, checkNotNull(batch.ledgerSeq), reviewed,
            )))
        }
        removedFileIds.forEach { database.remove("$prefix.file.$it") }
        val confirmationSlot = "$prefix.file.confirmation"
        val pendingKey = batch.submission?.key?.takeIf { batch.result == null }
        if (pendingKey == null) database.remove(confirmationSlot)
        else {
            val stamp = checkNotNull(stateStamp(prefix))
            database.write(confirmationSlot, Json.encodeToString(PendingConfirmationIndex(stamp, pendingKey)))
            if (current?.submission == null && batch.reviewed == null) savedConfirmation = SavedConfirmation(prefix, stamp, batch)
        }
        return true
    }

    override suspend fun readFile(session: ClientStoredSession, workspaceId: String, batchId: String, storageId: String): String? =
        try {
            database.read("${slot(session, workspaceId)}.import.$batchId.file.$storageId") as String?
        } catch (failure: Throwable) {
            throw ClientOperationFailure("local_state_unreadable", cause = failure)
        }

    override suspend fun compareAndDelete(session: ClientStoredSession, workspaceId: String, previous: ClientCashImportBatch): Boolean {
        if (!sameState(session, session()) || read(session, workspaceId, previous.id) != previous ||
            hasPendingImportAccountCommand(session, workspaceId, previous.id)) return false
        val prefix = "${slot(session, workspaceId)}.import.${previous.id}"
        database.remove(prefix)
        database.removeByPrefix("$prefix.file.")
        return true
    }

    override suspend fun commands(session: ClientStoredSession, workspaceId: String): List<ClientPendingCommand> =
        workspaceCommands("${slot(session, workspaceId)}.command.")

    private suspend fun hasPendingImportAccountCommand(session: ClientStoredSession, workspaceId: String, batchId: String): Boolean =
        commands(session, workspaceId).any { it.batchId == batchId && it.receipt == null && it.rejection == null }

    private fun workspaceCommands(prefix: String): List<ClientPendingCommand> =
        database.slots(prefix).unsafeCast<Array<String>>().map { slot -> checkNotNull(read<ClientPendingCommand>(slot)) }

    override suspend fun command(session: ClientStoredSession, workspaceId: String, id: String): ClientPendingCommand? =
        read<ClientPendingCommand>("${slot(session, workspaceId)}.command.$id")?.also {
            if (it.id != id) throw ClientOperationFailure("local_state_unreadable")
        }

    override suspend fun saveCommand(
        session: ClientStoredSession, workspaceId: String, previous: ClientPendingCommand?, command: ClientPendingCommand,
        expectedBatch: ClientCashImportBatch?,
    ): Boolean {
        if (!sameState(session, session()) || command(session, workspaceId, command.id) != previous ||
            expectedBatch != null && read(session, workspaceId, expectedBatch.id) != expectedBatch) return false
        database.write("${slot(session, workspaceId)}.command.${command.id}", Json.encodeToString(command))
        return true
    }

    override suspend fun checkpoint() {
        val saved = savedConfirmation
        savedConfirmation = null
        observedReview = null
        checkpointedConfirmation = null
        val version = if (saved != null) database.dataVersion() as Int else null
        database.checkpoint()
        if (saved != null && version == database.dataVersion() as Int) checkpointedConfirmation = saved
    }

    private fun encodeImportBatch(batch: ClientCashImportBatch): String {
        val submission = batch.submission
        if (submission == null || submission.request.length < largeImportRequestLength)
            return Json.encodeToString(batch.copy(reviewed = null))
        val metadata = Json.encodeToString(batch.copy(reviewed = null, submission = submission.copy(request = "")))
        return NodeHost.encodeFrozenImportBatch(metadata, submission.request)
    }

    private inline fun <reified T> readImportRequest(slot: String, field: String, withRequest: (T, String) -> T): T? =
        readState(slot) { text ->
            val parts = NodeHost.splitLargeImportRequest(text, field, largeImportRequestLength)
            if (parts == null) Json.decodeFromString<T>(text)
            else withRequest(Json.decodeFromString<T>(parts.metadata as String), parts.request as String)
        }

    private inline fun <reified T> read(slot: String): T? =
        readState(slot) { Json.decodeFromString<T>(it) }

    private inline fun <T> readState(slot: String, decode: (String) -> T): T? = try {
        (database.read(slot) as String?)?.let(decode)
    } catch (failure: Throwable) {
        throw ClientOperationFailure("local_state_unreadable", cause = failure)
    }
}

@Serializable
private data class PendingConfirmationIndex(val stamp: String, val operationKey: String)

@Serializable
private data class StoredReviewedCommand(val planId: String, val revision: Long, val ledgerSeq: String,
    val command: ClientCashImportReviewedCommand)

private fun weakReference(value: Any): dynamic = js("new WeakRef(value)")

private fun encodeUrlComponent(value: String): String = js("encodeURIComponent(value)")

internal fun newReference(): String = process.getBuiltinModule("node:crypto").randomUUID() as String

internal fun readInputText(path: String): String {
    val file = NodeHost.readStatement(path, CASH_STATEMENT_MAX_BYTES)
    (file.code as String?)?.let { throw CliFailure(it, "--input") }
    val bytes = file.bytes.unsafeCast<ByteArray>()
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: kotlin.text.CharacterCodingException) {
        throw CliFailure("invalid_parameter", "--input")
    }
}

internal fun statementSource(path: String, source: String?, ref: String = newReference(), password: String? = null): CashImportFileSource {
    val name = process.getBuiltinModule("node:path").basename(path) as String
    return CashImportFileSource(ref, name) {
        val file = NodeHost.readStatement(path, CASH_STATEMENT_MAX_BYTES)
        (file.code as String?)?.let { throw ClientOperationFailure(it) }
        CashImportFileInput(ref, file.name as String, (file.modifiedAt as String).toLong(),
            file.bytes.unsafeCast<ByteArray>(), source, password)
    }
}
