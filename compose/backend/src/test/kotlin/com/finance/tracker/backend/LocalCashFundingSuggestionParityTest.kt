package com.finance.tracker.backend

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.suggestLocalCashFundingAccounts
import com.finance.tracker.backend.persistence.generated.tables.references.ACCOUNTS
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.*

@RunWith(Parameterized::class)
class LocalCashFundingSuggestionParityTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun backends() = listOf(arrayOf("sqlite"), arrayOf("postgresql"))
    }
    private fun group(ref: String, source: String = "icbc_debit", identity: String = "123456") = CashFundingGroup(
        ref, source, "file_account", "owner", identity, "Synthetic", currencies = listOf("CNY"), rowRefs = listOf("f:r1"))

    @Test fun mappingsAndAliasesMatchTheOriginalPrecedenceAndRemainReadOnly() = withCashWriteSetTestWorkspace(backend) { db, workspace, account ->
        db.write { c -> c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.NAME, "Active").where(ACCOUNTS.ID.eq(account)).execute() }
        val inactiveAccount = deletionAccount(db, workspace)
        db.write { c ->
            c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.ACTIVE, false).where(ACCOUNTS.ID.eq(inactiveAccount)).execute()
            for ((source, kind, key, target) in listOf(
                listOf("icbc_debit", "funding_group_v1", "current", account),
                listOf("icbc_debit", "funding_group_v1", "inactive-current", inactiveAccount),
                listOf("icbc_debit", "file_account", "123456", account)))
                deletionInsert(c, db, "statement_account_mappings", mapOf("workspace_id" to workspace, "source_type" to source,
                    "identity_kind" to kind, "source_account_key" to key, "account_id" to target, "confirmed_by" to "synthetic",
                    "revision" to 1L, "created_at" to deletionTime, "updated_at" to deletionTime))
            deletionInsert(c, db, "account_aliases", mapOf("workspace_id" to workspace, "alias_type" to "account_identifier",
                "alias_value" to "123456", "account_id" to account, "created_at" to deletionTime, "updated_at" to deletionTime))
        }
        val groups = listOf(group("current"), group("inactive-current"), group("legacy"), group("alias", "ccb_debit"), group("wechat", "wechat"))
        fun check(inactive: Boolean = false) {
            val before = detailDatabaseState(db)
            db.read { c ->
                val source = LedgerSnapshotDto(workspace, "0", CASH_RULES_VERSION, LedgerSnapshotRepository(c, workspace, db.sqlite).facts())
                val expected = if (inactive) groups.associate { it.sourceAccountRef to CashFundingSuggestion(null, null, "none") }
                else mapOf("current" to CashFundingSuggestion(account.toString(), 1, "confirmed_mapping"),
                    "inactive-current" to CashFundingSuggestion(null, null, "none"), "legacy" to CashFundingSuggestion(account.toString(), 1, "legacy_mapping"),
                    "alias" to CashFundingSuggestion(account.toString(), 1, "account_alias"), "wechat" to CashFundingSuggestion(null, null, "none"))
                assertEquals(expected, CashImportAccountMappings(c, workspace, db.sqlite).suggest(groups))
                assertEquals(expected, suggestLocalCashFundingAccounts(groups, source))
            }
            assertEquals(before, detailDatabaseState(db))
        }
        check()
        db.write { c -> c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.ACTIVE, false).where(ACCOUNTS.ID.eq(account)).execute() }
        check(inactive = true)
    }

    @Test fun everyAliasGroupAcrossThreeSqlBatchesMatchesTheSnapshot() = withCashWriteSetTestWorkspace(backend) { db, workspace, account ->
        val groups = (0..404).map { group("ref-$it", "ccb_debit", "123456${it.toString().padStart(4, '0')}") }
        db.write { c ->
            for (group in groups) deletionInsert(c, db, "account_aliases", mapOf("workspace_id" to workspace,
                "alias_type" to "account_identifier", "alias_value" to group.identityKey, "account_id" to account,
                "created_at" to deletionTime, "updated_at" to deletionTime))
        }
        val before = detailDatabaseState(db)
        db.read { c ->
            val source = LedgerSnapshotDto(workspace, "0", CASH_RULES_VERSION, LedgerSnapshotRepository(c, workspace, db.sqlite).facts())
            val expected = groups.associate { it.sourceAccountRef to CashFundingSuggestion(account.toString(), 1, "account_alias") }
            assertEquals(expected, CashImportAccountMappings(c, workspace, db.sqlite).suggest(groups))
            assertEquals(expected, suggestLocalCashFundingAccounts(groups, source))
        }
        assertEquals(before, detailDatabaseState(db))
    }
}
