package com.finance.tracker.backend

import java.math.BigDecimal
import kotlinx.serialization.json.*
import com.finance.tracker.business.domain.pythonStringOrder as sharedPythonStringOrder
import com.finance.tracker.business.domain.sourceJson as sharedSourceJson

internal fun providerIdentityKey(providerId: String): String = com.finance.tracker.business.domain.providerIdentityKey(providerId)

internal typealias SourceBindingKey = com.finance.tracker.business.domain.SourceBindingKey

internal typealias SourceDocumentEvidence = com.finance.tracker.business.domain.SourceDocumentEvidence
internal typealias DocumentNamespace = com.finance.tracker.business.domain.DocumentNamespace

internal typealias SourceOccurrence = com.finance.tracker.business.domain.SourceOccurrence

internal typealias CashSourceScope = com.finance.tracker.business.domain.CashSourceScope

internal typealias CashSourceDocuments = com.finance.tracker.business.domain.CashSourceDocuments

/** Python `json.dumps(ensure_ascii=False, sort_keys=True, separators=(",", ":"))` 的稳定摘要。 */
internal fun sourceDigest(vararg values: Any?): String = tokenDigest(sourceJson(values.toList()).toString())

/** 实现复用共享 KMP 模块，保持单一来源。 */
internal fun pythonStringOrder(first: String, second: String): Int = sharedPythonStringOrder(first, second)

private fun pythonDigestFloat(value: Double): String = when {
    value.isNaN() -> "NaN"
    value == Double.POSITIVE_INFINITY -> "Infinity"
    value == Double.NEGATIVE_INFINITY -> "-Infinity"
    else -> pythonFloatText(value)
}

private fun backendSourceDecimal(value: Any): String? = (value as? BigDecimal)?.toString()

/** 结构实现复用共享 KMP 模块，十进制与浮点口径由后端注入，digest 保持不变。 */
internal fun sourceJson(value: Any?): JsonElement =
    sharedSourceJson(value, ::backendSourceDecimal, ::pythonDigestFloat)
