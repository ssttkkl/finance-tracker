package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashCurrentMemberDto
import com.finance.tracker.business.contracts.CashCurrentMembersDto
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import kotlinx.serialization.json.*

class CurrentProjectionNotFound(val record: Boolean) : RuntimeException("current_projection_not_found")
class CurrentProjectionUnavailable : RuntimeException("projection.unavailable")

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

    private fun stableIdentifier(value: Any?): Long {
        val identifier = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is String -> if (value.length in 1..19 && value.all { it in '0'..'9' }) value.toLongOrNull() else null
            else -> null
        }
        return identifier?.takeIf { it > 0 } ?: throw CurrentProjectionNotFound(true)
    }

    private fun <T> read(token: String?, requestPrincipal: Principal?, block: (CurrentProjectionReadRepository) -> T): T {
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
        } catch (_: StorageFailure) {
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
    private fun active(): CurrentProjectionState {
        val state = connection.prepareStatement("SELECT availability,active_dataset_id,projection_version FROM cash_projection_states WHERE workspace_id=?").use {
            it.setString(1, workspace)
            it.executeQuery().use { rows ->
                if (!rows.next() || rows.getString(1) != "ready" || rows.getString(2).isNullOrEmpty()) throw CurrentProjectionUnavailable()
                CurrentProjectionState(rows.getString(2), rows.getLong(3))
            }
        }
        afterStateRead()
        return state
    }

    private val activeSources = """EXISTS (SELECT 1 FROM cash_transactions root WHERE root.workspace_id=p.workspace_id
        AND root.id=p.root_cash_transaction_id AND root.deleted_at IS NULL)
        AND p.id NOT IN (SELECT member.projection_row_id FROM cash_projection_members member
            JOIN cash_transactions fact ON fact.workspace_id=member.workspace_id AND fact.id=member.cash_transaction_id
            WHERE member.workspace_id=p.workspace_id AND member.dataset_id=p.dataset_id AND fact.deleted_at IS NOT NULL)"""

    fun location(identifier: Long): JsonObject {
        val state = active()
        val exists = connection.prepareStatement("SELECT id FROM cash_transactions WHERE workspace_id=? AND id=? AND deleted_at IS NULL").use {
            it.setString(1, workspace); it.setLong(2, identifier); it.executeQuery().use(ResultSet::next)
        }
        if (!exists) throw CurrentProjectionNotFound(true)
        return connection.prepareStatement("""SELECT p.projection_id,p.attention_count FROM cash_projections p
            JOIN cash_projection_members member ON member.workspace_id=p.workspace_id AND member.dataset_id=p.dataset_id AND member.projection_row_id=p.id
            WHERE p.workspace_id=? AND p.dataset_id=? AND member.cash_transaction_id=? AND $activeSources""").use {
            it.setString(1, workspace); it.setString(2, state.dataset); it.setLong(3, identifier)
            it.executeQuery().use { rows ->
                if (!rows.next()) throw CurrentProjectionUnavailable()
                buildJsonObject {
                    put("transaction_id", identifier.toString()); put("projection_id", rows.getString(1))
                    put("attention_count", rows.getLong(2)); put("projection_version", state.version)
                }
            }
        }
    }

    fun detail(projectionId: String): JsonObject {
        val state = active()
        val row = connection.prepareStatement("""SELECT p.* FROM cash_projections p WHERE p.workspace_id=? AND p.dataset_id=?
            AND p.projection_id=? AND (p.visible=true OR p.attention_count>0) AND $activeSources""").use {
            it.setString(1, workspace); it.setString(2, state.dataset); it.setString(3, projectionId)
            it.executeQuery().use { rows ->
                if (!rows.next()) throw CurrentProjectionNotFound(false)
                projectionRow(rows, state.version)
            }
        }
        val members = connection.prepareStatement("""SELECT fact.id,fact.row_revision,fact.state,fact.source_type FROM cash_transactions fact
            JOIN cash_projection_members member ON member.workspace_id=fact.workspace_id AND member.cash_transaction_id=fact.id
            WHERE member.workspace_id=? AND member.dataset_id=? AND member.projection_row_id=? ORDER BY member.ordinal""").use {
            it.setString(1, workspace); it.setString(2, state.dataset); it.setLong(3, row.id)
            it.executeQuery().use { rows -> buildList {
                while (rows.next()) add(CurrentProjectionMember(rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getString(4)))
            } }
        }
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
            "members" to JsonArray(current.getValue("members").jsonArray.mapIndexed { index, member ->
                JsonObject(member.jsonObject + ("state" to JsonPrimitive(members[index].state)))
            }),
            "issues" to issues(ids),
        ))
    }

    private fun projectionRow(rows: ResultSet, version: Long): CurrentProjectionRow {
        val attention = rows.getLong("attention_count")
        val state = rows.getString("state")
        val root = rows.getLong("root_cash_transaction_id")
        val value = buildJsonObject {
            for (field in listOf("projection_id", "counterparty", "note", "currency", "economic_type", "transfer_subtype", "source_type", "record_id", "hidden_reason", "entry_id"))
                put(field, rows.getString(field)?.let(::JsonPrimitive) ?: JsonNull)
            put("occurred_at", rows.cashInstant("occurred_at", sqlite)?.let { JsonPrimitive(manualInstantText(it)) } ?: JsonNull)
            put("amount", displayAmount(rows.getString("net_amount")))
            put("visible", rows.getBoolean("visible")); put("statistics_eligible", rows.getBoolean("statistics_eligible"))
            put("member_count", rows.getLong("member_count")); put("attention_count", attention); put("state", state)
            put("confirmation_status", if (state == "draft" || attention != 0L) "pending" else "confirmed")
            put("projection_version", version); put("transaction_id", root.toString())
        }
        return CurrentProjectionRow(rows.getLong("id"), rows.getString("projection_id"), root, rows.getLong("member_count"),
            rows.nullableLong("account_id"), rows.getString("category_id"), attention, state, value)
    }

    private fun attention(ids: Set<Long>): Set<Long> = buildSet {
        for (batch in ids.sorted().chunked(500)) {
            val marks = batch.joinToString(",") { "?" }
            val queries = listOf(
                "SELECT transaction_id FROM cash_transaction_issues WHERE workspace_id=? AND transaction_id IN ($marks)",
                "SELECT primary_transaction_id FROM transaction_relations WHERE workspace_id=? AND active=true AND state='draft' AND primary_transaction_id IN ($marks)",
                "SELECT secondary_transaction_id FROM transaction_relations WHERE workspace_id=? AND active=true AND state='draft' AND secondary_transaction_id IN ($marks)",
            )
            connection.prepareStatement(queries.joinToString(" UNION ALL ")).use {
                var index = 1
                repeat(3) { _ -> it.setString(index++, workspace); batch.forEach { id -> it.setLong(index++, id) } }
                it.executeQuery().use { rows -> while (rows.next()) add(rows.getLong(1)) }
            }
        }
    }

    private fun issues(ids: Set<Long>): JsonArray = JsonArray(buildList {
        for (batch in ids.sorted().chunked(500)) connection.prepareStatement("""SELECT id,transaction_id,revision,code,field_path FROM cash_transaction_issues
            WHERE workspace_id=? AND transaction_id IN (${batch.joinToString(",") { "?" }}) ORDER BY id""").use {
            it.setString(1, workspace); batch.forEachIndexed { index, id -> it.setLong(index + 2, id) }
            it.executeQuery().use { rows -> while (rows.next()) {
                val reason = try { cashImportIssue(rows.getString("code"), fieldPath = rows.getString("field_path")) }
                    catch (_: IllegalArgumentException) { cashImportIssue("posting_incomplete") }
                add(buildJsonObject {
                    put("transaction_id", rows.getLong("transaction_id").toString()); put("issue_id", rows.getLong("id").toString()); put("revision", rows.getLong("revision"))
                    put("code", reason.code); put("message", reason.message); put("field_path", reason.fieldPath?.let(::JsonPrimitive) ?: JsonNull)
                    put("blocking_scope", reason.blockingScope); put("next_action", reason.nextAction)
                    put("after_import_action", reason.afterImportAction?.let(::JsonPrimitive) ?: JsonNull)
                })
            } }
        }
    })

    private fun account(id: Long?): JsonElement {
        if (id == null) return JsonNull
        return connection.prepareStatement("SELECT id,name,type,active FROM accounts WHERE workspace_id=? AND id=?").use {
            it.setString(1, workspace); it.setLong(2, id)
            it.executeQuery().use { rows -> if (!rows.next()) JsonNull else buildJsonObject {
                put("id", rows.getLong(1)); put("name", rows.getString(2)); put("type", rows.getString(3)); put("active", rows.getBoolean(4))
            } }
        }
    }

    private fun category(id: String?): JsonElement {
        if (id.isNullOrEmpty()) return JsonNull
        val categories = connection.prepareStatement("SELECT id,parent_id,name FROM cash_categories WHERE workspace_id=?").use {
            it.setString(1, workspace); it.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2) to rows.getString(3)) } }
        }
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

    private fun relations(dataset: String, rowId: Long): List<CurrentProjectionRelation> = connection.prepareStatement("""SELECT kind,subtype FROM cash_projection_relations
        WHERE workspace_id=? AND dataset_id=? AND projection_row_id=? ORDER BY ordinal""").use {
        it.setString(1, workspace); it.setString(2, dataset); it.setLong(3, rowId)
        it.executeQuery().use { rows -> buildList { while (rows.next()) add(CurrentProjectionRelation(rows.getString(1), rows.getString(2))) } }
    }

    private fun relationSummary(relations: List<CurrentProjectionRelation>): JsonArray = JsonArray(
        relations.groupingBy { it }.eachCount().entries.sortedWith { a, b ->
            cashRelationStringOrder.compare(a.key.kind, b.key.kind).takeIf { it != 0 }
                ?: cashRelationStringOrder.compare(a.key.subtype, b.key.subtype)
        }.map { (relation, count) -> buildJsonObject { put("kind", relation.kind); put("subtype", relation.subtype); put("count", count) } },
    )

    private fun transfer(dataset: String, row: CurrentProjectionRow): JsonElement {
        val cash = connection.prepareStatement("""SELECT first_part.account_id AS first_account,first_part.amount AS first_amount,first_part.currency AS first_currency,
            second_part.account_id AS second_account,second_part.amount AS second_amount,second_part.currency AS second_currency
            FROM cash_projection_relations published JOIN transaction_relations relation ON relation.workspace_id=published.workspace_id AND relation.id=published.transaction_relation_id
            JOIN cash_transaction_components first_part ON first_part.workspace_id=relation.workspace_id AND first_part.id=relation.primary_component_id
            JOIN cash_transaction_components second_part ON second_part.workspace_id=relation.workspace_id AND second_part.id=relation.secondary_component_id
            JOIN cash_transactions first_parent ON first_parent.workspace_id=first_part.workspace_id AND first_parent.id=first_part.cash_transaction_id
            JOIN cash_transactions second_parent ON second_parent.workspace_id=second_part.workspace_id AND second_parent.id=second_part.cash_transaction_id
            JOIN accounts first_account ON first_account.workspace_id=first_part.workspace_id AND first_account.id=first_part.account_id
            JOIN accounts second_account ON second_account.workspace_id=second_part.workspace_id AND second_account.id=second_part.account_id
            WHERE published.workspace_id=? AND published.dataset_id=? AND published.projection_row_id=? AND published.kind='transfer_pair' ORDER BY published.ordinal""").use {
            it.setString(1, workspace); it.setString(2, dataset); it.setLong(3, row.id)
            it.executeQuery().use { rows -> if (!rows.next()) null else transferValue(rows) }
        }
        if (cash != null) return cash
        return connection.prepareStatement("""SELECT funding.direction,part.account_id AS cash_account,part.amount AS cash_amount,part.currency AS cash_currency,
            event.account_id AS investment_account,event.to_amount,event.from_amount,event.currency AS investment_currency
            FROM cash_projections p JOIN cash_investment_funding_relations funding ON funding.workspace_id=p.workspace_id AND funding.id=p.funding_relation_id
                AND funding.status='accepted' AND funding.active_slot='active'
            JOIN cash_transaction_components part ON part.workspace_id=funding.workspace_id AND part.id=funding.cash_transaction_component_id
            JOIN cash_transactions parent ON parent.workspace_id=part.workspace_id AND parent.id=part.cash_transaction_id
            JOIN accounts cash_account ON cash_account.workspace_id=parent.workspace_id AND cash_account.id=part.account_id
            JOIN investment_events event ON event.workspace_id=funding.workspace_id AND event.id=funding.investment_event_id
            JOIN accounts investment_account ON investment_account.workspace_id=event.workspace_id AND investment_account.id=event.account_id
            WHERE p.workspace_id=? AND p.dataset_id=? AND p.id=? AND p.economic_type='internal_transfer' AND p.transfer_subtype='bank_security_transfer'""").use {
            it.setString(1, workspace); it.setString(2, dataset); it.setLong(3, row.id)
            it.executeQuery().use { rows ->
                if (!rows.next()) return@use JsonNull
                val incoming = rows.getString("direction") == "cash_to_investment"
                if (!incoming && rows.getString("direction") != "investment_to_cash") return@use JsonNull
                val amount = rows.getString(if (incoming) "to_amount" else "from_amount") ?: return@use JsonNull
                val cashSide = Triple(account(rows.getLong("cash_account")), displayAmount(rows.getString("cash_amount")), rows.getString("cash_currency"))
                val investmentSide = Triple(account(rows.getLong("investment_account")), displayAmount(amount), rows.getString("investment_currency"))
                transferValue(if (incoming) cashSide else investmentSide, if (incoming) investmentSide else cashSide)
            }
        }
    }

    private fun transferValue(rows: ResultSet): JsonObject = transferValue(
        Triple(account(rows.getLong("first_account")), displayAmount(rows.getString("first_amount")), rows.getString("first_currency")),
        Triple(account(rows.getLong("second_account")), displayAmount(rows.getString("second_amount")), rows.getString("second_currency")),
    )

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
