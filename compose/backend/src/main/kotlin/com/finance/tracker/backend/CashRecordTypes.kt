package com.finance.tracker.backend

internal val cashRecordTypes = com.finance.tracker.business.domain.cashRecordTypes
internal val cashRecordSubtypes = com.finance.tracker.business.domain.cashRecordSubtypes
internal fun defaultCashRecordSubtype(type: String?): String = com.finance.tracker.business.domain.defaultCashRecordSubtype(type)
internal fun validCashRecordPair(type: Any?, subtype: Any?): Boolean = com.finance.tracker.business.domain.validCashRecordPair(type, subtype)
