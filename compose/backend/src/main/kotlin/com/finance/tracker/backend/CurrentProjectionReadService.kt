package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashCurrentMemberDto
import com.finance.tracker.business.contracts.CashCurrentMembersDto
import java.sql.Connection
import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.backend.persistence.generated.tables.records.CashProjectionsRecord
import org.jooq.Condition
import org.jooq.impl.DSL
import java.time.Instant
import kotlinx.serialization.json.*

class CurrentProjectionNotFound(val record: Boolean) : RuntimeException("current_projection_not_found")
class CurrentProjectionUnavailable : RuntimeException("projection.unavailable")
class CashEvidenceNotFound : RuntimeException("not_found")
class CashEvidenceUnavailable : RuntimeException("projection.unavailable")

class CurrentProjectionReadService(
    private val database: Database,
    private val now: () -> Instant = Instant::now,
    private val afterStateRead: () -> Unit = {},
    private val beforeReturn: () -> Unit = {},
) {
    private val reads = ReadServices(database, now)

    fun location(token: String?, identifier: Any?, requestPrincipal: Principal? = null): JsonObject =
        read(token, requestPrincipal) { repository -> repository.location(stableIdentifier(identifier)) }

    fun detail(token: String?, projectionId: String, requestPrincipal: Principal? = null): JsonObject =
        read(token, requestPrincipal) { repository ->
            if (projectionId.codePointCount(0, projectionId.length) !in 1..128) throw CurrentProjectionNotFound(false)
            repository.detail(projectionId)
        }

    fun evidence(token: String?, projectionId: String, requestPrincipal: Principal? = null): JsonObject =
        try {
            read(token, requestPrincipal, mapStorageToUnavailable = false) { it.evidence(projectionId) }
        } catch (_: CurrentProjectionUnavailable) {
            throw CashEvidenceUnavailable()
        }

    private fun stableIdentifier(value: Any?): Long {
        val identifier = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is String -> if (value.length in 1..19 && value.all { it in '0'..'9' }) value.toLongOrNull() else null
            else -> null
        }
        return identifier?.takeIf { it > 0 } ?: throw CurrentProjectionNotFound(true)
    }

    private fun <T> read(token: String?, requestPrincipal: Principal?, mapStorageToUnavailable: Boolean = true,
        block: (CurrentProjectionReadRepository) -> T): T {
        try {
            val principal = reads.currentContext(token)
            if (requestPrincipal != null) sameContext(requestPrincipal, principal)
            val result = database.read { connection ->
                if (!database.sqlite) connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
                block(CurrentProjectionReadRepository(connection, principal.workspaceId!!, database.sqlite, afterStateRead))
            }
            beforeReturn()
            sameContext(principal, reads.currentContext(token))
            return result
        } catch (failure: StorageFailure) {
            if (!mapStorageToUnavailable) throw failure
            throw CurrentProjectionUnavailable()
        }
    }

    private fun sameContext(expected: Principal, actual: Principal) {
        if (expected.workspaceId != actual.workspaceId || expected.userId != actual.userId) throw AccessFailure(true)
    }
}

private data class CurrentProjectionState(val dataset: String, val version: Long)
private data class CurrentProjectionRow(
    val id: Long, val projectionId: String, val root: Long, val memberCount: Long,
    val accountId: Long?, val categoryId: String?, val attention: Long, val state: String,
    val value: JsonObject,
)
private data class CurrentProjectionMember(val id: Long, val revision: Long, val state: String, val source: String?)
private data class CurrentProjectionRelation(val kind: String, val subtype: String)

internal class CurrentProjectionReadRepository(
    private val connection: Connection,
    private val workspace: String,
    private val sqlite: Boolean,
    private val afterStateRead: () -> Unit,
) {
    private val sql = connection.jooq(sqlite)
    private val p = CASH_PROJECTIONS.`as`("p")
    private val member = CASH_PROJECTION_MEMBERS.`as`("member")
    private val fact = CASH_TRANSACTIONS.`as`("fact")

    private fun active(): CurrentProjectionState {
        val row = sql.select(CASH_PROJECTION_STATES.AVAILABILITY, CASH_PROJECTION_STATES.ACTIVE_DATASET_ID, CASH_PROJECTION_STATES.PROJECTION_VERSION)
            .from(CASH_PROJECTION_STATES).where(CASH_PROJECTION_STATES.WORKSPACE_ID.eq(workspace)).fetchOne()
            ?: throw CurrentProjectionUnavailable()
        val dataset = row[CASH_PROJECTION_STATES.ACTIVE_DATASET_ID]
        if (row[CASH_PROJECTION_STATES.AVAILABILITY] != "ready" || dataset.isNullOrEmpty()) throw CurrentProjectionUnavailable()
        val state = CurrentProjectionState(dataset, row[CASH_PROJECTION_STATES.PROJECTION_VERSION]!!)
        afterStateRead()
        return state
    }

    private fun activeSources(): Condition {
        val root = CASH_TRANSACTIONS.`as`("root")
        val deletedMember = CASH_PROJECTION_MEMBERS.`as`("deleted_member")
        val deletedFact = CASH_TRANSACTIONS.`as`("deleted_fact")
        return DSL.exists(DSL.selectOne().from(root).where(root.WORKSPACE_ID.eq(p.WORKSPACE_ID))
            .and(root.ID.eq(p.ROOT_CASH_TRANSACTION_ID)).and(root.DELETED_AT.isNull))
            .and(p.ID.notIn(DSL.select(deletedMember.PROJECTION_ROW_ID).from(deletedMember)
                .join(deletedFact).on(deletedFact.WORKSPACE_ID.eq(deletedMember.WORKSPACE_ID)).and(deletedFact.ID.eq(deletedMember.CASH_TRANSACTION_ID))
                .where(deletedMember.WORKSPACE_ID.eq(p.WORKSPACE_ID)).and(deletedMember.DATASET_ID.eq(p.DATASET_ID)).and(deletedFact.DELETED_AT.isNotNull)))
    }

    fun location(identifier: Long): JsonObject {
        val state = active()
        if (!sql.fetchExists(CASH_TRANSACTIONS, CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)
                .and(CASH_TRANSACTIONS.ID.eq(identifier)).and(CASH_TRANSACTIONS.DELETED_AT.isNull))) throw CurrentProjectionNotFound(true)
        val row = sql.select(p.PROJECTION_ID, p.ATTENTION_COUNT).from(p)
            .join(member).on(member.WORKSPACE_ID.eq(p.WORKSPACE_ID)).and(member.DATASET_ID.eq(p.DATASET_ID)).and(member.PROJECTION_ROW_ID.eq(p.ID))
            .where(p.WORKSPACE_ID.eq(workspace)).and(p.DATASET_ID.eq(state.dataset)).and(member.CASH_TRANSACTION_ID.eq(identifier))
            .and(activeSources()).fetchOne() ?: throw CurrentProjectionUnavailable()
        return buildJsonObject {
            put("transaction_id", identifier.toString()); put("projection_id", row[p.PROJECTION_ID]!!)
            put("attention_count", row[p.ATTENTION_COUNT]!!); put("projection_version", state.version)
        }
    }

    fun detail(projectionId: String): JsonObject {
        val state = active()
        val record = sql.selectFrom(p).where(p.WORKSPACE_ID.eq(workspace)).and(p.DATASET_ID.eq(state.dataset))
            .and(p.PROJECTION_ID.eq(projectionId)).and(p.VISIBLE.isTrue.or(p.ATTENTION_COUNT.gt(0)))
            .and(activeSources()).fetchOne() ?: throw CurrentProjectionNotFound(false)
        val row = projectionRow(record, state.version)
        val members = sql.select(fact.ID, fact.ROW_REVISION, fact.STATE, fact.SOURCE_TYPE).from(fact)
            .join(member).on(member.WORKSPACE_ID.eq(fact.WORKSPACE_ID)).and(member.CASH_TRANSACTION_ID.eq(fact.ID))
            .where(member.WORKSPACE_ID.eq(workspace)).and(member.DATASET_ID.eq(state.dataset)).and(member.PROJECTION_ROW_ID.eq(row.id))
            .orderBy(member.ORDINAL).fetch { CurrentProjectionMember(it[fact.ID]!!, it[fact.ROW_REVISION]!!, it[fact.STATE]!!, it[fact.SOURCE_TYPE]) }
        val ids = members.map { it.id }.toSet()
        if (members.size.toLong() != row.memberCount || row.root !in ids) throw CurrentProjectionUnavailable()
        val pending = attention(ids)
        val publicMembers = CashCurrentMembersDto(state.version, members.map {
            CashCurrentMemberDto(it.id.toString(), it.revision, if (it.state == "draft" || it.id in pending) "pending" else "confirmed")
        })
        val current = Json.encodeToJsonElement(publicMembers).jsonObject
        val relations = relations(state.dataset, row.id)
        val sources = members.mapNotNull { it.source?.takeIf(String::isNotEmpty) }.distinct()
        val projection = JsonObject(row.value + mapOf(
            "account" to account(row.accountId), "category" to category(row.categoryId),
            "source_types" to JsonArray(sources.map(::JsonPrimitive)),
            "composition" to JsonArray(relations.map { it.kind }.distinct().sortedWith(cashRelationStringOrder).map(::JsonPrimitive)),
            "accepted_relation_summary" to relationSummary(relations),
            "transfer" to transfer(state.dataset, row),
        ))
        return JsonObject(current + mapOf(
            "projection" to projection,
            "members" to JsonArray(current.getValue("members").jsonArray.mapIndexed { index, item ->
                JsonObject(item.jsonObject + ("state" to JsonPrimitive(members[index].state)))
            }),
            "issues" to issues(ids),
        ))
    }

    fun evidence(projectionId: String): JsonObject {
        val state = active()
        val record = sql.selectFrom(p).where(p.WORKSPACE_ID.eq(workspace)).and(p.DATASET_ID.eq(state.dataset))
            .and(p.PROJECTION_ID.eq(projectionId)).and(activeSources()).fetchOne() ?: throw CashEvidenceNotFound()
        val row = projectionRow(record, state.version)
        return CashEvidenceReadRepository(connection, workspace, sqlite).read(record, state.version, row.value) {
            transfer(state.dataset, row)
        }
    }

    private fun projectionRow(row: CashProjectionsRecord, version: Long): CurrentProjectionRow {
        val attention = row.attentionCount!!
        val state = row.state!!
        val root = row.rootCashTransactionId!!
        val value = buildJsonObject {
            for (field in listOf(p.PROJECTION_ID, p.COUNTERPARTY, p.NOTE, p.CURRENCY, p.ECONOMIC_TYPE, p.TRANSFER_SUBTYPE, p.SOURCE_TYPE,
                    p.RECORD_ID, p.HIDDEN_REASON, p.ENTRY_ID)) put(field.name, row[field]?.let(::JsonPrimitive) ?: JsonNull)
            put("occurred_at", row.occurredAt?.let { JsonPrimitive(manualInstantText(it)) } ?: JsonNull)
            put("amount", displayAmount(row.netAmount?.text))
            put("visible", row.visible!!); put("statistics_eligible", row.statisticsEligible!!)
            put("member_count", row.memberCount!!); put("attention_count", attention); put("state", state)
            put("confirmation_status", if (state == "draft" || attention != 0L) "pending" else "confirmed")
            put("projection_version", version); put("transaction_id", root.toString())
        }
        return CurrentProjectionRow(row.id!!, row.projectionId!!, root, row.memberCount!!,
            row.accountId, row.categoryId, attention, state, value)
    }

    private fun attention(ids: Set<Long>): Set<Long> = buildSet {
        val issue = CASH_TRANSACTION_ISSUES
        val relation = TRANSACTION_RELATIONS
        for (batch in ids.sorted().chunked(500)) {
            val pendingRelation = relation.WORKSPACE_ID.eq(workspace).and(relation.ACTIVE.isTrue).and(relation.STATE.eq("draft"))
            sql.select(issue.TRANSACTION_ID).from(issue).where(issue.WORKSPACE_ID.eq(workspace)).and(issue.TRANSACTION_ID.`in`(batch))
                .unionAll(DSL.select(relation.PRIMARY_TRANSACTION_ID).from(relation).where(pendingRelation).and(relation.PRIMARY_TRANSACTION_ID.`in`(batch)))
                .unionAll(DSL.select(relation.SECONDARY_TRANSACTION_ID).from(relation).where(pendingRelation).and(relation.SECONDARY_TRANSACTION_ID.`in`(batch)))
                .fetch().forEach { add(it.value1()!!) }
        }
    }

    private fun issues(ids: Set<Long>): JsonArray = JsonArray(buildList {
        val issue = CASH_TRANSACTION_ISSUES
        for (batch in ids.sorted().chunked(500)) {
            sql.select(issue.ID, issue.TRANSACTION_ID, issue.REVISION, issue.CODE, issue.FIELD_PATH).from(issue)
                .where(issue.WORKSPACE_ID.eq(workspace)).and(issue.TRANSACTION_ID.`in`(batch)).orderBy(issue.ID).fetch().forEach { row ->
                    val reason = try { cashImportIssue(row[issue.CODE]!!, fieldPath = row[issue.FIELD_PATH]) }
                        catch (_: IllegalArgumentException) { cashImportIssue("posting_incomplete") }
                    add(buildJsonObject {
                        put("transaction_id", row[issue.TRANSACTION_ID]!!.toString()); put("issue_id", row[issue.ID]!!.toString()); put("revision", row[issue.REVISION]!!)
                        put("code", reason.code); put("message", reason.message); put("field_path", reason.fieldPath?.let(::JsonPrimitive) ?: JsonNull)
                        put("blocking_scope", reason.blockingScope); put("next_action", reason.nextAction)
                        put("after_import_action", reason.afterImportAction?.let(::JsonPrimitive) ?: JsonNull)
                    })
                }
        }
    })

    private fun account(id: Long?): JsonElement {
        if (id == null) return JsonNull
        val a = ACCOUNTS
        val row = sql.select(a.ID, a.NAME, a.TYPE, a.ACTIVE).from(a).where(a.WORKSPACE_ID.eq(workspace)).and(a.ID.eq(id)).fetchOne() ?: return JsonNull
        return buildJsonObject { put("id", row[a.ID]!!); put("name", row[a.NAME]!!); put("type", row[a.TYPE]!!); put("active", row[a.ACTIVE]!!) }
    }

    private fun category(id: String?): JsonElement {
        if (id.isNullOrEmpty()) return JsonNull
        val c = CASH_CATEGORIES
        val categories = sql.select(c.ID, c.PARENT_ID, c.NAME).from(c).where(c.WORKSPACE_ID.eq(workspace)).fetch()
            .associate { it[c.ID]!! to (it[c.PARENT_ID] to it[c.NAME]!!) }
        val selected = categories[id] ?: return JsonNull
        val path = mutableListOf<JsonElement>()
        val visited = mutableSetOf<String>()
        var current: String? = id
        while (current != null && current in categories) {
            check(visited.add(current)) { "category cycle in current projection" }
            val item = categories.getValue(current)
            path += buildJsonObject { put("id", current); put("name", item.second) }
            current = item.first?.takeIf(String::isNotEmpty)
        }
        return buildJsonObject { put("id", id); put("name", selected.second); put("path", JsonArray(path.reversed())) }
    }

    private fun relations(dataset: String, rowId: Long): List<CurrentProjectionRelation> {
        val relation = CASH_PROJECTION_RELATIONS
        return sql.select(relation.KIND, relation.SUBTYPE).from(relation).where(relation.WORKSPACE_ID.eq(workspace))
            .and(relation.DATASET_ID.eq(dataset)).and(relation.PROJECTION_ROW_ID.eq(rowId)).orderBy(relation.ORDINAL)
            .fetch { CurrentProjectionRelation(it[relation.KIND]!!, it[relation.SUBTYPE]!!) }
    }

    private fun relationSummary(relations: List<CurrentProjectionRelation>): JsonArray = JsonArray(
        relations.groupingBy { it }.eachCount().entries.sortedWith { a, b ->
            cashRelationStringOrder.compare(a.key.kind, b.key.kind).takeIf { it != 0 }
                ?: cashRelationStringOrder.compare(a.key.subtype, b.key.subtype)
        }.map { (relation, count) -> buildJsonObject { put("kind", relation.kind); put("subtype", relation.subtype); put("count", count) } },
    )

    private fun transfer(dataset: String, row: CurrentProjectionRow): JsonElement {
        val published = CASH_PROJECTION_RELATIONS.`as`("published")
        val relation = TRANSACTION_RELATIONS.`as`("relation")
        val first = CASH_TRANSACTION_COMPONENTS.`as`("first_part")
        val second = CASH_TRANSACTION_COMPONENTS.`as`("second_part")
        val firstParent = CASH_TRANSACTIONS.`as`("first_parent")
        val secondParent = CASH_TRANSACTIONS.`as`("second_parent")
        val firstAccount = ACCOUNTS.`as`("first_account")
        val secondAccount = ACCOUNTS.`as`("second_account")
        val cash = sql.select(first.ACCOUNT_ID, first.AMOUNT, first.CURRENCY, second.ACCOUNT_ID, second.AMOUNT, second.CURRENCY).from(published)
            .join(relation).on(relation.WORKSPACE_ID.eq(published.WORKSPACE_ID)).and(relation.ID.eq(published.TRANSACTION_RELATION_ID))
            .join(first).on(first.WORKSPACE_ID.eq(relation.WORKSPACE_ID)).and(first.ID.eq(relation.PRIMARY_COMPONENT_ID))
            .join(second).on(second.WORKSPACE_ID.eq(relation.WORKSPACE_ID)).and(second.ID.eq(relation.SECONDARY_COMPONENT_ID))
            .join(firstParent).on(firstParent.WORKSPACE_ID.eq(first.WORKSPACE_ID)).and(firstParent.ID.eq(first.CASH_TRANSACTION_ID))
            .join(secondParent).on(secondParent.WORKSPACE_ID.eq(second.WORKSPACE_ID)).and(secondParent.ID.eq(second.CASH_TRANSACTION_ID))
            .join(firstAccount).on(firstAccount.WORKSPACE_ID.eq(first.WORKSPACE_ID)).and(firstAccount.ID.eq(first.ACCOUNT_ID))
            .join(secondAccount).on(secondAccount.WORKSPACE_ID.eq(second.WORKSPACE_ID)).and(secondAccount.ID.eq(second.ACCOUNT_ID))
            .where(published.WORKSPACE_ID.eq(workspace)).and(published.DATASET_ID.eq(dataset)).and(published.PROJECTION_ROW_ID.eq(row.id))
            .and(published.KIND.eq("transfer_pair")).orderBy(published.ORDINAL).limit(1).fetchOne()
        if (cash != null) return transferValue(
            Triple(account(cash[first.ACCOUNT_ID]), displayAmount(cash[first.AMOUNT]?.text), cash[first.CURRENCY]),
            Triple(account(cash[second.ACCOUNT_ID]), displayAmount(cash[second.AMOUNT]?.text), cash[second.CURRENCY]),
        )
        val funding = CASH_INVESTMENT_FUNDING_RELATIONS.`as`("funding")
        val part = CASH_TRANSACTION_COMPONENTS.`as`("part")
        val parent = CASH_TRANSACTIONS.`as`("parent")
        val event = INVESTMENT_EVENTS.`as`("event")
        val cashAccount = ACCOUNTS.`as`("cash_account")
        val investmentAccount = ACCOUNTS.`as`("investment_account")
        val funded = sql.select(funding.DIRECTION, part.ACCOUNT_ID, part.AMOUNT, part.CURRENCY, event.ACCOUNT_ID, event.TO_AMOUNT, event.FROM_AMOUNT, event.CURRENCY)
            .from(p).join(funding).on(funding.WORKSPACE_ID.eq(p.WORKSPACE_ID)).and(funding.ID.eq(p.FUNDING_RELATION_ID))
            .and(funding.STATUS.eq("accepted")).and(funding.ACTIVE_SLOT.eq("active"))
            .join(part).on(part.WORKSPACE_ID.eq(funding.WORKSPACE_ID)).and(part.ID.eq(funding.CASH_TRANSACTION_COMPONENT_ID))
            .join(parent).on(parent.WORKSPACE_ID.eq(part.WORKSPACE_ID)).and(parent.ID.eq(part.CASH_TRANSACTION_ID))
            .join(cashAccount).on(cashAccount.WORKSPACE_ID.eq(parent.WORKSPACE_ID)).and(cashAccount.ID.eq(part.ACCOUNT_ID))
            .join(event).on(event.WORKSPACE_ID.eq(funding.WORKSPACE_ID)).and(event.ID.eq(funding.INVESTMENT_EVENT_ID))
            .join(investmentAccount).on(investmentAccount.WORKSPACE_ID.eq(event.WORKSPACE_ID)).and(investmentAccount.ID.eq(event.ACCOUNT_ID))
            .where(p.WORKSPACE_ID.eq(workspace)).and(p.DATASET_ID.eq(dataset)).and(p.ID.eq(row.id))
            .and(p.ECONOMIC_TYPE.eq("internal_transfer")).and(p.TRANSFER_SUBTYPE.eq("bank_security_transfer")).fetchOne() ?: return JsonNull
        val incoming = funded[funding.DIRECTION] == "cash_to_investment"
        if (!incoming && funded[funding.DIRECTION] != "investment_to_cash") return JsonNull
        val amount = funded[if (incoming) event.TO_AMOUNT else event.FROM_AMOUNT] ?: return JsonNull
        val cashSide = Triple(account(funded[part.ACCOUNT_ID]), displayAmount(funded[part.AMOUNT]?.text), funded[part.CURRENCY])
        val investmentSide = Triple(account(funded[event.ACCOUNT_ID]), displayAmount(amount.text), funded[event.CURRENCY])
        return transferValue(if (incoming) cashSide else investmentSide, if (incoming) investmentSide else cashSide)
    }

    private fun transferValue(from: Triple<JsonElement, JsonElement, String?>, to: Triple<JsonElement, JsonElement, String?>) = buildJsonObject {
        put("from_account", from.first); put("from_amount", from.second); put("from_currency", from.third?.let(::JsonPrimitive) ?: JsonNull)
        put("to_account", to.first); put("to_amount", to.second); put("to_currency", to.third?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun displayAmount(value: String?): JsonElement {
        if (value == null) return JsonNull
        val amount = value.toBigDecimal()
        return JsonPrimitive(if (amount.signum() == 0) "0" else amount.stripTrailingZeros().toPlainString())
    }
}
