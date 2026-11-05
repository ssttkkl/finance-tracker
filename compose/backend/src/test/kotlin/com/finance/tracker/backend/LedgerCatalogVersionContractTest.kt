package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import kotlin.test.*
import kotlinx.serialization.json.*

class LedgerCatalogVersionContractTest {
    private fun fixture(action: (Database, String, String) -> Unit) = withLedgerTestWorkspace(action)
    private fun version(db: Database, workspace: String): Long = db.read { c ->
        requireNotNull(c.jooq(db.sqlite).select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES)
            .where(WORKSPACES.ID.eq(workspace)).fetchOne(WORKSPACES.LEDGER_SEQ))
    }
    private fun account(name: String = "Cash") = buildJsonObject {
        put("name", name); put("type", "cash"); put("currencies", JsonArray(listOf(JsonPrimitive("CNY"))))
    }
    @Test fun accountCommandsAdvanceOnceAndReplayOrRejectionDoesNotAdvance() = fixture { db, workspace, token ->
        val service = AccountService(db)
        assertEquals(0L, version(db, workspace))
        val created = service.create(token, account(), "create")
        val id = created.getValue("account_id").jsonPrimitive.content
        assertEquals(1L, version(db, workspace))
        assertEquals(created, service.create(token, account(), "create"))
        service.get(token, id); service.list(token, "cash")
        assertEquals(1L, version(db, workspace))
        service.update(token, id, buildJsonObject {}, 1L, "revision")
        assertEquals(2L, version(db, workspace))
        assertEquals("account_changed", assertFailsWith<AccountFailure> {
            service.update(token, id, buildJsonObject { put("name", "stale") }, 1L, "stale")
        }.code)
        assertEquals("account_active", assertFailsWith<AccountFailure> { service.delete(token, id, 2L, "early-delete") }.code)
        assertEquals(2L, version(db, workspace))
        service.update(token, id, buildJsonObject { put("active", false) }, 2L, "deactivate")
        val deleted = service.delete(token, id, 3L, "delete")
        assertEquals(4L, version(db, workspace))
        assertEquals(deleted, service.delete(token, id, 3L, "delete"))
        assertEquals(created, service.create(token, account(), "create"))
        assertEquals(4L, version(db, workspace))
    }
    @Test fun lateAccountFailureRollsBackVersionAccountAndReceipt() = fixture { db, workspace, token ->
        class Rejected : RuntimeException()
        assertFailsWith<Rejected> {
            AccountService(db, beforeCommit = { throw Rejected() }).create(token, account(), "failed-create")
        }
        assertEquals(0L, version(db, workspace))
        db.read { c ->
            val sql = c.jooq(db.sqlite)
            assertFalse(sql.fetchExists(ACCOUNTS, ACCOUNTS.WORKSPACE_ID.eq(workspace)))
            assertFalse(sql.fetchExists(CASH_MUTATION_RECEIPTS, CASH_MUTATION_RECEIPTS.WORKSPACE_ID.eq(workspace)))
        }
        AccountService(db).create(token, account(), "failed-create")
        assertEquals(1L, version(db, workspace))
    }
    @Test fun categorySubtreeChangesAdvanceOnceAndNoopOrderOrRejectionDoesNotAdvance() = fixture { db, workspace, token ->
        val service = CategoryService(db)
        val root = service.create(token, "Root")
        val child = service.create(token, "Child", parentId = root.id)
        val target = service.create(token, "Target")
        assertEquals(3L, version(db, workspace))
        service.list(token)
        assertEquals(3L, version(db, workspace))
        service.reorder(token, target.id, "before")
        assertEquals(4L, version(db, workspace))
        service.update(token, root.id, parentId = target.id, changeParent = true)
        assertEquals(5L, version(db, workspace))
        assertEquals("category.cycle", assertFailsWith<CategoryFailure> {
            service.update(token, target.id, parentId = child.id, changeParent = true)
        }.code)
        service.reorder(token, child.id, "before")
        assertEquals(5L, version(db, workspace))
        service.update(token, child.id, name = "Renamed")
        assertEquals(6L, version(db, workspace))
        val impact = service.deletionImpact(token, child.id)
        service.delete(token, child.id, impact["revision"], impact["category_revision"], impact["direct_usage_count"], false)
        assertEquals(7L, version(db, workspace))
        val parentImpact = service.deletionImpact(token, target.id)
        assertEquals("category.has_children", assertFailsWith<CategoryFailure> {
            service.delete(token, target.id, parentImpact["revision"], parentImpact["category_revision"], parentImpact["direct_usage_count"], false)
        }.code)
        assertEquals(7L, version(db, workspace))
    }
    @Test fun aliasesAndFundingMappingsAdvanceOnlyWhenTheyWrite() = fixture { db, workspace, token ->
        val accounts = AccountService(db)
        val first = accounts.create(token, account(), "first").getValue("account_id").jsonPrimitive.content
        val second = accounts.create(token, account("Other"), "second").getValue("account_id").jsonPrimitive.content
        val aliases = AccountAliasService(db)
        aliases.add(token, buildJsonObject { put("type", "card_tail"); put("value", "1234"); put("account", "Cash") })
        assertEquals(3L, version(db, workspace))
        assertFailsWith<AccountAliasFailure> {
            aliases.add(token, buildJsonObject { put("value", "123"); put("account", "Cash") })
        }
        assertEquals(3L, version(db, workspace))
        assertFailsWith<StorageFailure> {
            db.write { c ->
                check(c.lockWorkspace(db.sqlite, workspace))
                CashFundingMappingRepository(c, workspace, db.sqlite).publish(listOf(
                    mapOf("source_type" to "alipay", "source_account_ref" to "a-valid", "account_id" to first),
                    mapOf("source_type" to "alipay", "source_account_ref" to "z-invalid", "account_id" to Long.MAX_VALUE.toString()),
                ), "admin")
            }
        }
        assertEquals(3L, version(db, workspace))
        db.read { c -> assertFalse(c.jooq(db.sqlite).fetchExists(STATEMENT_ACCOUNT_MAPPINGS,
            STATEMENT_ACCOUNT_MAPPINGS.WORKSPACE_ID.eq(workspace))) }
        fun publish(account: String) = db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace))
            CashFundingMappingRepository(c, workspace, db.sqlite).publish(listOf("a", "b").map {
                mapOf("source_type" to "alipay", "source_account_ref" to it, "account_id" to account)
            }, "admin")
        }
        publish(first)
        assertEquals(4L, version(db, workspace))
        publish(first)
        assertEquals(4L, version(db, workspace))
        publish(second)
        assertEquals(5L, version(db, workspace))
    }
    @Test fun deniedWritesAndSessionOperationsDoNotAdvanceFactsVersion() = fixture { db, workspace, token ->
        WorkspaceService(db).select(token, workspace)
        setAccountTestRole(db, workspace, "viewer")
        assertFailsWith<AccountFailure> { AccountService(db).create(token, account(), "denied") }
        assertFailsWith<AccessFailure> { CategoryService(db).create(token, "Denied") }
        assertFailsWith<AccessFailure> { AccountAliasService(db).add(token, buildJsonObject { put("value", "1234"); put("account", "Cash") }) }
        assertEquals(0L, version(db, workspace))
    }
}
