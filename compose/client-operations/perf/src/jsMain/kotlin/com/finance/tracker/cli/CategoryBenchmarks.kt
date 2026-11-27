package com.finance.tracker.cli

import com.finance.tracker.client.CashCategoryPatch
import com.finance.tracker.client.EditCashCategory
import com.finance.tracker.client.cashCategoryDirectory

internal suspend fun Suite.categoryBenchmarks() {
    val workspace = categoryWorkspace
    select(workspace)
    val snapshot = withClient(origin) { it.ledger(workspace).ledger.snapshot }
    check(count("categories", workspace) == 1000 && count("rows", workspace) == 10000)
    val first = fixture.categories[0] as String
    val second = fixture.categories[1] as String
    val unused = fixture.categories[999] as String
    val rows = fixture.categoryRows.slice(0, 100)
    val entries = Array(100) { rows[it].entryId as String }.toList()

    scenario("category.directory") {
        val value = measure { cashCategoryDirectory(snapshot) }
        check(value.items.size == 1000 && value.revision == 1L && value.items.first().id == first)
    }
    scenario("category.create") {
        val name = "Perf added category $index"
        val input = EditCashCategory(name, null, "Synthetic category description", categoryRevision(workspace))
        val value = withMaintenance(origin) { measure { it.createCategory(workspace, input) } }
        check(value.name == name && value.parentId == null && value.description == input.description)
        check(count("categories", workspace) == 1001)
        withMaintenance(origin) { categories ->
            val impact = categories.categoryDeletionImpact(workspace, value.id)
            categories.deleteCategory(workspace, value.id, impact.revision, impact.categoryRevision, impact.directUsageCount)
        }
    }
    scenario("category.edit") {
        val name = "Perf renamed category $index"
        val value = withMaintenance(origin) {
            measure { it.updateCategory(workspace, first, CashCategoryPatch(categoryRevision(workspace), name = name)) }
        }
        check(value.id == first && value.name == name && value.path == listOf(name))
        withMaintenance(origin) {
            check(it.updateCategory(workspace, first, CashCategoryPatch(categoryRevision(workspace), name = "Perf Category 0000")).id == first)
        }
    }
    scenario("category.reorder") {
        val value = withMaintenance(origin) {
            measure { it.reorderCategory(workspace, second, "before", categoryRevision(workspace), null) }
        }
        check(value.id == second && value.sortOrder == 0)
        withMaintenance(origin) {
            check(it.reorderCategory(workspace, second, "after", categoryRevision(workspace), null).sortOrder == 1)
        }
    }
    scenario("category.delete-impact") {
        val value = withMaintenance(origin) { measure { it.categoryDeletionImpact(workspace, first) } }
        check(value.categoryId == first && value.directUsageCount > 0 && value.childCount == 0)
    }
    scenario("category.delete") {
        val original = host.captureCategory(unused)
        val impact = withMaintenance(origin) { it.categoryDeletionImpact(workspace, unused) }
        check(impact.directUsageCount == 0 && impact.childCount == 0)
        val value = withMaintenance(origin) {
            measure { it.deleteCategory(workspace, unused, impact.revision, impact.categoryRevision, impact.directUsageCount) }
        }
        check(value.categoryId == unused && value.clearedTransactionCount == 0 && count("categories", workspace) == 999)
        host.restoreCategory(original)
    }
    scenario("category.batch-classify") {
        val base = ledgerRevision(workspace)
        val value = withMaintenance(origin) { measure { it.categorize(workspace, entries, base, second) } }
        check(value.projectionCount == 100 && value.updatedTransactionCount == 100 &&
            value.categoryId == second && value.projectionVersion > base)
        check(host.categoryAssignmentCount(workspace, entries.toTypedArray(), second) == 100)
        withMaintenance(origin) {
            val restored = it.categorize(workspace, entries, value.projectionVersion, null)
            check(restored.projectionCount == 100 && restored.updatedTransactionCount == 100 && restored.categoryId == null)
        }
        check(host.categoryAssignmentCount(workspace, entries.toTypedArray(), null) == 100)
    }
    scenario("category.cash-delete-impact") {
        val base = ledgerRevision(workspace)
        val value = withMaintenance(origin) { measure { it.deletionImpact(workspace, entries, base) } }
        check(value.projectionCount == 100 && value.transactionCount == 100 && value.relationGroupCount == 0)
        check(count("rows", workspace) == 10000)
    }
    scenario("category.batch-delete") {
        val base = ledgerRevision(workspace)
        val value = withMaintenance(origin) { measure { it.delete(workspace, entries, base) } }
        check(value.deleted && value.projectionCount == 100 && value.transactionCount == 100 &&
            value.relationGroupCount == 0 && value.projectionVersion > base)
        check(count("rows", workspace) == 9900)
        host.restoreDeletedRows(workspace, rows)
        check(count("rows", workspace) == 10000)
    }
    select(this.workspace)
}

private fun Suite.categoryRevision(workspace: String): Long = (host.categoryRevision(workspace) as Int).toLong()

private fun Suite.ledgerRevision(workspace: String): Long = (host.ledgerRevision(workspace) as Int).toLong()
