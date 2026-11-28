package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.domain.CashAmount
import com.finance.tracker.client.CashLedgerSummary
import com.finance.tracker.client.ClientOperationFailure
import com.finance.tracker.client.LedgerAssistantDelivery
import com.finance.tracker.client.LedgerAssistantDeliveryStatus
import com.finance.tracker.client.NewCashRecord
import com.finance.tracker.client.freezeCashRecord
import com.finance.tracker.client.validateCashCommandReceipt
import com.finance.tracker.domain.DomainFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class LedgerAssistantWorkspace(
    private val operations: CashBusinessOperations,
    private val reader: LedgerSessionLocal,
    private val store: LedgerAssistantConversations,
    private val workspaceId: String,
    private val principal: String,
    val canWrite: Boolean,
) {
    private val mutex = Mutex()
    private val activeWrites = mutableSetOf<String>()

    suspend fun load(): List<LedgerAssistantConversation> = mutex.withLock {
        store.list().map { old ->
            val restoredMessages = old.messages.interruptActiveReads().map { message ->
                message.copy(delivery = message.delivery?.interrupted())
            }
            val conversation = if (restoredMessages == old.messages) old else update(old) { copy(messages = restoredMessages) }
            val command = conversation.draft?.command
            if (command == null || command.receipt != null || reader.offline.value) conversation
            else {
                val receipt = try {
                    useApi(write = false) { api, _ -> api.readCashMutationReceiptByKey(command.id) }
                } catch (_: ApiFailure) {
                    null
                } catch (_: FinanceTransportFailure) {
                    null
                }
                if (receipt == null) conversation else {
                    val validated = try {
                        validateCashCommandReceipt(command, receipt).receipt
                    } catch (failure: ClientOperationFailure) {
                        return@map if (failure.commitState == "not_committed") markRejected(conversation) else conversation
                    }
                    recordReceipt(conversation, validated)
                }
            }
        }
    }

    suspend fun purgeExpiredSkillFiles() = store.purgeExpiredSkillFiles(Clock.System.now().toEpochMilliseconds())

    suspend fun readAttachment(conversationId: String, id: String): LedgerAssistantFileContent? =
        store.readSkillFile(conversationId, id, Clock.System.now().toEpochMilliseconds())?.takeIf { it.metadata.attached }

    suspend fun draftAccounts() = checkNotNull(reader.accounts()).filter { it.active }

    suspend fun draftCategories() = checkNotNull(reader.categories())

    suspend fun draftOptions() = checkNotNull(reader.options())

    suspend fun create(): LedgerAssistantConversation = mutex.withLock {
        val conversation = LedgerAssistantConversation(newAssistantId(), Clock.System.now().toString())
        check(store.swap(null, conversation))
        conversation
    }

    suspend fun pin(id: String, pinned: Boolean): LedgerAssistantConversation? = mutex.withLock {
        val old = store.list().firstOrNull { it.id == id } ?: return@withLock null
        if (old.pinned == pinned) old else update(old, touchesActivity = false) { copy(pinned = pinned) }
    }

    suspend fun rename(id: String, title: String): LedgerAssistantConversation? = mutex.withLock {
        val candidate = title.trim()
        require(candidate.isNotEmpty())
        val old = store.list().firstOrNull { it.id == id } ?: return@withLock null
        if (old.historyTitle == candidate) old else update(old, touchesActivity = false) { copy(titleOverride = candidate) }
    }

    suspend fun delete(id: String): Boolean = mutex.withLock {
        val old = store.list().firstOrNull { it.id == id } ?: return@withLock true
        if (id in activeWrites || old.hasUnresolvedWrite) return@withLock false
        store.delete(old)
    }

    suspend fun send(
        id: String,
        input: String,
        onUpdate: (LedgerAssistantConversation) -> Unit = {},
    ): LedgerAssistantConversation = mutex.withLock {
        require(input.isNotBlank())
        val old = current(id)
        val draft = old.draft
        check(old.messages.none {
            it.delivery?.status in setOf(LedgerAssistantDeliveryStatus.NOT_DELIVERED, LedgerAssistantDeliveryStatus.WAITING_FOR_RECORD)
        })
        val command = draft?.command
        if (command != null && (command.receipt == null || id in activeWrites)) {
            val waiting = LedgerAssistantDelivery(newAssistantId(), input, status = LedgerAssistantDeliveryStatus.WAITING_FOR_RECORD)
            return@withLock update(old) { copy(messages = messages + LedgerAssistantMessage(delivery = waiting)) }.also(onUpdate)
        }
        val cancelled = draft != null && !draft.cancelled && command == null
        val delivery = LedgerAssistantDelivery(
            newAssistantId(), input, draft?.toolCallId?.takeIf { command == null && (cancelled || draft.cancelled) },
        ).retry()
        val cancelledMessage = draft?.takeIf { cancelled }?.let { cancelledCard(it) }
        val sending = update(old) {
            copy(
                messages = (if (cancelled) messages.withDraftTrace(checkNotNull(draft), LedgerAssistantToolStatus.CANCELLED) else messages) + listOfNotNull(
                    cancelledMessage,
                    LedgerAssistantMessage(delivery = delivery),
                ),
                draft = draft?.let { if (cancelled) it.copy(cancelled = true) else it },
            )
        }
        infer(sending.also(onUpdate), delivery, onUpdate)
    }

    suspend fun retry(
        id: String,
        messageId: String,
        onUpdate: (LedgerAssistantConversation) -> Unit,
    ): LedgerAssistantConversation = mutex.withLock {
        val old = current(id)
        val delivery = checkNotNull(old.messages.lastOrNull { it.delivery != null }?.delivery)
        if (delivery.messageId != messageId || delivery.status != LedgerAssistantDeliveryStatus.NOT_DELIVERED) return@withLock old
        val claim = delivery.retry()
        infer(update(old) { replaceDelivery(claim) }.also(onUpdate), claim, onUpdate)
    }

    suspend fun confirm(
        id: String,
        callId: String,
        fields: JsonObject,
        onPrepared: (LedgerAssistantConversation) -> Unit,
    ): LedgerAssistantConversation {
        val submitting = mutex.withLock {
            val old = current(id)
            val draft = old.draft ?: return old
            if (!canWrite || draft.cancelled || draft.localId != callId || draft.command != null) return old
            val input = draftInput(fields)
            val account = draftAccounts().singleOrNull { it.id.toString() == input.accountId }
                ?: throw IllegalArgumentException("assistant_account_unavailable")
            require(input.currency in account.currencies.orEmpty()) { "assistant_currency_unavailable" }
            val command = freezeCashRecord(input, account.name, newAssistantId())
            update(old) {
                copy(
                    draft = draft.copy(fields = fields, command = command),
                    messages = messages.withDraftTrace(draft, LedgerAssistantToolStatus.SUBMITTING),
                )
            }
                .also { check(activeWrites.add(id)) }
        }
        onPrepared(submitting)
        return completeWrite(submitting, lookupFirst = false, onPrepared)
    }

    suspend fun cancelDraft(id: String, callId: String): LedgerAssistantConversation = mutex.withLock {
        val old = current(id)
        val draft = old.draft ?: return@withLock old
        if (draft.localId != callId || draft.cancelled || draft.command != null) return@withLock old
        val card = cancelledCard(draft)
        update(old) {
            copy(draft = draft.copy(cancelled = true), messages = messages.withDraftTrace(draft, LedgerAssistantToolStatus.CANCELLED) + card)
        }
    }

    suspend fun edit(id: String, callId: String, fields: JsonObject): LedgerAssistantConversation = mutex.withLock {
        val conversation = current(id)
        val draft = conversation.draft ?: return@withLock conversation
        if (draft.localId != callId || draft.cancelled || draft.command != null || draft.fields == fields) conversation
        else update(conversation) { copy(draft = draft.copy(fields = fields)) }
    }

    suspend fun recoverWrite(id: String, onUpdate: (LedgerAssistantConversation) -> Unit = {}): LedgerAssistantConversation {
        val old = mutex.withLock {
            val conversation = current(id)
            if (conversation.draft?.command == null || !activeWrites.add(id)) return conversation
            conversation
        }
        return completeWrite(old, lookupFirst = true, onUpdate)
    }

    private suspend fun completeWrite(
        conversation: LedgerAssistantConversation,
        lookupFirst: Boolean,
        onUpdate: (LedgerAssistantConversation) -> Unit,
    ): LedgerAssistantConversation {
        val id = conversation.id
        val draft = checkNotNull(conversation.draft)
        val command = checkNotNull(draft.command)
        try {
            if (command.receipt == null) {
                try {
                    useApi(write = true) { api, refresh ->
                        val saved = api.executeFixedCashCommand(command, lookupFirst)
                        mutex.withLock {
                            val newest = current(id)
                            check(newest.draft?.command?.id == command.id)
                            recordReceipt(newest, saved).also(onUpdate)
                        }
                        refresh(saved.ledgerSeq)
                    }
                } catch (failure: ClientOperationFailure) {
                    if (failure.commitState == "not_committed") mutex.withLock {
                        val newest = current(id)
                        check(newest.draft?.command?.id == command.id)
                        markRejected(newest)
                    }
                    throw failure
                }
            }
            return mutex.withLock {
                val latest = current(id)
                check(latest.draft?.command?.id == command.id)
                val queued = latest.messages.firstNotNullOfOrNull {
                    it.delivery?.takeIf { delivery -> delivery.status == LedgerAssistantDeliveryStatus.WAITING_FOR_RECORD }
                }
                if (queued != null) {
                    val delivery = queued.retry()
                    infer(update(latest) { replaceDelivery(delivery) }.also(onUpdate), delivery, onUpdate)
                } else {
                    val saved = checkNotNull(latest.draft?.command?.receipt)
                    val continuation = LedgerAssistantContinuation(null, draft.toolCallId, receiptResult(saved))
                    executeRound(latest, continuation, onUpdate = onUpdate)
                }
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock { activeWrites.remove(id) } }
        }
    }

    private suspend fun markRejected(conversation: LedgerAssistantConversation): LedgerAssistantConversation {
        val draft = checkNotNull(conversation.draft)
        val queued = conversation.messages.any { it.delivery?.status == LedgerAssistantDeliveryStatus.WAITING_FOR_RECORD }
        val cancelled = if (queued) listOf(cancelledCard(draft)) else emptyList()
        return update(conversation) {
            copy(
                draft = draft.copy(command = null, cancelled = queued),
                messages = messages.withDraftTrace(
                    draft, if (queued) LedgerAssistantToolStatus.CANCELLED else LedgerAssistantToolStatus.FAILED,
                ).map { message ->
                    val pending = message.delivery
                    if (pending?.status == LedgerAssistantDeliveryStatus.WAITING_FOR_RECORD) {
                        message.copy(delivery = pending.copy(
                            status = LedgerAssistantDeliveryStatus.NOT_DELIVERED,
                            cancelledToolCallId = draft.toolCallId,
                        ))
                    } else message
                } + cancelled,
            )
        }
    }

    private suspend fun cancelledCard(draft: LedgerAssistantDraft): LedgerAssistantMessage {
        val account = reader.accounts()?.firstOrNull { it.id.toString() == draft.fields.text("account_id") }
        val fields = if (account == null) draft.fields else JsonObject(draft.fields + ("account_name" to JsonPrimitive(account.name)))
        return LedgerAssistantMessage(result = LedgerAssistantToolResult("cancelled_transaction", fields))
    }

    private suspend fun recordReceipt(conversation: LedgerAssistantConversation, receipt: CashMutationReceiptDto): LedgerAssistantConversation {
        val draft = checkNotNull(conversation.draft)
        val command = checkNotNull(draft.command)
        val frozen = Json.parseToJsonElement(command.body).jsonObject
        val caption = frozen.filterKeys { it in setOf("amount", "currency", "account_name") }
        val displayedReceipt = JsonObject(Json.encodeToJsonElement(receipt).jsonObject + caption)
        return update(conversation) {
            copy(
                draft = draft.copy(command = command.copy(receipt = receipt, body = "")),
                messages = messages.withDraftTrace(draft, LedgerAssistantToolStatus.SAVED, JsonObject(caption)) + LedgerAssistantMessage(
                    result = LedgerAssistantToolResult("saved_transaction", displayedReceipt),
                ),
            )
        }
    }

    private suspend fun infer(
        conversation: LedgerAssistantConversation,
        delivery: LedgerAssistantDelivery,
        onUpdate: (LedgerAssistantConversation) -> Unit,
    ): LedgerAssistantConversation {
        val draft = conversation.draft
        val receipt = draft?.command?.receipt
        val continuation = when {
            delivery.cancelledToolCallId != null -> LedgerAssistantContinuation(
                delivery.input, delivery.cancelledToolCallId, buildJsonObject { put("status", "cancelled") },
            )
            receipt != null -> LedgerAssistantContinuation(
                delivery.input, checkNotNull(draft).toolCallId, receiptResult(receipt),
            )
            else -> LedgerAssistantContinuation(delivery.input)
        }
        return executeRound(conversation, continuation, delivery, onUpdate)
    }

    private suspend fun executeRound(
        initial: LedgerAssistantConversation,
        continuation: LedgerAssistantContinuation,
        delivery: LedgerAssistantDelivery? = null,
        onUpdate: (LedgerAssistantConversation) -> Unit,
    ): LedgerAssistantConversation {
        var saved = initial
        val inputIndex = initial.messages.indexOfLast { it.delivery != null }
        var replyStartedAt = initial.messages.getOrNull(inputIndex)?.replyStartedAt
        fun markReplyVisible(visible: Boolean) {
            if (visible && replyStartedAt == null) replyStartedAt = Clock.System.now().toString()
        }
        fun LedgerAssistantConversation.withReplyTime(interruptedAt: String? = null) = copy(
            messages = messages.mapIndexed { index, message ->
                if (index == inputIndex) message.copy(replyStartedAt = replyStartedAt, replyInterruptedAt = interruptedAt)
                else message
            },
        )
        val attachments = initial.messages.mapNotNull { it.attachment }.associateBy { it.id }.toMutableMap()
        fun withAttachmentDeadlines(messages: List<LedgerAssistantMessage>) = messages.map { message ->
            val committed = message.attachment?.let { attachments[it.id] }
            if (committed == null) message else message.copy(attachment = committed)
        }
        suspend fun finishInterrupted(): LedgerAssistantConversation = update(saved) {
            val interrupted = copy(messages = messages.interruptActiveReads())
                .withReplyTime(interruptedAt = replyStartedAt?.let { Clock.System.now().toString() })
            if (delivery == null) interrupted else interrupted.replaceDelivery(delivery.interrupted())
        }

        return try {
            val round = useApi(write = false) { api, _ ->
                val messageId = delivery?.messageId ?: initial.messages.lastOrNull { it.delivery != null }?.delivery?.messageId
                val skills = messageId?.let {
                    LedgerAssistantSkills(reader, store, workspaceId, initial.id, it, api.requireCurrentRequest, attachments::get)
                }
                val executor = LedgerAssistantPromptExecutor(
                    api, checkNotNull(api.boundCredentials), api.requireCurrentRequest, skillsAvailable = skills != null,
                ) { partial ->
                    markReplyVisible(partial.isNotBlank())
                    saved = update(saved) { copy(incompleteReply = partial).withReplyTime() }.also(onUpdate)
                }
                runLedgerAssistantAgent(
                    executor, initial.id, initial.checkpoint, continuation,
                    onReadMessages = { observed ->
                        markReplyVisible(observed.any { !it.reply.isNullOrBlank() })
                        saved = update(saved) {
                            copy(messages = initial.messages + withAttachmentDeadlines(observed), incompleteReply = null).withReplyTime()
                        }.also(onUpdate)
                    },
                    executeSkill = skills?.let { it::execute },
                    persistSkill = { work, observed ->
                        work.checkBeforeCommit()
                        markReplyVisible(observed.any { !it.reply.isNullOrBlank() })
                        val committedAt = Clock.System.now()
                        val sent = work.files.map { it.metadata }.filter { it.attached }.associate { file ->
                            file.id to file.copy(expiresAtMs = committedAt.toEpochMilliseconds() + LEDGER_ASSISTANT_ATTACHMENT_TTL_MS)
                        }
                        val next = saved.copy(
                            revision = saved.revision + 1,
                            updatedAt = committedAt.toString(),
                            messages = initial.messages + withAttachmentDeadlines(observed).map { message ->
                                val file = message.attachment?.let { sent[it.id] }
                                if (file == null) message else message.copy(attachment = file)
                            },
                            incompleteReply = null,
                        ).withReplyTime()
                        val committed = try {
                            store.commitSkillRun(saved, next, work.removed, work.files, work.receipt,
                                committedAt.toEpochMilliseconds(), work.requiredSource, work.expectedLedgerVersion)
                        } catch (failure: LedgerAssistantScriptFailure) {
                            throw failure
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            throw LedgerAssistantScriptFailure("file_storage_unavailable")
                        }
                        if (!committed) throw CancellationException("ledger_assistant_conversation_changed")
                        attachments.putAll(sent)
                        saved = next.also(onUpdate)
                    },
                    readTool = ::readAssistantTool,
                )
            }
            markReplyVisible(round.messages.any { !it.reply.isNullOrBlank() })
            update(saved) {
                val completed = copy(messages = initial.messages + withAttachmentDeadlines(round.messages),
                    checkpoint = round.checkpoint, draft = round.draft, incompleteReply = null).withReplyTime()
                if (delivery == null) completed else completed.replaceDelivery(checkNotNull(delivery.delivered(delivery.attempt)))
            }
        } catch (_: ApiFailure) {
            finishInterrupted()
        } catch (_: FinanceTransportFailure) {
            finishInterrupted()
        } catch (_: DomainFailure) {
            finishInterrupted()
        }
    }

    private suspend fun readAssistantTool(name: String, fields: JsonObject): JsonObject {
        val filters = assistantCashFilters(fields)
        val timeZone = fields.text("time_zone_id")
        return when (name) {
            "list_accounts" -> buildJsonObject {
                put("accounts", Json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(com.finance.tracker.business.contracts.AccountReferenceDto.serializer()),
                    checkNotNull(reader.accounts()),
                ))
            }
            "list_categories" -> Json.encodeToJsonElement(
                com.finance.tracker.business.contracts.CategoryDirectoryDto.serializer(), checkNotNull(reader.categories()),
            ).jsonObject
            "list_transactions" -> {
                val limit = (fields["limit"] as? JsonPrimitive)?.intOrNull ?: 20
                if (limit !in 1..50) return assistantToolError("invalid_page_limit")
                val page = checkNotNull(reader.page(filters, timeZone, fields.text("cursor"), limit = limit))
                buildJsonObject {
                    put("items", Json.encodeToJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(com.finance.tracker.business.contracts.CashProjectionDto.serializer()),
                        page.items,
                    ))
                    page.nextCursor?.let { put("next_cursor", it) }
                }
            }
            "summarize_transactions" -> {
                val page = checkNotNull(reader.page(filters, timeZone, limit = 20))
                val summary = Json.encodeToJsonElement(CashLedgerSummary.serializer(), CashLedgerSummary(
                    workspaceId, page.projectionVersion.toString(), page.filters, page.monthlySummaries, page.pendingItemCount,
                )).jsonObject
                val category = filters.categoryId?.let { id -> reader.categories()?.items?.singleOrNull { it.id == id }?.name }
                JsonObject(summary + buildJsonObject {
                    put("display_items", Json.encodeToJsonElement(page.items))
                    if (category != null) put("display_category", category)
                })
            }
            "get_transaction" -> {
                val projectionId = fields.text("projection_id") ?: return assistantToolError("transaction_id_required")
                readLedgerAssistantTransaction(reader, projectionId)
            }
            else -> error("Unknown ledger assistant tool")
        }
    }

    private suspend fun <T> useApi(write: Boolean, action: suspend (FinanceApiClient, suspend (String?) -> Unit) -> T): T =
        operations.run(write) { api, refresh ->
            if (api.expectedWorkspace != workspaceId || api.expectedPrincipalEmail != principal)
                throw CancellationException("ledger_assistant_workspace_changed")
            action(api, refresh)
        }

    private suspend fun current(id: String): LedgerAssistantConversation = store.list().single { it.id == id }

    private suspend fun update(
        old: LedgerAssistantConversation,
        touchesActivity: Boolean = true,
        change: LedgerAssistantConversation.() -> LedgerAssistantConversation,
    ): LedgerAssistantConversation {
        val next = old.change().copy(revision = old.revision + 1, updatedAt = if (touchesActivity) Clock.System.now().toString() else old.updatedAt)
        check(store.swap(old, next)) { "ledger_assistant_conversation_changed" }
        return next
    }
}

private fun List<LedgerAssistantMessage>.withDraftTrace(
    draft: LedgerAssistantDraft,
    status: LedgerAssistantToolStatus,
    result: JsonObject? = null,
): List<LedgerAssistantMessage> = map { message ->
    val trace = message.toolTrace
    if (trace?.draftId == draft.localId) message.copy(toolTrace = trace.copy(status = status, result = result)) else message
}

private fun List<LedgerAssistantMessage>.interruptActiveReads(): List<LedgerAssistantMessage> = map { message ->
    val trace = message.toolTrace
    if (trace?.status == LedgerAssistantToolStatus.RUNNING) {
        message.copy(toolTrace = trace.copy(status = LedgerAssistantToolStatus.INTERRUPTED))
    } else message
}

private fun LedgerAssistantConversation.replaceDelivery(delivery: LedgerAssistantDelivery) = copy(
    messages = messages.map { if (it.delivery?.messageId == delivery.messageId) it.copy(delivery = delivery) else it },
)

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun draftInput(fields: JsonObject): NewCashRecord {
    fun required(key: String) = requireNotNull(fields.text(key)?.takeIf { it.isNotBlank() }) { "assistant_required_field" }
    val kind = required("record_type")
    require(kind in setOf("consumption", "income")) { "assistant_invalid_record_type" }
    val magnitude = required("amount").removePrefix("-")
    require(Regex("[0-9]+(?:\\.[0-9]+)?").matches(magnitude) && CashAmount.parseOrNull(magnitude) != null) {
        "assistant_invalid_amount"
    }
    val amount = if (kind == "consumption" && magnitude.any { it in '1'..'9' }) "-$magnitude" else magnitude
    return NewCashRecord(
        required("account_id"), amount, required("currency"), required("occurred_at"), kind,
        fields.text("record_subtype")?.takeIf(String::isNotBlank),
        fields.text("counterparty"), fields.text("note"), fields.text("category_id")?.takeIf(String::isNotBlank),
    )
}

private fun receiptResult(receipt: com.finance.tracker.business.contracts.CashMutationReceiptDto) = buildJsonObject {
    put("status", "saved")
    put("receipt", Json.encodeToJsonElement(com.finance.tracker.business.contracts.CashMutationReceiptDto.serializer(), receipt))
}

private fun assistantToolError(code: String) = buildJsonObject { put("error", code) }

@OptIn(ExperimentalUuidApi::class)
internal fun newAssistantId(): String = Uuid.random().toString()
