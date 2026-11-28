package com.finance.tracker.client

import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.requireCashWriteSetReceipt
import kotlin.io.encoding.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal fun invalidReadPage(): Nothing = throw ClientOperationFailure("protocol_error")

internal class ReadPageAssembly(
    private val sections: List<String>,
    private val consume: (String, JsonElement) -> Unit,
) {
    var expected: ReadPagePosition? = ReadPagePosition(sections.first(), 0)
        private set
    private var sectionTotal: Int? = null
    private var fragmentTotal: Int? = null
    private var fragmentParts = mutableListOf<ByteArray>()

    fun appendBundle(first: ReadPageChunk, remaining: List<ReadPageChunk>) {
        if (remaining.size >= sections.size) invalidReadPage()
        append(first)
        var section = first.position.section
        for (chunk in remaining) {
            if (chunk.position.section == section) invalidReadPage()
            append(chunk)
            section = chunk.position.section
        }
    }

    fun append(chunk: ReadPageChunk) {
        val position = expected ?: invalidReadPage()
        val total = chunk.total
        if (chunk.position != position || chunk.items.size > READ_PAGE_CHUNK_MAX_ITEMS || total < position.offset ||
            total == position.offset && total != 0 || sectionTotal?.let { total != it } == true)
            invalidReadPage()
        sectionTotal = total
        val fragment = chunk.fragment
        val offset = if (fragment == null) {
            if (position.fragmentOffset != 0 || fragmentTotal != null || chunk.items.size > total - position.offset ||
                chunk.items.isEmpty() && total != 0) invalidReadPage()
            chunk.items.forEach { consume(position.section, it) }
            position.offset + chunk.items.size
        } else {
            if (chunk.items.isNotEmpty() || position.offset >= total) invalidReadPage()
            if (fragment.totalBytes <= position.fragmentOffset ||
                fragmentTotal?.let { it != fragment.totalBytes } == true) invalidReadPage()
            val bytes = try { Base64.decode(fragment.base64) }
            catch (_: IllegalArgumentException) { invalidReadPage() }
            if (bytes.isEmpty() || bytes.size > fragment.totalBytes - position.fragmentOffset) invalidReadPage()
            fragmentTotal = fragment.totalBytes
            fragmentParts += bytes
            val end = position.fragmentOffset + bytes.size
            if (end < fragment.totalBytes) {
                advance(chunk, position.copy(fragmentOffset = end))
                return
            }
            val complete = ByteArray(end)
            var index = 0
            for (part in fragmentParts) {
                part.copyInto(complete, index)
                index += part.size
            }
            fragmentTotal = null
            fragmentParts.clear()
            val element = try {
                Json.parseToJsonElement(complete.decodeToString(throwOnInvalidSequence = true))
            } catch (_: IllegalArgumentException) {
                invalidReadPage()
            } catch (_: kotlin.text.CharacterCodingException) {
                invalidReadPage()
            }
            consume(position.section, element)
            position.offset + 1
        }
        val next = if (offset < total) ReadPagePosition(position.section, offset)
        else sections.getOrNull(sections.indexOf(position.section) + 1)?.let { ReadPagePosition(it, 0) }
        if (next?.section != position.section) sectionTotal = null
        advance(chunk, next)
    }

    private fun advance(chunk: ReadPageChunk, next: ReadPagePosition?) {
        if (chunk.next != next) invalidReadPage()
        expected = next
    }
}

private class ReadPageRows<T>(private val serializer: KSerializer<T>) {
    val values = mutableListOf<T>()
    fun add(value: JsonElement) {
        values += try { clientJson.decodeFromJsonElement(serializer, value) }
        catch (_: SerializationException) { invalidReadPage() }
    }
}

internal class LedgerSnapshotAssembly(private val workspace: String, private val minimumSeq: Long) {
    private val accounts = ReadPageRows(LedgerAccountFactDto.serializer())
    private val accountAliases = ReadPageRows(LedgerAccountAliasFactDto.serializer())
    private val mappings = ReadPageRows(LedgerStatementMappingFactDto.serializer())
    private val categories = ReadPageRows(LedgerCategoryFactDto.serializer())
    private val transactions = ReadPageRows(LedgerCashTransactionFactDto.serializer())
    private val components = ReadPageRows(LedgerCashComponentFactDto.serializer())
    private val sourceBindings = ReadPageRows(LedgerSourceBindingFactDto.serializer())
    private val emptyPages = ReadPageRows(LedgerSourceEmptyPageFactDto.serializer())
    private val relations = ReadPageRows(LedgerCashRelationFactDto.serializer())
    private val relationCandidates = ReadPageRows(LedgerRelationCandidateFactDto.serializer())
    private val issues = ReadPageRows(LedgerCashIssueFactDto.serializer())
    private val issueCandidates = ReadPageRows(LedgerIssueCandidateFactDto.serializer())
    private val entries = ReadPageRows(LedgerCashEntryFactDto.serializer())
    private val members = ReadPageRows(LedgerCashEntryMemberFactDto.serializer())
    private val fundingRelations = ReadPageRows(LedgerInvestmentFundingFactDto.serializer())

    private val rows = LedgerSnapshotSection.entries.map { it.wireName }.zip(listOf(
        accounts, accountAliases, mappings, categories, transactions, components, sourceBindings, emptyPages,
        relations, relationCandidates, issues, issueCandidates, entries, members, fundingRelations,
    )).toMap()
    private val pages = ReadPageAssembly(LedgerSnapshotSection.entries.map { it.wireName }) { section, value ->
        rows.getValue(section).add(value)
    }
    private var metadata: SnapshotPageMetadata? = null
    val position: ReadPagePosition? get() = pages.expected
    val ledgerSeq: String? get() = metadata?.ledgerSeq

    fun append(page: LedgerSnapshotReadPage) {
        val presented = SnapshotPageMetadata(page.ledgerSeq, page.rulesVersion, page.categoryDirectoryRevision)
        if (page.workspaceId != workspace || readPageSequence(page.ledgerSeq) < minimumSeq ||
            page.rulesVersion.isBlank() || metadata?.let { it != presented } == true) invalidReadPage()
        readPageSequence(page.categoryDirectoryRevision)
        pages.appendBundle(page.chunk, page.moreChunks)
        metadata = presented
    }

    fun complete(): LedgerSnapshotDto {
        if (position != null) invalidReadPage()
        val value = metadata ?: invalidReadPage()
        val facts = LedgerFactsDto(accounts.values, accountAliases.values, mappings.values, categories.values,
            transactions.values, components.values, sourceBindings.values, emptyPages.values, relations.values,
            relationCandidates.values, issues.values, issueCandidates.values, entries.values, members.values,
            fundingRelations.values, value.categoryDirectoryRevision)
        return LedgerSnapshotDto(workspace, value.ledgerSeq, value.rulesVersion, facts)
    }

    private data class SnapshotPageMetadata(val ledgerSeq: String, val rulesVersion: String, val categoryDirectoryRevision: String)
}

internal class CashImportReceiptAssembly(private val workspace: String) {
    private var header: CashMutationReceiptDto? = null
    private var hasPendingIds = false
    private val transactions = ReadPageRows(CashWriteSetTransactionResultDto.serializer())
    private val bindings = ReadPageRows(CashWriteSetIdentityDto.serializer())
    private val issues = ReadPageRows(CashWriteSetIdentityDto.serializer())
    private val mappings = ReadPageRows(CashWriteSetIdentityDto.serializer())
    private val relations = ReadPageRows(CashWriteSetIdentityDto.serializer())
    private val entries = ReadPageRows(CashWriteSetEntryResultDto.serializer())
    private val resolvedIssues = mutableListOf<String>()
    private val retiredEntries = mutableListOf<String>()
    private val pendingIds = mutableListOf<String>()
    private var metadata: ReceiptPageMetadata? = null
    private val pages = ReadPageAssembly(CashImportReceiptSection.entries.map { it.wireName }, ::consume)
    val position: ReadPagePosition? get() = pages.expected
    val receiptId: String? get() = metadata?.id

    fun append(page: CashImportReceiptReadPage) {
        val presented = ReceiptPageMetadata(page.receiptId, page.ledgerSeq)
        if (page.workspaceId != workspace || page.receiptId.isBlank() ||
            metadata?.let { it != presented } == true) invalidReadPage()
        readPageSequence(page.ledgerSeq)
        metadata = presented
        pages.appendBundle(page.chunk, page.moreChunks)
    }

    fun complete(): CashMutationReceiptDto {
        if (position != null || !hasPendingIds && pendingIds.isNotEmpty()) invalidReadPage()
        val base = header ?: invalidReadPage()
        if (hasPendingIds) {
            val count = base.pendingItemCount ?: invalidReadPage()
            if (count !in 0..pendingIds.size.toLong() || (count == 0L) != pendingIds.isEmpty() ||
                pendingIds.toSet().size != pendingIds.size) invalidReadPage()
        }
        val typed = base.copy(writeSetResult = CashWriteSetResultDto(transactions.values, bindings.values,
            issues.values, mappings.values, relations.values, entries.values, resolvedIssues, retiredEntries),
            pendingTransactionIds = pendingIds.takeIf { hasPendingIds })
        try { requireCashWriteSetReceipt(typed) }
        catch (_: IllegalStateException) { invalidReadPage() }
        return typed
    }

    private fun consume(field: String, value: JsonElement) {
        when (CashImportReceiptSection.entries.single { it.wireName == field }) {
            CashImportReceiptSection.HEADER -> {
                if (header != null) invalidReadPage()
                val envelope = value as? JsonObject ?: invalidReadPage()
                if (envelope.keys != setOf("receipt", "has_pending_transaction_ids")) invalidReadPage()
                val base = try { clientJson.decodeFromJsonElement(CashMutationReceiptDto.serializer(), envelope.getValue("receipt")) }
                catch (_: SerializationException) { invalidReadPage() }
                if (base.receiptId != metadata?.id || base.ledgerSeq != metadata?.ledgerSeq || base.planId != null ||
                    base.operationKind != "cash_write_set" || !base.committed ||
                    base.writeSetResult != null || base.pendingTransactionIds != null) invalidReadPage()
                val pending = envelope["has_pending_transaction_ids"] as? JsonPrimitive ?: invalidReadPage()
                hasPendingIds = pending.takeUnless { it.isString }?.booleanOrNull ?: invalidReadPage()
                header = base
            }
            CashImportReceiptSection.TRANSACTIONS -> transactions.add(value)
            CashImportReceiptSection.SOURCE_BINDINGS -> bindings.add(value)
            CashImportReceiptSection.ISSUES -> issues.add(value)
            CashImportReceiptSection.STATEMENT_ACCOUNT_MAPPINGS -> mappings.add(value)
            CashImportReceiptSection.RELATIONS -> relations.add(value)
            CashImportReceiptSection.ENTRIES -> entries.add(value)
            CashImportReceiptSection.RESOLVED_ISSUE_IDS -> resolvedIssues += positiveReadPageId(value)
            CashImportReceiptSection.RETIRED_ENTRY_IDS -> retiredEntries += readPageText(value)
            CashImportReceiptSection.PENDING_TRANSACTION_IDS -> pendingIds += positiveReadPageId(value)
        }
    }

    private data class ReceiptPageMetadata(val id: String, val ledgerSeq: String)
}

private fun readPageSequence(text: String): Long =
    text.toLongOrNull()?.takeIf { it >= 0 && it.toString() == text } ?: invalidReadPage()

private fun readPageText(value: JsonElement): String =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: invalidReadPage()

private fun positiveReadPageId(value: JsonElement): String =
    readPageText(value).also { if (readPageSequence(it) == 0L) invalidReadPage() }
