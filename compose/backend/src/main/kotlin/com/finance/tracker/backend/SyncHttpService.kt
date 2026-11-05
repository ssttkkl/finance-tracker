package com.finance.tracker.backend

import com.finance.tracker.business.contracts.Role
import java.math.BigInteger
import kotlinx.serialization.json.*

internal class SyncCleanupFailure(cause: Throwable) : RuntimeException("sync_cleanup_failed", cause)
internal class SyncConfigurationFailure : RuntimeException()
internal class SyncHttpFailure(val code: String, val status: Int = 400, message: String) : RuntimeException(message)
internal class InvestmentConnectorFactory(
    private val credentials: (String) -> Map<String, String> = { provider ->
        val loader = ConnectorCredentials()
        if (provider == "polymarket") loader.polymarket() else loader.exchange(provider)
    },
    private val ccxt: (String, Map<String, String>) -> CcxtSdkClient = ::CcxtSdkClient,
    private val polymarket: (Map<String, String>) -> InvestmentConnector = { PolymarketConnector(it) },
) {
    fun create(provider: String): InvestmentConnector {
        if (provider !in setOf("binance", "kraken", "okx", "polymarket")) throw SyncConfigurationFailure()
        val values = try { credentials(provider) } catch (_: IllegalArgumentException) { throw SyncConfigurationFailure() }
        if (provider == "polymarket") return polymarket(values)
        return object : InvestmentConnector {
            override val sourceType = "${provider}_api"
            override fun fetch(since: String?): ConnectorResult {
                val client = ccxt(provider, values)
                var primary: Throwable? = null
                try { return CcxtExchangeConnector(provider, client).fetch(since) }
                catch (error: Throwable) { primary = error; throw error }
                finally {
                    try { client.close() }
                    catch (cleanup: Throwable) {
                        val failure = primary
                        if (failure is InterruptedException || failure is java.util.concurrent.CancellationException || failure is Error) failure.addSuppressed(cleanup)
                        else if (cleanup is InterruptedException || cleanup is java.util.concurrent.CancellationException || cleanup is Error) {
                            if (failure != null) cleanup.addSuppressed(failure)
                            throw cleanup
                        } else throw SyncCleanupFailure(cleanup).also { if (failure != null) it.addSuppressed(failure) }
                    }
                }
            }
        }
    }
}
internal class SyncHttpService(database: Database,
    private val service: SyncService = SyncService(database),
    private val factory: (String) -> InvestmentConnector = InvestmentConnectorFactory()::create,
) {
    internal data class Request(val provider: String, val account: String, val full: Boolean, val batchSize: Int)
    fun requireAdmin(principal: Principal) {
        if (principal.role != Role.ADMIN) throw SyncHttpFailure("workspace_forbidden", 403, "只有管理员可以执行该操作。")
    }
    private fun invalid(code: String): Nothing {
        if (code.startsWith("sync_") || code.startsWith("credential")) throw SyncConfigurationFailure()
        throw SyncHttpFailure(code, message = "请求参数无效。")
    }
    internal fun parse(bytes: ByteArray): Request {
        val value = try { categoryDeleteRequest(pythonJsonText(bytes)).value } catch (failure: CategoryFailure) { invalid(failure.code) }
        val body = value as? JsonObject ?: invalid("sync_payload_invalid")
        fun text(key: String, required: Boolean = true): String? {
            val field = body[key]
            if ((field == null || field == JsonNull) && !required) return null
            if (field !is JsonPrimitive || !field.isString) invalid("${key}_required")
            val result = valuationStrip(field.content)
            if (required && result.isEmpty()) invalid("${key}_required")
            return result
        }
        val source = text("source", false)?.takeIf { it.isNotEmpty() } ?: text("provider")!!
        val account = text("account")!!
        val size = body["batch_size"] ?: JsonPrimitive(500)
        if (size !is JsonPrimitive || size.isString || !size.content.matches(Regex("-?[0-9]+"))) invalid("sync_batch_size_invalid")
        val number = BigInteger(size.content)
        if (number.signum() <= 0) invalid("sync_batch_size_invalid")
        return Request(source, account, pythonTruthy(body["full"] ?: JsonPrimitive(false)), number.min(BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt())
    }
    fun sync(token: String?, principal: Principal, request: Request): JsonObject {
        requireAdmin(principal)
        val connector = factory(request.provider)
        val result = service.sync(token, request.provider, request.account, request.full, request.batchSize, connector, principal.workspaceId, requireAdministrator = true)
        if (result["ok"] != JsonPrimitive(true)) throw SyncHttpFailure("operation_failed", message = "同步失败，请检查服务端连接配置。")
        return result
    }
}
