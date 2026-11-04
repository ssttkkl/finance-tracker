package com.finance.tracker.business.domain

object CashSourceScope {
    const val ALIPAY_TRANSACTION_SCOPE = "alipay:transaction:v1"
    const val WECHAT_DEFAULT_SCOPE = "wechat:default:v1"

    fun isValidPlatformTransactionId(value: String?): Boolean =
        value != null && value == value.trim { it.code in pythonWhitespace } &&
            value !in setOf("", "/", "-", "--") && value.pythonCodePointCount() <= 512

    fun sourceTransactionScopeKey(sourceType: String, owner: String? = null): String? {
        val key = sourceAccountKey(sourceType, owner)
        return if (sourceType == "alipay") ALIPAY_TRANSACTION_SCOPE else key
    }

    fun sourceAccountKey(sourceType: String, owner: String? = null): String? {
        if (sourceType == "wechat") return WECHAT_DEFAULT_SCOPE
        if (owner != null && (owner.isEmpty() || owner.pythonCodePointCount() > 256))
            throw IllegalArgumentException("source_account_invalid")
        return owner
    }
}
