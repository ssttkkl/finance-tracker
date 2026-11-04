package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.records.CashInvestmentFundingRelationsRecord
import com.finance.tracker.backend.persistence.generated.tables.records.InvestmentEventsRecord
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.*

internal class FundingRelationFailure(message: String) : RuntimeException(message)

private data class FundingCash(val id: Long, val amount: BigDecimal, val currency: String?,
    val occurredAt: Instant, val recordType: String?, val counterparty: String)
private data class FundingCandidate(val cash: FundingCash, val window: Int, val institution: Boolean)

internal class FundingRelationRepository(private val context: CashSqlContext) {
    private val r = CASH_INVESTMENT_FUNDING_RELATIONS
    private val e = INVESTMENT_EVENTS
    private val t = CASH_TRANSACTIONS
    private val p = CASH_TRANSACTION_COMPONENTS
    private val a = ACCOUNTS
    private val workspace get() = context.workspace
    private val sql get() = context.sql
    private fun now() = context.now().truncatedTo(ChronoUnit.MICROS)
    private fun failure(message: String): Nothing = throw FundingRelationFailure(message)
    private fun incoming(event: InvestmentEventsRecord): Boolean {
        val from = event.fromAmount?.text?.toBigDecimal() ?: BigDecimal.ZERO
        val to = event.toAmount?.text?.toBigDecimal() ?: BigDecimal.ZERO
        return when {
            to.signum() > 0 && from.signum() == 0 -> true
            from.signum() > 0 && to.signum() == 0 -> false
            else -> failure("外部出入金必须恰有一个非零现金部分")
        }
    }
    private fun types(incoming: Boolean) = if (incoming) setOf("investment_out", "transfer_out") else setOf("investment_in", "transfer_in")
    private fun exact(cash: FundingCash, event: InvestmentEventsRecord): Boolean = cash.currency == event.currency &&
        cash.amount.abs(MathContext(28, RoundingMode.HALF_EVEN)).compareTo(
            (if (incoming(event)) event.toAmount else event.fromAmount)!!.text.toBigDecimal()) == 0
    private fun institution(cash: FundingCash, event: InvestmentEventsRecord): Boolean {
        val markers = when (event.sourceType) {
            "dfzq_pdf" -> listOf("银行转证券", "证券转银行")
            "ibkr_csv" -> listOf("interactive brokers")
            "schwab_csv" -> listOf("charles schwab")
            "usmart_hk_pdf" -> listOf("盈立证券", "盈立證券")
            else -> emptyList()
        }
        val folded = buildString { cash.counterparty.codePoints().forEach { append(pythonCaseFoldMapping(it) ?: String(Character.toChars(it))) } }
        return markers.any { it in folded }
    }
    private fun window(cash: FundingCash, event: InvestmentEventsRecord, incoming: Boolean): Int? {
        val cashDay = cash.occurredAt.atOffset(ZoneOffset.UTC).toLocalDate()
        val eventDay = event.occurredAt!!.atOffset(ZoneOffset.UTC).toLocalDate()
        val days = if (incoming) ChronoUnit.DAYS.between(cashDay, eventDay) else ChronoUnit.DAYS.between(eventDay, cashDay)
        return days.takeIf { it in 0..7 }?.toInt()
    }
    private fun candidates(event: InvestmentEventsRecord): List<FundingCandidate> {
        val incoming = incoming(event)
        return sql.select(p.ID, p.AMOUNT, p.CURRENCY, t.OCCURRED_AT, t.RECORD_TYPE, t.COUNTERPARTY).from(t)
            .join(p).on(p.WORKSPACE_ID.eq(t.WORKSPACE_ID)).and(p.CASH_TRANSACTION_ID.eq(t.ID))
            .join(a).on(a.WORKSPACE_ID.eq(p.WORKSPACE_ID)).and(a.ID.eq(p.ACCOUNT_ID))
            .where(t.WORKSPACE_ID.eq(workspace)).and(t.DELETED_AT.isNull).and(t.STATE.eq("posted"))
            .and(t.RECORD_ORIGIN.`in`("import", "legacy")).and(a.TYPE.eq("cash"))
            .and(t.RECORD_TYPE.`in`(types(incoming))).orderBy(t.OCCURRED_AT, t.ID).fetch().mapNotNull { row ->
                val cash = FundingCash(row[p.ID]!!, row[p.AMOUNT]!!.text.toBigDecimal(), row[p.CURRENCY],
                    row[t.OCCURRED_AT]!!, row[t.RECORD_TYPE], row[t.COUNTERPARTY].orEmpty())
                if ((cash.amount.signum() < 0) != incoming) return@mapNotNull null
                val days = window(cash, event, incoming) ?: return@mapNotNull null
                val institution = institution(cash, event)
                if (exact(cash, event) || institution) FundingCandidate(cash, days, institution) else null
            }
    }
    private fun selected(candidates: List<FundingCandidate>, event: InvestmentEventsRecord): List<FundingCandidate> {
        val institutions = candidates.filter { it.institution }
        if (institutions.isEmpty()) return candidates
        val exact = institutions.filter { exact(it.cash, event) }
        if (exact.isEmpty()) return institutions
        val nearest = exact.minOf { it.window }
        return exact.filter { it.window == nearest }
    }
    private fun cashOccupied(id: Long): Boolean {
        val relation = TRANSACTION_RELATIONS
        return sql.select(relation.ID).from(relation).where(relation.WORKSPACE_ID.eq(workspace))
            .and(relation.STATE.eq("confirmed")).and(relation.ACTIVE.isTrue)
            .and(relation.PRIMARY_COMPONENT_ID.eq(id).or(relation.SECONDARY_COMPONENT_ID.eq(id))).limit(1).fetchOne() != null
    }
    private fun unreviewed(row: CashInvestmentFundingRelationsRecord) = row.status == "pending_review" && row.createdBy == "system" && row.decidedBy.isNullOrEmpty()
    private fun store(row: CashInvestmentFundingRelationsRecord) {
        if (row.fields().none { field -> !CashRecordPolicy.valuesEqual(row[field], row.original(field)) }) return
        context.connection.markLedgerChanged(workspace)
        row.store()
    }
    private fun decide(row: CashInvestmentFundingRelationsRecord, status: String, actor: String, reason: String) {
        row.status = status; row.decidedBy = actor; row.decidedAt = now(); row.decisionReason = reason
    }
    private fun json(row: CashInvestmentFundingRelationsRecord): JsonObject = buildJsonObject {
        put("id", row.id!!); put("cash_transaction_component_id", row.cashTransactionComponentId!!)
        put("investment_event_id", row.investmentEventId!!); put("direction", row.direction); put("status", row.status)
        put("rule_id", row.ruleId); put("evidence", row.evidence ?: buildJsonObject {})
        put("created_by", row.createdBy); put("created_at", manualInstantText(row.createdAt!!))
        put("decided_by", row.decidedBy); put("decided_at", row.decidedAt?.let { JsonPrimitive(manualInstantText(it)) } ?: JsonNull)
        put("decision_reason", row.decisionReason)
    }
    fun pending(): List<JsonObject> {
        context.requireActive()
        return sql.selectFrom(r).where(r.WORKSPACE_ID.eq(workspace)).and(r.STATUS.eq("pending_review"))
            .orderBy(r.CREATED_AT, r.ID).fetch().map(::json)
    }
    fun scan(): List<JsonObject> {
        context.requireActive()
        val accepted = sql.select(r.CASH_TRANSACTION_COMPONENT_ID, r.INVESTMENT_EVENT_ID).from(r)
            .where(r.WORKSPACE_ID.eq(workspace)).and(r.STATUS.eq("accepted")).and(r.ACTIVE_SLOT.eq("active")).fetch()
        val acceptedCash = accepted.mapTo(mutableSetOf()) { it[r.CASH_TRANSACTION_COMPONENT_ID]!! }
        val acceptedEvents = accepted.mapTo(mutableSetOf()) { it[r.INVESTMENT_EVENT_ID]!! }
        val existing = sql.selectFrom(r).where(r.WORKSPACE_ID.eq(workspace)).and(r.ACTIVE_SLOT.eq("active"))
            .fetch().associateByTo(linkedMapOf()) { it.cashTransactionComponentId!! to it.investmentEventId!! }
        val events = sql.select(e.asterisk()).from(e).join(a).on(a.WORKSPACE_ID.eq(e.WORKSPACE_ID)).and(a.ID.eq(e.ACCOUNT_ID))
            .where(e.WORKSPACE_ID.eq(workspace)).and(a.TYPE.`in`("security", "crypto"))
            .and(e.RECORD_TYPE.eq("funding")).and(e.RECORD_SUBTYPE.eq("external")).orderBy(e.OCCURRED_AT, e.ID).fetchInto(e)
        val changed = mutableListOf<CashInvestmentFundingRelationsRecord>(); val affected = mutableSetOf<Long>()
        for (event in events) {
            if (event.id in acceptedEvents) {
                existing.values.firstOrNull { it.investmentEventId == event.id && it.status == "accepted" }?.let(changed::add)
                continue
            }
            val candidates = selected(candidates(event).filter { it.cash.id !in acceptedCash && !cashOccupied(it.cash.id) }, event)
            val ids = candidates.mapTo(mutableSetOf()) { it.cash.id }
            for (row in existing.values) {
                if (row.investmentEventId == event.id && unreviewed(row) && row.cashTransactionComponentId !in ids) {
                    decide(row, "rejected", "system", "no_longer_candidate"); store(row); changed.add(row)
                }
            }
            for (candidate in candidates) {
                val cash = candidate.cash
                val reason = when {
                    candidates.size != 1 -> ""
                    candidate.institution -> "unique_institution_name_candidate"
                    candidate.window == 0 && cash.recordType == if (incoming(event)) "investment_out" else "investment_in" -> "unique_strong_candidate"
                    else -> ""
                }
                val evidence = buildJsonObject {
                    put("business_day_window", candidate.window); put("candidate_count", candidates.size)
                    put("cash_record_type", cash.recordType)
                    put("match_keys", sourceJson(if (candidate.institution) listOf("institution_name", "direction", "business_day")
                        else listOf("amount", "currency", "direction", "business_day")))
                }
                val key = cash.id to event.id!!
                var row = existing[key]
                if (row != null) {
                    if (unreviewed(row)) {
                        row.evidence = evidence
                        if (reason.isNotEmpty()) {
                            decide(row, "accepted", "system", reason)
                            acceptedCash.add(cash.id); acceptedEvents.add(event.id!!); affected.add(cash.id)
                        }
                        store(row)
                    }
                    changed.add(row); continue
                }
                row = sql.newRecord(r).apply {
                    workspaceId = workspace; cashTransactionComponentId = cash.id; investmentEventId = event.id
                    direction = if (incoming(event)) "cash_to_investment" else "investment_to_cash"
                    status = if (reason.isNotEmpty()) "accepted" else "pending_review"
                    ruleId = "cash-investment-funding-v1"; this.evidence = evidence; activeSlot = "active"
                    createdBy = "system"; createdAt = now(); decidedBy = if (reason.isNotEmpty()) "system" else ""
                    decidedAt = if (reason.isNotEmpty()) now() else null; decisionReason = reason
                }
                store(row); existing[key] = row; changed.add(row)
                if (reason.isNotEmpty()) { acceptedCash.add(cash.id); acceptedEvents.add(event.id!!); affected.add(cash.id) }
            }
        }
        if (affected.isNotEmpty()) CashProjectionService.maintainIfReadyInSession(context, emptySet(), knownComponentIds = affected)
        return changed.map(::json)
    }
    private fun get(id: Long): CashInvestmentFundingRelationsRecord {
        context.requireActive()
        return sql.selectFrom(r).where(r.WORKSPACE_ID.eq(workspace)).and(r.ID.eq(id)).fetchOne()
            ?: failure("找不到资金调拨关系")
    }
    private fun available(row: CashInvestmentFundingRelationsRecord) {
        val record = sql.select(p.ID, p.AMOUNT, p.CURRENCY, t.OCCURRED_AT, t.RECORD_TYPE, t.COUNTERPARTY).from(p)
            .join(t).on(t.WORKSPACE_ID.eq(p.WORKSPACE_ID)).and(t.ID.eq(p.CASH_TRANSACTION_ID))
            .where(p.WORKSPACE_ID.eq(workspace)).and(p.ID.eq(row.cashTransactionComponentId))
            .and(t.DELETED_AT.isNull).and(t.STATE.eq("posted")).and(t.RECORD_ORIGIN.`in`("import", "legacy")).fetchOne()
        val event = sql.selectFrom(e).where(e.WORKSPACE_ID.eq(workspace)).and(e.ID.eq(row.investmentEventId)).fetchOne()
        if (record == null || event == null) failure("资金调拨关系端点不可用")
        val cash = FundingCash(record[p.ID]!!, record[p.AMOUNT]!!.text.toBigDecimal(), record[p.CURRENCY],
            record[t.OCCURRED_AT]!!, record[t.RECORD_TYPE], record[t.COUNTERPARTY].orEmpty())
        if (event.recordType != "funding" || event.recordSubtype != "external" || cash.recordType !in types(incoming(event)) ||
            window(cash, event, incoming(event)) == null || !(exact(cash, event) || institution(cash, event)))
            failure("资金调拨关系端点不满足规范化匹配规则")
        if (cashOccupied(cash.id)) failure("现金端点已被确认关系占用")
        if (sql.select(r.ID).from(r).where(r.WORKSPACE_ID.eq(workspace)).and(r.STATUS.eq("accepted")).and(r.ACTIVE_SLOT.eq("active"))
            .and(r.ID.ne(row.id)).and(r.CASH_TRANSACTION_COMPONENT_ID.eq(cash.id).or(r.INVESTMENT_EVENT_ID.eq(event.id))).limit(1).fetchOne() != null)
            failure("端点已被确认关系占用")
    }
    private fun validateReason(reason: String, original: DecodedPythonJson?) {
        val value = (original?.value as? JsonObject)?.get("reason") ?: JsonPrimitive(reason)
        try { manualOriginalUtf8(value, original, normalized = reason) }
        catch (failure: ManualCashBusinessFailure) { throw FundingRelationFailure(failure.message.orEmpty()) }
    }
    fun confirm(id: Long, actor: String, reason: String = "", original: DecodedPythonJson? = null): JsonObject {
        val row = get(id)
        if (row.status != "pending_review") failure("只能确认待审核资金调拨关系")
        available(row); validateReason(reason, original); decide(row, "accepted", actor, reason); store(row)
        CashProjectionService.maintainIfReadyInSession(context, emptySet(), knownComponentIds = setOf(row.cashTransactionComponentId!!))
        return json(row)
    }
    fun reject(id: Long, actor: String, reason: String = "", original: DecodedPythonJson? = null): JsonObject {
        val row = get(id)
        if (row.status != "pending_review") failure("只能驳回待审核资金调拨关系")
        validateReason(reason, original); decide(row, "rejected", actor, reason); store(row)
        return json(row)
    }
}

class FundingRelationService(private val database: Database, private val now: () -> Instant = Instant::now) {
    private val reads = ReadServices(database, now)
    private fun same(expected: Principal, actual: Principal) {
        if (expected.userId != actual.userId || expected.workspaceId != actual.workspaceId) throw AccessFailure(true)
    }
    internal fun pending(token: String?, request: Principal): JsonObject {
        val principal = reads.currentContext(token); same(request, principal)
        val result = database.read { connection ->
            same(principal, reads.context(connection, token, false))
            JsonArray(FundingRelationRepository(CashSqlContext(connection, principal.workspaceId!!, database.sqlite, now)).pending())
        }
        same(principal, reads.currentContext(token))
        return buildJsonObject { put("items", result) }
    }
    internal fun write(token: String?, request: Principal, operation: String, id: java.math.BigInteger? = null,
        value: JsonElement = JsonNull, original: DecodedPythonJson? = null): JsonObject {
        val reasonValue = (value as? JsonObject)?.get("reason")
        val reason = when {
            reasonValue == null || reasonValue == JsonNull -> ""
            reasonValue is JsonPrimitive && reasonValue.isString -> reasonValue.content.trim { it.code in pythonWhitespace }
            else -> throw FundingRelationFailure("reason_required")
        }.ifEmpty { if (operation == "reject") "rejected" else "" }
        return database.write { connection ->
            val workspace = request.workspaceId ?: throw AccessFailure(true)
            if (!connection.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
            val principal = reads.lockedContext(connection, token, workspace, write = true); same(request, principal)
            val repository = FundingRelationRepository(CashSqlContext(connection, workspace, database.sqlite, now))
            val result = if (operation == "scan") buildJsonObject { put("items", JsonArray(repository.scan())) }
                else {
                    if (id == null || id < java.math.BigInteger.valueOf(Long.MIN_VALUE) || id > java.math.BigInteger.valueOf(Long.MAX_VALUE)) {
                        if (database.sqlite) throw FundingRelationInternalFailure() else throw StorageFailure("storage.connect")
                    }
                    if (operation == "confirm") repository.confirm(id.toLong(), principal.userId, reason, original)
                    else repository.reject(id.toLong(), principal.userId, reason, original)
                }
            same(request, reads.lockedContext(connection, token, workspace, write = true))
            result
        }
    }
}

internal class FundingRelationInternalFailure : RuntimeException()
internal class FundingRelationHttpFailure(val status: Int, message: String) : RuntimeException(message)
internal class FundingRelationPathFailure(val input: String, val oversized: Boolean = false) : RuntimeException()
internal fun fundingRelationId(raw: String): java.math.BigInteger {
    val text = raw.trim { it.code in pythonWhitespace && it.code !in 0x1c..0x1f }
    if (!Regex("[+-]?[0-9](?:_?[0-9])*+(?:\\.0++)?").matches(text)) throw FundingRelationPathFailure(raw)
    val digits = text.substringBefore('.').replace("_", "").removePrefix("+").removePrefix("-").trimStart('0')
    if (digits.length > 4300) throw FundingRelationPathFailure(raw, oversized = true)
    return text.substringBefore('.').replace("_", "").toBigInteger()
}
