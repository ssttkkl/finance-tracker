package com.finance.tracker.presentation

fun displayDecimalAdd(left: String?, right: String?): String? =
    com.finance.tracker.business.domain.displayDecimalAdd(left, right)

fun displayDecimalCompare(left: String?, right: String?): Int? =
    com.finance.tracker.business.domain.displayDecimalCompare(left, right)

fun displayDecimalSign(value: String?): Int? =
    com.finance.tracker.business.domain.displayDecimalSign(value)

fun displayDecimalAbs(value: String?): String? =
    com.finance.tracker.business.domain.displayDecimalAbs(value)

fun displayDecimalMultiply(left: String?, right: String?): String? =
    com.finance.tracker.business.domain.displayDecimalMultiply(left, right)

fun displayDecimalDivide(numerator: String?, denominator: String?, precision: Int = 18): String? =
    com.finance.tracker.business.domain.displayDecimalDivide(numerator, denominator, precision)

fun displayDecimalRound(value: String?, scale: Int): String? =
    com.finance.tracker.business.domain.displayDecimalRound(value, scale)
