package com.finance.tracker.business.domain

/** Value boundary for the same record decisions over native scalars or decoded local JSON. */
interface CashRecordValues {
    fun normalize(field: String, value: Any?): Any?
    fun prepare(current: Map<*, *>, patch: Any?, protected: Set<String> = emptySet()): CashPolicyUpdate
    fun valuesEqual(left: Any?, right: Any?): Boolean
    fun copyValue(value: Any?): Any?
    fun validateConsistency(record: Map<*, *>)
}
