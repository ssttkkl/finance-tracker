package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.sql.Connection
import java.time.Instant
import org.jooq.impl.DSL

internal data class CashFundingGroup(val sourceAccountRef: String, val sourceType: String, val identityKind: String,
    val originKey: String, val identityKey: String, val displayName: String,
    val currencies: List<String>, val rowRefs: List<String>)
internal data class CashFundingMappingKey(val sourceType: String, val identityKind: String, val sourceAccountKey: String)
internal data class CashFundingMapping(val id: Long, val accountId: Long?, val confirmedBy: String, val revision: Long,
    val createdAt: Instant, val updatedAt: Instant)
internal data class CashFundingSuggestion(val accountId: String?, val expectedAccountRevision: Long?, val basis: String)

internal class CashFundingMappingRepository(private val connection: Connection, private val workspace: String,
    private val sqlite: Boolean, private val now: () -> Instant = Instant::now) {
    companion object { const val KIND = "funding_group_v1" }
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
                sql.insertInto(table).set(table.WORKSPACE_ID, workspace).set(table.SOURCE_TYPE, key.sourceType)
                    .set(table.IDENTITY_KIND, key.identityKind).set(table.SOURCE_ACCOUNT_KEY, key.sourceAccountKey)
                    .set(table.ACCOUNT_ID, account).set(table.CONFIRMED_BY, userId).set(table.REVISION, 1L)
                    .set(table.CREATED_AT, timestamp).set(table.UPDATED_AT, timestamp).execute()
            } else if (old.accountId != account) {
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
    private fun legacy(group: CashFundingGroup): CashFundingMappingKey? {
        val kind = when {
            group.sourceType == "wechat" && group.originKey == CashSourceScope.WECHAT_DEFAULT_SCOPE &&
                group.identityKind == "payment_method" -> "payment_method"
            group.identityKind == "file_account" && group.sourceType in setOf("icbc_credit", "icbc_debit") -> "file_account"
            group.identityKind == "file_account" && group.sourceType == "icbc_asia" -> "account_identifier"
            else -> return null
        }
        return CashFundingMappingKey(group.sourceType, kind, group.identityKey)
    }

    fun suggest(groups: List<CashFundingGroup>): Map<String, CashFundingSuggestion> {
        val keys = groups.associate { it.sourceAccountRef to CashFundingMappingKey(it.sourceType,
            CashFundingMappingRepository.KIND, it.sourceAccountRef) }
        val legacy = groups.associate { it.sourceAccountRef to legacy(it) }
        val mappings = repository.lookup(keys.values + legacy.values.filterNotNull())
        val aliasGroups = groups.filter { it.identityKind == "file_account" && it.identityKey.matches(Regex("[0-9]{5,}")) }
            .associate { it.sourceAccountRef to ("account_identifier" to it.identityKey) }
        val aliases = mutableMapOf<Pair<String, String>, MutableSet<Long>>()
        val table = ACCOUNT_ALIASES
        val sql = connection.jooq(sqlite)
        val aliasOrder = compareBy<Pair<String, String>, String>(cashRelationStringOrder) { it.first }
            .thenBy(cashRelationStringOrder) { it.second }
        for (batch in aliasGroups.values.distinct().sortedWith(aliasOrder).chunked(200)) {
            for (row in sql.select(table.ALIAS_TYPE, table.ALIAS_VALUE, table.ACCOUNT_ID).from(table)
                    .where(table.WORKSPACE_ID.eq(workspace)).and(DSL.row(table.ALIAS_TYPE, table.ALIAS_VALUE)
                        .`in`(batch.map { DSL.row(it.first, it.second) })).fetch())
                aliases.getOrPut(row.value1()!! to row.value2()!!) { mutableSetOf() }.add(row.value3()!!)
        }
        val ids = (mappings.values.mapNotNull { it.accountId } + aliases.values.flatten()).distinct().sorted()
        data class Account(val id: Long, val revision: Long, val active: Boolean, val type: String)
        val accounts = mutableMapOf<Long, Account>()
        val a = ACCOUNTS
        for (batch in ids.chunked(200)) for (row in sql.select(a.ID, a.REVISION, a.ACTIVE, a.TYPE).from(a)
                .where(a.WORKSPACE_ID.eq(workspace)).and(a.ID.`in`(batch)).fetch())
            accounts[row.value1()!!] = Account(row.value1()!!, row.value2()!!, row.value3()!!, row.value4()!!)
        return groups.associate { group ->
            val ref = group.sourceAccountRef
            val current = mappings[keys.getValue(ref)]
            val old = legacy[ref]?.let(mappings::get)
            val mapping = current ?: old
            val basis = if (current != null) "confirmed_mapping" else "legacy_mapping"
            val candidates = if (mapping != null) setOfNotNull(mapping.accountId) else aliases[aliasGroups[ref]].orEmpty()
            val eligible = candidates.mapNotNull(accounts::get).filter { it.active && it.type in setOf("cash", "loan", "lend") }
            val account = eligible.singleOrNull()
            ref to CashFundingSuggestion(account?.id?.toString(), account?.revision,
                if (account == null) "none" else if (mapping != null) basis else "account_alias")
        }
    }

    fun publish(choices: List<Map<String, Any?>>, userId: String) = repository.publish(choices, userId)
}
