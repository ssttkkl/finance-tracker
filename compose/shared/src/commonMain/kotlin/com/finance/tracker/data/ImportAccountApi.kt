package com.finance.tracker.data

import kotlinx.serialization.json.JsonObject

suspend fun FinanceApiClient.listCashImportAccounts(): List<ImportAccountListItemDto> = ImportAccountApi(this).list()

suspend fun FinanceApiClient.readImportAccount(accountId: String): ImportAccountDto = ImportAccountApi(this).read(accountId)

suspend fun FinanceApiClient.createImportAccount(name: String, type: String, currencies: List<String>, idempotencyKey: String): ImportAccountDto =
    ImportAccountApi(this).create(name, type, currencies, idempotencyKey)

suspend fun FinanceApiClient.updateImportAccount(accountId: String, expectedRevision: Long, patch: JsonObject, idempotencyKey: String): ImportAccountDto =
    ImportAccountApi(this).update(accountId, expectedRevision, patch, idempotencyKey)

suspend fun FinanceApiClient.deleteImportAccount(accountId: String, expectedRevision: Long, idempotencyKey: String): ImportAccountDto =
    ImportAccountApi(this).delete(accountId, expectedRevision, idempotencyKey)

suspend fun FinanceApiClient.ensureImportAccountCurrencies(requirements: List<ImportCurrencyRequirementDto>): List<ImportCurrencyRequirementResultDto> =
    ImportAccountApi(this).ensureCurrencies(requirements)
