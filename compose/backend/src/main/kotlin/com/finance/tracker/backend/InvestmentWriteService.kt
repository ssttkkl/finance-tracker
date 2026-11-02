package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import kotlinx.serialization.json.*

internal class InvestmentEncodingFailure(message: String) : RuntimeException(message)
internal class InvestmentWriteInternalFailure : RuntimeException()
private class InvestmentCommandRejected(val result: JsonObject) : RuntimeException()
class InvestmentWriteService(private val database: Database, private val now: () -> Instant = Instant::now,
    private val beforeReturn: () -> Unit = {}, private val resultTransform: (JsonObject) -> JsonObject = { it },
    private val beforeWrite: () -> Unit = {}, private val beforeWorkspaceLock: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    internal fun create(token: String?, value: JsonElement, original: DecodedPythonJson? = null, workspace: String? = null): JsonObject {
        val body = value as? JsonObject ?: legacyCashPayloadError("investment_payload_invalid")
        val operation = legacyCashText(body, "operation", false)?.takeIf { it.isNotEmpty() } ?: legacyCashText(body, "record_type")!!
        if (operation !in setOf("buy", "sell", "swap", "deposit", "withdraw", "dividend", "checkin")) legacyCashPayloadError("investment_operation_invalid")
        val account = legacyCashText(body, "account")!!; val currency = legacyCashText(body, "currency", false)
        val note = legacyCashText(body, "note", false).orEmpty(); val date = legacyCashText(body, "date", false)
        val application = InvestmentCommandApplication { command ->
            val result = execute(token, command, workspace, body, original)
            if (result["ok"] == JsonPrimitive(false)) throw CashflowOperationFailure("operation_failed", "投资事件写入失败，请检查账户和参数。")
            result
        }
        fun text(key: String) = legacyCashText(body, key)!!
        fun number(key: String, required: Boolean = true) = legacyCashDecimalText(body, key, required, original)
        fun commission() = number("commission", false)?.takeUnless { com.finance.tracker.business.domain.LedgerDecimal.parseRepresentationOrNull(it)?.coefficient == "0" } ?: "0"
        return when (operation) {
            "buy" -> application.buy(text("ticker"), number("shares")!!, number("price")!!, commission(), currency, account, note, date)
            "sell" -> application.sell(text("ticker"), number("shares")!!, number("price")!!, commission(), currency, account, note, date)
            "swap" -> application.swap(account, text("from_ticker"), number("from_shares")!!, text("to_ticker"), number("to_shares")!!, currency, note, date, commission(), legacyCashText(body, "commission_asset", false).orEmpty())
            "deposit" -> application.deposit(number("amount")!!, currency, account, note, date)
            "withdraw" -> application.withdraw(number("amount")!!, currency, account, note, date)
            "dividend" -> application.dividend(text("ticker"), number("amount")!!, currency, account, note, date)
            else -> if (body["ticker"] != null && body["ticker"] != JsonNull) application.checkinTicker(text("ticker"), number("shares")!!, number("avg_cost")!!, currency, account, note, date)
                else application.checkinCash(number("cash")!!, currency, account, note, date)
        }
    }
    internal fun execute(token: String?, command: InvestmentCommand, requestWorkspace: String? = null,
        body: JsonObject? = null, original: DecodedPythonJson? = null): JsonObject = try { beforeWrite(); database.write { connection ->
        val workspace = requestWorkspace ?: reads.session(connection, token).workspaceId ?: throw AccessFailure(true)
        beforeWorkspaceLock()
        if (!connection.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
        reads.lockedContext(connection, token, workspace, write = true)
        val context = CashSqlContext(connection, workspace, database.sqlite, now)
        fun utf8(key: String, text: String) {
            try { manualOriginalUtf8(body?.get(key) ?: JsonPrimitive(text), original, normalized = text) }
            catch (failure: ManualCashBusinessFailure) { throw InvestmentEncodingFailure(failure.message.orEmpty()) }
        }
        utf8("account", command.account)
        fun failure(message: String) = buildJsonObject { put("ok", false); put("message", message); put("count", 0); put("findings", JsonArray(emptyList())); put("export", JsonNull); put("details", JsonObject(emptyMap())) }
        val account = CashTransferRepository(context).accounts().firstOrNull { it.account.name == command.account }
            ?: throw InvestmentCommandRejected(failure("找不到账户：${command.account}"))
        if (account.account.type !in setOf("security", "crypto")) throw InvestmentCommandRejected(failure("投资命令需要 security 或 crypto 类型的账户"))
        val row = try {
            val snapshot = context.snapshot.load(lock = true)
            val currencies = account.wire["currencies"]
            val bases = if (currencies == null || !pythonTruthy(currencies)) null else currencies.jsonArray.map { pythonString(it) }
            require(body?.get("currency")?.let { original?.stringRawSurrogates(it) }.orEmpty().isEmpty()) { "currency is required" }
            val result = InvestmentProjection.applyCommand(snapshot, command, account.account.type, command.currency.orEmpty(), bases, now = { manualInstantText(now()) })
            validateInvestmentSnapshot(snapshot.value)
            insert(context, account.id, result, command, body, original)
            context.snapshot.save(snapshot)
            result
        } catch (failure: IllegalArgumentException) {
            throw InvestmentCommandRejected(failure(failure.message.orEmpty()))
        }
        beforeReturn(); reads.lockedContext(connection, token, workspace, write = true)
        val result = resultTransform(buildJsonObject {
            put("ok", true); put("message", command.recordType); put("count", 0); put("findings", JsonArray(emptyList())); put("export", JsonNull)
            put("details", buildJsonObject { put("row", row) })
        })
        // Check the complete HTTP result before Database commits the transaction.
        try { manualOriginalUtf8(JsonPrimitive(result.toString()), null) }
        catch (failure: ManualCashBusinessFailure) { throw InvestmentEncodingFailure(failure.message.orEmpty()) }
        result
    } } catch (rejected: InvestmentCommandRejected) { rejected.result }
    private fun insert(context: CashSqlContext, account: Long, row: JsonObject, command: InvestmentCommand, body: JsonObject?, original: DecodedPythonJson?) {
        val currency = row.getValue("currency").jsonPrimitive.content
        require(currency.isEmpty() || normalizedCashOperationCurrency(currency) == currency) { "currency must be a 3-letter code" }
        val semantics = normalizeInvestmentRecordSemantics(row["record_type"], row["record_subtype"])
        val date = row.getValue("date").jsonPrimitive.content
        val dateRaw = body?.get("date"); val shift = (dateRaw as? JsonPrimitive)?.content?.indexOf(date)?.coerceAtLeast(0) ?: 0
        val positions = dateRaw?.let { original?.stringRawSurrogates(it) }.orEmpty().map { it - shift }.filter { it in date.indices }.toSet()
        val at = manualCashTimestamp(date, positions)
        for (key in listOf("note", "from_ticker", "to_ticker", "commission_asset")) {
            try { manualOriginalUtf8(row[key], null) } catch (failure: ManualCashBusinessFailure) { throw InvestmentEncodingFailure(failure.message.orEmpty()) }
        }
        val used = listOf("note") + when (command.recordType) {
            "buy", "sell", "dividend", "checkin_ticker" -> listOf("ticker")
            "swap" -> listOf("from_ticker", "to_ticker", "commission_asset")
            else -> emptyList()
        }
        for (key in used) {
            try { manualOriginalUtf8(body?.get(key), original) }
            catch (failure: ManualCashBusinessFailure) { throw InvestmentEncodingFailure(failure.message.orEmpty()) }
        }
        fun amount(key: String): CashStoredDecimal {
            val number = row.getValue(key).jsonPrimitive.content.toBigDecimal()
            if (number.scale() > 18) {
                val rounded = number.setScale(18, java.math.RoundingMode.HALF_UP)
                if (rounded.precision() > 28) throw InvestmentWriteInternalFailure()
                val negativeZero = rounded.signum() == 0 && row.getValue(key).jsonPrimitive.content.startsWith("-")
                return CashStoredDecimal((if (negativeZero) "-" else "") + rounded.toPlainString())
            }
            return CashStoredDecimal(row.getValue(key).jsonPrimitive.content)
        }
        val fromAmount = amount("from_amount")
        val toAmount = amount("to_amount")
        // The original repository validates price even though it is not stored.
        amount("price")
        val event = INVESTMENT_EVENTS
        context.sql.insertInto(event).set(event.WORKSPACE_ID, context.workspace).set(event.ACCOUNT_ID, account)
            .set(event.OCCURRED_AT, at).set(event.RECORD_TYPE, semantics.first).set(event.RECORD_SUBTYPE, semantics.second)
            .set(event.CURRENCY, currency).set(event.NOTE, row.getValue("note").jsonPrimitive.content)
            .set(event.FROM_TICKER, row.getValue("from_ticker").jsonPrimitive.content).set(event.TO_TICKER, row.getValue("to_ticker").jsonPrimitive.content)
            .set(event.FROM_AMOUNT, fromAmount)
            .set(event.TO_AMOUNT, toAmount)
            .set(event.COMMISSION, amount("commission"))
            .set(event.COMMISSION_ASSET, row.getValue("commission_asset").jsonPrimitive.content)
            .set(event.SOURCE_TYPE, null as String?).set(event.RECORD_ID, "").set(event.SOURCE_PAYLOAD, null as JsonElement?)
            .set(event.PAYLOAD, JsonObject(emptyMap())).set(event.CREATED_AT, context.now()).execute()
    }
}
