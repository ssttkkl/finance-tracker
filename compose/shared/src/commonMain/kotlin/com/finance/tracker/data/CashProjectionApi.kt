package com.finance.tracker.data

import io.ktor.http.HttpMethod

suspend fun FinanceApiClient.fetchCashAccounts(): List<AccountDto> =
    request<AccountListDto>(HttpMethod.Get, "/api/v1/accounts?view=cash").items

suspend fun FinanceApiClient.classifyCashProjections(ids: List<String>, projectionVersion: Long, categoryId: String?): CashClassificationResultDto =
    cashMaintenance { categorize(expectedWorkspace, ids, projectionVersion, categoryId) }

suspend fun FinanceApiClient.fetchCashProjectionDeleteImpact(ids: List<String>, projectionVersion: Long): CashProjectionDeleteImpactDto =
    cashMaintenance { deletionImpact(expectedWorkspace, ids, projectionVersion) }

suspend fun FinanceApiClient.deleteCashProjections(ids: List<String>, projectionVersion: Long): CashProjectionDeleteResultDto =
    cashMaintenance { delete(expectedWorkspace, ids, projectionVersion) }
