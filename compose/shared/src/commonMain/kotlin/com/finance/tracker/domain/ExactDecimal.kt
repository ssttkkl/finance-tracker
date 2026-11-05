package com.finance.tracker.domain

typealias AllocationBalance = com.finance.tracker.business.domain.AllocationBalance

fun isExactDecimalString(value: String): Boolean = com.finance.tracker.business.domain.isExactDecimalString(value)

fun allocationBalance(targetValue: String, values: List<String>): AllocationBalance =
    com.finance.tracker.business.domain.allocationBalance(targetValue, values)
