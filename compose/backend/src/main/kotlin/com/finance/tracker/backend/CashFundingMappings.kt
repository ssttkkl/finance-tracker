package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.sql.Connection
import java.time.Instant
import org.jooq.impl.DSL

internal typealias CashFundingMappingKey = com.finance.tracker.business.domain.CashFundingMappingKey
internal data class CashFundingMapping(val id: Long, val accountId: Long?, val confirmedBy: String, val revision: Long,
    val createdAt: Instant, val updatedAt: Instant)
internal typealias CashFundingSuggestion = com.finance.tracker.business.domain.CashFundingSuggestion

internal class CashFundingMappingRepository(private val connection: Connection, private val workspace: String,
    private val sqlite: Boolean, private val now: () -> Instant = Instant::now) {
    companion object { const val KIND = com.finance.tracker.business.domain.CashFundingSuggestionRules.KIND }
    private val keyOrder = compareBy<CashFundingMappingKey, String>(cashRelationStringOrder) { it.sourceType }
        .thenBy(cashRelationStringOrder) { it.identityKind }.thenBy(cashRelationStringOrder) { it.sourceAccountKey }
    fun lookup(identities: Collection<CashFundingMappingKey>): Map<CashFundingMappingKey, CashFundingMapping> {
        val table = STATEMENT_ACCOUNT_MAPPINGS
        val result = linkedMapOf<CashFundingMappingKey, CashFundingMapping>()
        for (batch in identities.distinct().sortedWith(keyOrder).chunked(200)) {
            val rows = connection.jooq(sqlite).selectFrom(table).where(table.WORKSPACE_ID.eq(workspace))
                .and(DSL.row(table.SOURCE_TYPE, table.IDENTITY_KIND, table.SOURCE_ACCOUNT_KEY)
                    .`in`(batch.map { DSL.row(it.sourceType, it.identityKind, it.sourceAccountKey) })).fetch()
            for (row in rows) result[CashFundingMappingKey(row.sourceType!!, row.identityKind!!, row.sourceAccountKey!!)] =
                CashFundingMapping(row.id!!, row.accountId, row.confirmedBy!!, row.revision!!, row.createdAt!!, row.updatedAt!!)
        }
        return result
    }

    fun publish(choices: List<Map<String, Any?>>, userId: String) {
        val selected = linkedMapOf<CashFundingMappingKey, Long>()
        for (choice in choices) {
            val key = CashFundingMappingKey(choice.getValue("source_type") as String, KIND,
                choice.getValue("source_account_ref") as String)
            val account = pythonIntegerValue(sourceJson(choice.getValue("account_id"))).longValueExact()
            if (key in selected && selected[key] != account) throw IllegalArgumentException("funding_mapping_conflict")
            selected[key] = account
        }
        val existing = lookup(selected.keys)
        val table = STATEMENT_ACCOUNT_MAPPINGS
        val sql = connection.jooq(sqlite)
        for ((key, account) in selected.entries.sortedWith { a, b -> keyOrder.compare(a.key, b.key) }) {
            val old = existing[key]
            if (old == null) {
                val timestamp = now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                connection.markLedgerChanged(workspace)
                sql.insertInto(table).set(table.WORKSPACE_ID, workspace).set(table.SOURCE_TYPE, key.sourceType)
                    .set(table.IDENTITY_KIND, key.identityKind).set(table.SOURCE_ACCOUNT_KEY, key.sourceAccountKey)
                    .set(table.ACCOUNT_ID, account).set(table.CONFIRMED_BY, userId).set(table.REVISION, 1L)
                    .set(table.CREATED_AT, timestamp).set(table.UPDATED_AT, timestamp).execute()
            } else if (old.accountId != account) {
                connection.markLedgerChanged(workspace)
                sql.update(table).set(table.ACCOUNT_ID, account).set(table.CONFIRMED_BY, userId)
                    .set(table.REVISION, Math.addExact(old.revision, 1))
                    .set(table.UPDATED_AT, now().truncatedTo(java.time.temporal.ChronoUnit.MICROS))
                    .where(table.WORKSPACE_ID.eq(workspace)).and(table.ID.eq(old.id)).execute()
            }
        }
    }
}

internal class CashImportAccountMappings(private val connection: Connection, private val workspace: String,
    private val sqlite: Boolean) {
    private val repository = CashFundingMappingRepository(connection, workspace, sqlite)
    private val rules = com.finance.tracker.business.domain.CashFundingSuggestionRules(
        { keys -> repository.lookup(keys).mapValues { (_, mapping) -> mapping.accountId?.toString() } },
        ::aliases, ::accounts)

    fun suggest(groups: List<CashFundingGroup>): Map<String, CashFundingSuggestion> = rules.suggest(groups)

    private fun aliases(keys: Collection<Pair<String, String>>): Map<Pair<String, String>, Set<String>> {
        val aliases = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        val table = ACCOUNT_ALIASES
        val sql = connection.jooq(sqlite)
        val aliasOrder = compareBy<Pair<String, String>, String>(cashRelationStringOrder) { it.first }
            .thenBy(cashRelationStringOrder) { it.second }
        for (batch in keys.distinct().sortedWith(aliasOrder).chunked(200)) {
            for (row in sql.select(table.ALIAS_TYPE, table.ALIAS_VALUE, table.ACCOUNT_ID).from(table)
                    .where(table.WORKSPACE_ID.eq(workspace)).and(DSL.row(table.ALIAS_TYPE, table.ALIAS_VALUE)
                        .`in`(batch.map { DSL.row(it.first, it.second) })).fetch())
                aliases.getOrPut(row.value1()!! to row.value2()!!) { mutableSetOf() }.add(row.value3()!!.toString())
        }
        return aliases
    }

    private fun accounts(ids: Collection<String>): Map<String, com.finance.tracker.business.domain.CashFundingSuggestionAccount> {
        val accounts = mutableMapOf<String, com.finance.tracker.business.domain.CashFundingSuggestionAccount>()
        val a = ACCOUNTS
        for (batch in ids.map(String::toLong).distinct().sorted().chunked(200))
            for (row in connection.jooq(sqlite).select(a.ID, a.REVISION, a.ACTIVE, a.TYPE).from(a)
                    .where(a.WORKSPACE_ID.eq(workspace)).and(a.ID.`in`(batch)).fetch()) {
                val id = row.value1()!!.toString()
                accounts[id] = com.finance.tracker.business.domain.CashFundingSuggestionAccount(id, row.value2()!!, row.value3()!!, row.value4()!!)
            }
        return accounts
    }

    fun publish(choices: List<Map<String, Any?>>, userId: String) {
        val eligible = choices.filter { it["source_type"] != "wechat" }
        if (eligible.isNotEmpty()) repository.publish(eligible, userId)
    }
}
