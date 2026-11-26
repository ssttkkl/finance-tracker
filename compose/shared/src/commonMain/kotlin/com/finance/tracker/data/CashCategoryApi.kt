package com.finance.tracker.data

import com.finance.tracker.client.CashCategoryParentChange
import com.finance.tracker.client.CashCategoryPatch
import com.finance.tracker.client.EditCashCategory
import io.ktor.http.HttpMethod

suspend fun FinanceApiClient.fetchLedgerOptions(): LedgerOptionsDto = request(HttpMethod.Get, "/api/v1/cash-ledger/options")

suspend fun FinanceApiClient.fetchCashCategories(): CashCategoryDirectoryDto = request(HttpMethod.Get, "/api/v1/cash-categories")

suspend fun FinanceApiClient.createCashCategory(name: String, parentId: String?, description: String, expectedRevision: Long): CashCategoryDirectoryItemDto =
    cashMaintenance { createCategory(expectedWorkspace, EditCashCategory(name, parentId, description, expectedRevision)) }

suspend fun FinanceApiClient.updateCashCategory(id: String, name: String, parentId: String?, description: String, expectedRevision: Long): CashCategoryDirectoryItemDto =
    cashMaintenance { updateCategory(expectedWorkspace, id, CashCategoryPatch(expectedRevision, name, CashCategoryParentChange(parentId), description)) }

suspend fun FinanceApiClient.reorderCashCategory(id: String, direction: String, expectedRevision: Long, targetId: String? = null): CashCategoryDirectoryItemDto =
    cashMaintenance { reorderCategory(expectedWorkspace, id, direction, expectedRevision, targetId) }

suspend fun FinanceApiClient.fetchCashCategoryDeletionImpact(id: String): CashCategoryDeleteImpactDto =
    cashMaintenance { categoryDeletionImpact(expectedWorkspace, id) }

suspend fun FinanceApiClient.deleteCashCategory(id: String, impact: CashCategoryDeleteImpactDto): CashCategoryDeleteResultDto =
    cashMaintenance { deleteCategory(expectedWorkspace, id, impact.revision, impact.categoryRevision, impact.directUsageCount) }
