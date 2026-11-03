package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigInteger
import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.jooq.Condition
import org.jooq.impl.DSL
import kotlinx.serialization.json.*

class CashProjectionListService(private val database: Database, private val now: () -> Instant = Instant::now,
    private val afterStateRead: () -> Unit = {}, private val beforeReturn: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    fun list(token: String?, values: Map<String, String> = emptyMap(), requestPrincipal: Principal? = null): JsonObject {
        try {
            val principal = reads.currentContext(token)
            if (requestPrincipal != null) same(principal, requestPrincipal)
            val inputs = CashProjectionListInputs.parse(values)
            val result = database.read { connection ->
                if (!database.sqlite) connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
                CashProjectionListRepository(connection, principal.workspaceId!!, database.sqlite, afterStateRead).page(inputs)
            }
            beforeReturn(); same(principal, reads.currentContext(token))
            return result
        } catch (failure: AccessFailure) { throw failure }
        catch (failure: CashProjectionListFailure) { throw failure }
        catch (failure: CashProjectionListBooleanFailure) { throw failure }
        catch (failure: StorageFailure) { throw failure }
        catch (_: Exception) { throw CashProjectionListFailure("internal") }
    }
    private fun same(expected: Principal, actual: Principal) {
        if (expected.userId != actual.userId || expected.workspaceId != actual.workspaceId) throw AccessFailure(true)
    }
}

internal class CashProjectionListRepository(connection: Connection, private val workspace: String, private val sqlite: Boolean,
    private val afterStateRead: () -> Unit) {
    private val sql = connection.jooq(sqlite)
    private val p = CASH_PROJECTIONS.`as`("p")
    private val renderer = CurrentProjectionReadRepository(connection, workspace, sqlite) {}
    private fun active(): Pair<String, Long> {
        val s = CASH_PROJECTION_STATES
        val row = sql.select(s.AVAILABILITY, s.ACTIVE_DATASET_ID, s.PROJECTION_VERSION).from(s).where(s.WORKSPACE_ID.eq(workspace)).fetchOne()
            ?: throw CashProjectionListFailure("projection.unavailable")
        if (row[s.AVAILABILITY] != "ready" || row[s.ACTIVE_DATASET_ID].isNullOrEmpty()) throw CashProjectionListFailure("projection.unavailable")
        val result = row[s.ACTIVE_DATASET_ID]!! to row[s.PROJECTION_VERSION]!!
        afterStateRead(); return result
    }
    private fun base(dataset: String) = p.WORKSPACE_ID.eq(workspace).and(p.DATASET_ID.eq(dataset))
        .and(p.VISIBLE.isTrue.or(p.ATTENTION_COUNT.gt(0))).and(renderer.listActiveSources())
    private fun filtered(dataset: String, input: CashProjectionListInputs, categoryPath: String?): Condition {
        val f = input.filters; var condition = base(dataset)
        if (f["state"] != "all") condition = condition.and(p.STATE.eq(f["state"]))
        f["confirmation_status"]?.let { condition = condition.and(if (it == "pending") p.ATTENTION_COUNT.gt(0) else p.ATTENTION_COUNT.eq(0)) }
        if (f["hide_zero_amount"] == "True") {
            val nonzero = if (sqlite) DSL.function("ft_association_decimal_compare", Int::class.java,
                p.NET_AMOUNT, DSL.`val`("0")).ne(0)
            else p.NET_AMOUNT.ne(CashStoredDecimal("0"))
            condition = condition.and(p.NET_AMOUNT.isNull.or(nonzero))
        }
        input.start?.let { condition = condition.and(p.OCCURRED_AT.ge(it)) }
        input.end?.let { condition = condition.and(p.OCCURRED_AT.lt(it)) }
        input.account?.let { id ->
            if (id > BigInteger.valueOf(Long.MAX_VALUE)) {
                if (sqlite) throw CashProjectionListFailure("internal")
                throw StorageFailure("storage.connect")
            } else {
                val component = CASH_TRANSACTION_COMPONENTS; val member = CASH_PROJECTION_MEMBERS
                val members = DSL.selectOne().from(component).join(member).on(member.WORKSPACE_ID.eq(component.WORKSPACE_ID))
                    .and(member.CASH_TRANSACTION_ID.eq(component.CASH_TRANSACTION_ID))
                    .where(component.WORKSPACE_ID.eq(workspace)).and(component.ACCOUNT_ID.eq(id.toLong()))
                    .and(member.WORKSPACE_ID.eq(p.WORKSPACE_ID)).and(member.DATASET_ID.eq(p.DATASET_ID)).and(member.PROJECTION_ROW_ID.eq(p.ID))
                condition = condition.and(p.ACCOUNT_ID.eq(id.toLong()).or(DSL.exists(members)))
            }
        }
        f["currency"]?.let { condition = condition.and(p.CURRENCY.eq(it)) }
        if (categoryPath != null) condition = condition.and(p.CATEGORY_PATH.like("$categoryPath%"))
        else if (f["uncategorized"] == "True") condition = condition.and(p.CATEGORY_ID.isNull)
        f["economic_type"]?.let { condition = condition.and(p.ECONOMIC_TYPE.eq(it)) }
        f["transfer_subtype"]?.let { condition = condition.and(p.TRANSFER_SUBTYPE.eq(it)) }
        f["counterparty"]?.let { condition = condition.and(p.COUNTERPARTY.like("%$it%").or(p.NOTE.like("%$it%"))) }
        input.minimum?.let { number -> condition = condition.and(if (sqlite) DSL.function("ft_association_decimal_compare", Int::class.java,
            p.NET_AMOUNT, DSL.`val`(number.toPlainString())).ge(0) else p.NET_AMOUNT.ge(CashStoredDecimal(number.toPlainString()))) }
        input.maximum?.let { number -> condition = condition.and(if (sqlite) DSL.function("ft_association_decimal_compare", Int::class.java,
            p.NET_AMOUNT, DSL.`val`(number.toPlainString())).le(0) else p.NET_AMOUNT.le(CashStoredDecimal(number.toPlainString()))) }
        condition = when (f["composition"]) {
            "single" -> condition.and(p.HAS_PAYMENT_MIRROR.isFalse).and(p.HAS_REFUND_OFFSET.isFalse).and(p.HAS_TRANSFER_PAIR.isFalse)
            "payment_mirror" -> condition.and(p.HAS_PAYMENT_MIRROR.isTrue)
            "refund_offset" -> condition.and(p.HAS_REFUND_OFFSET.isTrue)
            "combined" -> condition.and(p.HAS_PAYMENT_MIRROR.isTrue.and(p.HAS_REFUND_OFFSET.isTrue)
                .or(p.HAS_PAYMENT_MIRROR.isTrue.and(p.HAS_TRANSFER_PAIR.isTrue)).or(p.HAS_REFUND_OFFSET.isTrue.and(p.HAS_TRANSFER_PAIR.isTrue)))
            else -> condition
        }
        return condition
    }
    fun page(input: CashProjectionListInputs): JsonObject {
        val indexed = input.index != null
        val early = if (indexed) active() else null
        if (early != null && input.version != null && input.version != early.second.toBigInteger()) throw CashProjectionListFailure("read_snapshot_changed")
        val cursor = input.cursor(workspace)
        val category = input.filters["category_id"]?.let { id ->
            sql.select(CASH_CATEGORIES.CATEGORY_PATH).from(CASH_CATEGORIES).where(CASH_CATEGORIES.WORKSPACE_ID.eq(workspace))
                .and(CASH_CATEGORIES.ID.eq(id)).fetchOne()?.value1() ?: invalidProjectionFilter()
        }
        val (dataset, version) = early ?: active()
        if (cursor != null && cursor.first != version.toBigInteger()) throw CashProjectionListFailure("read_snapshot_changed")
        val filter = filtered(dataset, input, category)
        val position = cursor?.let { p.SORT_AT.lt(it.second).or(p.SORT_AT.eq(it.second).and(p.PROJECTION_ID.lt(it.third))) } ?: DSL.trueCondition()
        val query = sql.selectFrom(p).where(filter.and(position)).orderBy(p.SORT_AT.desc(), p.PROJECTION_ID.desc())
            .limit(input.limit + if (indexed) 0 else 1)
        val rows = if (input.index != null && input.index > BigInteger.valueOf(Int.MAX_VALUE.toLong())) emptyList()
            else query.offset(input.index?.toInt() ?: 0).fetch()
        val selected = rows.take(input.limit)
        val monthly = if (input.version == null) monthly(filter, input.zone) else null
        val counts = if (monthly == null) {
            val count = sql.select(DSL.count(), DSL.sum(DSL.`when`(p.ATTENTION_COUNT.gt(0), 1).otherwise(0))).from(p).where(filter).fetchSingle()
            count.value1().toLong() to (count.value2()?.toLong() ?: 0)
        } else monthly.total to monthly.pending
        val total = counts.first.toBigInteger()
        return buildJsonObject {
            put("projection_version", version); put("items", JsonArray(selected.map { renderer.listItem(it, dataset, version) }))
            put("next_cursor", if (!indexed && rows.size > input.limit) {
                val last = selected.last(); JsonPrimitive(input.encodeCursor(workspace, version, last.sortAt!!, last.projectionId!!))
            } else JsonNull)
            put("page_size", input.limit); put("filters", sourceJson(input.filters)); put("filter_options", options(dataset))
            put("monthly_summaries", monthly?.summaries ?: JsonArray(emptyList())); put("pending_item_count", counts.second)
            put("start_index", input.index?.let(::JsonPrimitive) ?: JsonNull)
            put("total_count", if (indexed) JsonPrimitive(counts.first) else JsonNull)
            put("items_before", input.index?.let { JsonPrimitive(it.min(total)) } ?: JsonNull)
            put("items_after", input.index?.let { JsonPrimitive((total - it - selected.size.toBigInteger()).max(BigInteger.ZERO)) } ?: JsonNull)
            put("month_positions", if (indexed) monthly?.positions ?: JsonArray(emptyList()) else JsonArray(emptyList()))
        }
    }
    private fun monthly(filter: Condition, zone: ZoneId): CashProjectionMonthlyData {
        val accumulator = CashProjectionMonthlyAccumulator(zone)
        sql.select(p.OCCURRED_AT, p.ECONOMIC_TYPE, p.NET_AMOUNT, p.CURRENCY, p.STATISTICS_ELIGIBLE, p.ATTENTION_COUNT).from(p).where(filter)
            .orderBy(p.SORT_AT.desc(), p.PROJECTION_ID.desc()).fetchLazy().use { cursor -> cursor.forEach { row ->
                accumulator.add(row[p.OCCURRED_AT], row[p.ECONOMIC_TYPE], row[p.NET_AMOUNT]?.text?.toBigDecimal(), row[p.CURRENCY],
                    row[p.STATISTICS_ELIGIBLE] == true, row[p.ATTENTION_COUNT]!!)
            } }
        return accumulator.result()
    }
    private fun options(dataset: String): JsonObject {
        val categories = renderer.listCategories()
        val values = sql.selectDistinct(p.CURRENCY, p.ECONOMIC_TYPE, p.TRANSFER_SUBTYPE).from(p).where(base(dataset)).fetch()
        val currencies = values.mapNotNull { it[p.CURRENCY]?.trim { char -> char.code in pythonWhitespace }?.uppercase(Locale.ROOT)?.takeIf(String::isNotEmpty) }.distinct().sortedWith(cashRelationStringOrder)
        val economics = sortedMapOf<String, MutableSet<String>>(cashRelationStringOrder)
        values.forEach { row ->
            row[p.ECONOMIC_TYPE]?.trim { it.code in pythonWhitespace }?.takeIf(String::isNotEmpty)?.let { kind ->
                val subtypes = economics.getOrPut(kind) { sortedSetOf(cashRelationStringOrder) }
                row[p.TRANSFER_SUBTYPE]?.trim { it.code in pythonWhitespace }?.takeIf(String::isNotEmpty)?.let(subtypes::add)
            }
        }
        return buildJsonObject {
            put("categories", categories); put("currencies", JsonArray(currencies.map(::JsonPrimitive)))
            put("economic_types", JsonArray(economics.map { (kind, subtypes) -> buildJsonObject {
                put("economic_type", kind); put("transfer_subtypes", JsonArray(subtypes.map(::JsonPrimitive)))
            } }))
        }
    }
}
