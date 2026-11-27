package com.finance.tracker.cli

import com.finance.tracker.business.contracts.ImportCurrencyRequirementDto
import com.finance.tracker.client.CashAccountCurrenciesResult
import com.finance.tracker.client.CashAccountType
import com.finance.tracker.client.ClientAccountOperations
import com.finance.tracker.client.ClientAccountRequest
import com.finance.tracker.client.ClientAccountTransport
import com.finance.tracker.client.ClientHttpBody
import com.finance.tracker.client.ClientHttpRequest
import com.finance.tracker.client.cashAccountFact
import com.finance.tracker.client.cashAccountSelection
import com.finance.tracker.client.isSelectableCashAccount
import com.finance.tracker.client.missingCashAccountCurrencies
import com.finance.tracker.client.validateCashAccountCurrencyChange
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun Suite.accountPorts(token: String): ClientAccountOperations =
    ClientAccountOperations(object : ClientAccountTransport {
        override suspend fun <Response> send(request: ClientAccountRequest, response: DeserializationStrategy<Response>): Response {
            val separator = if ('?' in request.path) '&' else '?'
            val path = "${request.path}${separator}workspace_id=${encodeComponent(workspace)}"
            val result = http.request(ClientHttpRequest(request.method, path, token, request.body?.let { ClientHttpBody.Json(it) },
                request.key?.let { mapOf("Idempotency-Key" to it) }.orEmpty()))
            check(result.status in 200..299) { "Unexpected account HTTP ${result.status}" }
            return benchmarkJson.decodeFromString(response, result.body)
        }
    }, ::encodeComponent)

internal suspend fun Suite.accountBenchmarks() {
    val token = access.login(ownerEmail, password).accessToken
    access.selectWorkspace(token, workspace)
    val ports = accountPorts(token)
    select(workspace)
    val snapshot = withClient(origin) { it.ledger(workspace).ledger.snapshot }
    check(snapshot.facts.accounts.size == 50 && count("rows") == 3000)
    scenario("account.directory") {
        val value = withClient(origin) { measure { it.accounts(workspace) } }
        check(value.size == 50 && value.any { it.id.toString() == account })
    }
    scenario("account.selection") {
        val value = measure {
            val fact = checkNotNull(cashAccountFact(snapshot.facts.accounts, account))
            cashAccountSelection(fact, canWrite = true).also { check(isSelectableCashAccount(it)) }
        }
        check(value.accountId == account && value.currencies == listOf("CNY") && value.capabilities["can_update"] == true)
    }
    scenario("account.detail") {
        val value = withClient(origin) { measure { it.account(workspace, account) } }
        check(value.accountId == account && value.accountRevision > 0 && value.name == "Perf account 0")
    }
    scenario("account.balances") {
        val value = withClient(origin) { measure { it.accountDetails(workspace, account) } }
        check(value.account.accountId == account && value.balances.single().currency == "CNY")
        check(cashEquals(value.balances.single().balance, "-163.20"))
    }
    var savedReceipt: Pair<String, String>? = null
    suspend fun originalReceipt(): Pair<String, String> {
        savedReceipt?.let { return it }
        val created = withCashWrites(origin) { it.createAccount(workspace, "Perf receipt account", CashAccountType.CASH, listOf("CNY")) }
        val createdId = checkNotNull(created.receipt.account).accountId
        host.removeAccount(workspace, createdId)
        return (created.receipt.receiptId to created.operationId).also { savedReceipt = it }
    }
    scenario("account.receipt") {
        val (receiptId, _) = originalReceipt()
        val mark = markRequests()
        val value = measure { ports.receipt(receiptId) }
        check(value.committed && value.operationKind == "account_create" && value.receiptId == receiptId && writeCount(mark) == 0)
    }
    scenario("account.receipt-key") {
        val (receiptId, key) = originalReceipt()
        val mark = markRequests()
        val value = measure { ports.receiptByKey(key) }
        check(value.committed && value.operationKind == "account_create" && value.receiptId == receiptId && writeCount(mark) == 0)
    }
    scenario("account.create") {
        withClient(origin) { it.ledger(workspace) }
        val name = "Perf created account $index"
        val value = withCashWrites(origin) {
            measure { it.createAccount(workspace, name, CashAccountType.CASH, listOf("CNY")) }
        }
        val created = checkNotNull(value.receipt.account)
        check(value.receipt.committed && created.name == name && created.currencies == listOf("CNY"))
        check(count("accounts") == 51)
        host.removeAccount(workspace, created.accountId)
    }
    scenario("account.edit") {
        val before = ports.read(account)
        val name = "Perf edited account $index"
        val value = measure {
            ports.edit(ClientAccountOperations.update(account, before.accountRevision, buildJsonObject { put("name", name) },
                newReference(), ::encodeComponent))
        }
        check(value.accountId == account && value.name == name && value.accountRevision > before.accountRevision)
        val restore = ClientAccountOperations.update(account, value.accountRevision, buildJsonObject { put("name", before.name) },
            newReference(), ::encodeComponent)
        check(ports.edit(restore).name == before.name)
    }
    scenario("account.currencies") {
        val before = ports.read(account)
        check(before.currencies == listOf("CNY"))
        val value = measure {
            val missing = missingCashAccountCurrencies(before, listOf("CNY", "USD"))
            validateCashAccountCurrencyChange(before)
            val request = ClientAccountOperations.currencyRequirements(listOf(ImportCurrencyRequirementDto(newReference(),
                before.accountId, before.accountRevision, missing)))
            ports.ensureCurrencies(request).single()
        }
        val updated = checkNotNull(value.account)
        check(value.status == "updated" && updated.currencies.toSet() == setOf("CNY", "USD"))
        val restore = ClientAccountOperations.update(account, updated.accountRevision, buildJsonObject {
            put("currencies", JsonArray(listOf(JsonPrimitive("CNY"))))
        }, newReference(), ::encodeComponent)
        check(ports.edit(restore).currencies == listOf("CNY"))
    }
    var noOpBatch: Pair<String, Long>? = null
    scenario("account.currencies-noop") {
        val (batchId, revision) = noOpBatch ?: run {
            val name = "account-noop.csv"
            host.writeFixture(name, listOf(
                "支付宝交易记录明细查询", "支付宝账户：[owner@example.test]", "共1笔记录",
                "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号",
                "2025-08-01 00:00:00,日用百货,Perf Merchant,account,Perf synthetic,支出,20.00,支付宝余额,交易成功,1,merchant",
            ).joinToString("\n"))
            val batch = withCashImport(origin) { it.prepare(workspace, listOf(statementSource(fixturePath(name), "alipay"))) }
            check(batch.files.single().status == "parsed")
            (batch.batchId to batch.revision.toLong()).also { noOpBatch = it }
        }
        val existing = ports.read(account)
        val version = host.ledgerRevision(workspace) as Int
        val mark = markRequests()
        val value = withImportAccounts(origin) {
            measure { it.ensureCurrencies(workspace, batchId, revision, account, existing.accountRevision, listOf(" cny ", "CNY")) }
        }
        check(value is CashAccountCurrenciesResult.Unchanged && value.account == existing && writeCount(mark) == 0)
        check(host.ledgerRevision(workspace) == version && ports.read(account).accountRevision == existing.accountRevision)
    }
}
