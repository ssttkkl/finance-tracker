package com.finance.tracker.business.domain

/** Text identities extracted by the platform adapter, never guessed by general relation rules. */
data class CashPlatformTransactionKeys(val transactionId: String?, val merchantOrderId: String?)

internal data class CashPlatformSourceFields(val transactionId: String, val merchantOrderId: String,
    val legacyTransactionIds: List<String>, val legacyMerchantOrderIds: List<String>)

private val alipaySourceFields = CashPlatformSourceFields("交易订单号", "商家订单号", listOf("交易号"), listOf("商户单号"))
private val wechatSourceFields = CashPlatformSourceFields("交易单号", "商户单号", emptyList(), listOf("商家订单号"))

internal fun cashPlatformSourceFields(source: String): CashPlatformSourceFields? = when (source) {
    "alipay" -> alipaySourceFields
    "wechat" -> wechatSourceFields
    else -> null
}

/** Prefer normalized fields; only the named platform adapter may read mapped original fields as a fallback. */
fun readCashPlatformTransactionKeys(source: String, explanation: Any?, readOriginal: () -> Any?): CashPlatformTransactionKeys? {
    val fields = cashPlatformSourceFields(source) ?: return null
    val metadata = explanation as? Map<*, *>
    val payload by lazy { readOriginal() as? Map<*, *> }
    fun key(name: String, native: String, legacy: List<String>): String? {
        metadata?.get(name)?.let { return it as? String }
        for (field in listOf(name, native) + legacy) payload?.get(field)?.let { return it as? String }
        return null
    }
    return CashPlatformTransactionKeys(key("txn_id", fields.transactionId, fields.legacyTransactionIds),
        key("merchant_order_id", fields.merchantOrderId, fields.legacyMerchantOrderIds))
}
