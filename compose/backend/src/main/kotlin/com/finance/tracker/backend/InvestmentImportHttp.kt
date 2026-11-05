package com.finance.tracker.backend

import java.util.Base64
import kotlinx.serialization.json.*

internal object InvestmentImportHttp {
    fun execute(service: InvestmentImportService, token: String?, body: JsonElement, password: String?, workspace: String?): JsonObject {
        val input = body as? JsonObject ?: legacyCashPayloadError("investment_import_payload_invalid")
        val source = legacyCashText(input, "source")!!
        if (source !in setOf("dfzq", "ibkr", "schwab", "usmart-hk", "usmart_hk")) legacyCashPayloadError("investment_import_source_invalid")
        val account = legacyCashText(input, "account")!!
        val encoded = input["content_base64"]
        if (encoded !is JsonPrimitive || !encoded.isString || encoded.content.isEmpty()) legacyCashPayloadError("investment_import_content_required")
        val bytes = decode(encoded.content)
        if (bytes.size > 25 * 1024 * 1024) legacyCashPayloadError("investment_import_content_too_large")
        val name = (legacyCashText(input, "filename", false) ?: "statement").split('/').lastOrNull { it.isNotEmpty() && it != "." }.orEmpty()
        val suffix = name.lastIndexOf('.').takeIf { it > 0 && it < name.lastIndex }?.let { name.substring(it).take(12) }.orEmpty()
        val currency = legacyCashText(input, "currency", false)
        val result = service.importBytes(token, source, "statement$suffix", bytes, account, currency, password, workspace)
        if (result["ok"] != JsonPrimitive(true)) throw CashflowOperationFailure("operation_failed", "投资账单无法导入，请检查文件和账户配置。")
        return result
    }
    fun decode(text: String): ByteArray {
        fun invalid(): Nothing = legacyCashPayloadError("investment_import_content_invalid")
        val padding = text.indexOf('=')
        val data = if (padding < 0) text else text.substring(0, padding)
        if (data.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '+' && it != '/' }) invalid()
        if (padding >= 0 && text.substring(padding).any { it != '=' }) invalid()
        val remainder = data.length % 4
        val count = text.length - data.length
        if (data.isEmpty() && count > 0 || remainder == 1 || remainder == 2 && count != 2 || remainder == 3 && count != 1) invalid()
        return try { Base64.getDecoder().decode(if (remainder == 0) data else text) } catch (_: IllegalArgumentException) { invalid() }
    }
}
