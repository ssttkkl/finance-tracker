package com.finance.tracker.client

import com.finance.tracker.business.contracts.AccountCommandViewDto
import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.business.contracts.ImportCurrencyRequirementDto
import com.finance.tracker.business.contracts.ImportCurrencyRequirementResultDto
import com.finance.tracker.business.contracts.ImportCurrencyRequirementsViewDto
import com.finance.tracker.business.contracts.LedgerAccountFactDto
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.domain.buildLocalCashAccountReferences
import com.finance.tracker.business.domain.isCashWriteSetKey
import com.finance.tracker.business.domain.pythonWhitespace
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ClientAccountRequest(val method: String, val path: String, val body: String? = null, val key: String? = null)

interface ClientAccountTransport {
    suspend fun <Response> send(request: ClientAccountRequest, response: DeserializationStrategy<Response>): Response
}

class ClientAccountOperations(
    private val transport: ClientAccountTransport,
    private val encode: (String) -> String,
) {
    suspend fun read(accountId: String): AccountCommandViewDto =
        transport.send(ClientAccountRequest("GET", accountPath(accountId, encode)), AccountCommandViewDto.serializer())

    suspend fun edit(request: ClientAccountRequest): AccountCommandViewDto =
        transport.send(request, AccountCommandViewDto.serializer())

    suspend fun ensureCurrencies(request: ClientAccountRequest): List<ImportCurrencyRequirementResultDto> =
        transport.send(request, ImportCurrencyRequirementsViewDto.serializer()).results

    suspend fun receipt(receiptId: String): CashMutationReceiptDto {
        requireReference(receiptId)
        val receipt = transport.send(ClientAccountRequest("GET", "/api/v1/cash-mutations/receipts/${encode(receiptId)}"),
            CashMutationReceiptDto.serializer())
        if (receipt.receiptId != receiptId) throw ClientOperationFailure("protocol_error")
        return receipt
    }

    suspend fun receiptByKey(key: String): CashMutationReceiptDto {
        requireKey(key)
        return transport.send(ClientAccountRequest("GET", "/api/v1/cash-mutations/receipts/by-key?key=${encode(key)}"),
            CashMutationReceiptDto.serializer())
    }

    companion object {
        fun create(name: String, type: String, currencies: List<String>, key: String): ClientAccountRequest {
            requireKey(key)
            return ClientAccountRequest("POST", "/api/v1/accounts", buildJsonObject {
                put("name", name)
                put("type", type)
                put("currencies", JsonArray(currencies.map(::JsonPrimitive)))
            }.toString(), key)
        }

        fun update(id: String, revision: Long, patch: JsonObject, key: String, encode: (String) -> String): ClientAccountRequest {
            requireRevision(revision)
            requireKey(key)
            if (patch.keys.any { it !in setOf("name", "active", "currencies") }) invalidAccountInput()
            return ClientAccountRequest("PATCH", accountPath(id, encode), buildJsonObject {
                put("expected_revision", revision)
                put("patch", patch)
            }.toString(), key)
        }

        fun delete(id: String, revision: Long, key: String, encode: (String) -> String): ClientAccountRequest {
            requireRevision(revision)
            requireKey(key)
            return ClientAccountRequest("DELETE", accountPath(id, encode),
                buildJsonObject { put("expected_revision", revision) }.toString(), key)
        }

        fun currencyRequirements(requirements: List<ImportCurrencyRequirementDto>): ClientAccountRequest {
            if (requirements.isEmpty()) invalidAccountInput()
            requirements.forEach {
                requireRevision(it.expectedRevision)
                requireKey(it.idempotencyKey)
                requireReference(it.accountId)
            }
            return ClientAccountRequest("POST", "/api/v1/cash-import/accounts/currency-requirements", buildJsonObject {
                put("items", clientJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ImportCurrencyRequirementDto.serializer()), requirements))
            }.toString())
        }
    }
}

private fun accountPath(accountId: String, encode: (String) -> String): String {
    requireReference(accountId)
    return "/api/v1/accounts/by-id/${encode(accountId)}"
}

private fun requireReference(value: String) {
    if (value.isBlank()) invalidAccountInput()
    try { value.encodeToByteArray(throwOnInvalidSequence = true) }
    catch (_: CharacterCodingException) { invalidAccountInput() }
}

private fun requireKey(key: String) {
    if (!isCashWriteSetKey(key)) invalidAccountInput()
}

private fun requireRevision(revision: Long) {
    if (revision < 1 || revision == Long.MAX_VALUE) invalidAccountInput()
}

private fun invalidAccountInput(): Nothing = throw ClientOperationFailure("plan_input_invalid", 400)

fun cashAccountSelection(account: LedgerAccountFactDto, canWrite: Boolean): AccountCommandViewDto {
    val reference = buildLocalCashAccountReferences(listOf(account)).single()
    return AccountCommandViewDto(account.id, account.name, account.type, account.active, reference.currencies.orEmpty(),
        account.revision.toLong(), false, mapOf("can_update" to canWrite, "can_delete" to false))
}

fun cashAccountDirectory(accounts: List<LedgerAccountFactDto>): List<AccountReferenceDto> =
    buildLocalCashAccountReferences(accounts)

fun cashAccountFact(accounts: List<LedgerAccountFactDto>, accountId: String): LedgerAccountFactDto? =
    accounts.singleOrNull { it.id == accountId && it.type in setOf("cash", "loan", "lend") }

fun isSelectableCashAccount(account: AccountCommandViewDto): Boolean =
    !account.deleted && account.active && account.type in setOf("cash", "loan", "lend")

fun missingCashAccountCurrencies(account: AccountCommandViewDto, required: List<String>): List<String> {
    if (required.size > 100) throw ClientOperationFailure("account_payload_invalid", 422)
    return required.map { value ->
        val currency = value.trim { it.code in pythonWhitespace }
        if (!currency.matches(Regex("[A-Za-z]{3}"))) throw ClientOperationFailure("account_payload_invalid", 422)
        currency.uppercase()
    }.distinct().filterNot { it in account.currencies }
}

fun validateCashAccountCurrencyChange(account: AccountCommandViewDto) {
    if (account.deleted || account.capabilities["can_update"] != true)
        throw ClientOperationFailure("workspace_forbidden", 403)
}
