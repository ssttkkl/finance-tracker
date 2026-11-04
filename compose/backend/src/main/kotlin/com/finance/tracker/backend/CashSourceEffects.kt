package com.finance.tracker.backend

internal typealias CashSourceEffects = com.finance.tracker.business.domain.CashSourceEffects
internal fun validCashSourceExplanation(value: Map<String, Any?>) = com.finance.tracker.business.domain.validCashSourceExplanation(value)
internal fun extractCashSourceExplanation(value: Any?) = com.finance.tracker.business.domain.extractCashSourceExplanation(value)
internal fun cashSourceNote(sourceType: String, payload: Any?) = com.finance.tracker.business.domain.cashSourceNote(sourceType, payload)
internal fun inferIcbcPayment(counterparty: String, description: String) = com.finance.tracker.business.domain.inferIcbcPayment(counterparty, description)
internal fun inferCashPlatform(counterparty: String, description: String) = com.finance.tracker.business.domain.inferCashPlatform(counterparty, description)
internal fun stripCashPaymentPrefix(value: String) = com.finance.tracker.business.domain.stripCashPaymentPrefix(value)
internal fun stripPlatformPrefix(value: String) = com.finance.tracker.business.domain.stripPlatformPrefix(value)
internal fun normalizeSourceCounterparty(raw: String, description: String) = com.finance.tracker.business.domain.normalizeSourceCounterparty(raw, description)
internal fun prepareCashSourceEffects(values: Map<String, Any?>, patch: Map<String, Any?>, sourceType: String,
    sourceExplanation: Any?, protectedFields: Set<String> = emptySet()): CashSourceEffects =
    com.finance.tracker.business.domain.prepareCashSourceEffects(values, patch, sourceType, sourceExplanation, protectedFields)
