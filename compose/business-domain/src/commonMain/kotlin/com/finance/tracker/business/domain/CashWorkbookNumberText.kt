package com.finance.tracker.business.domain

import kotlin.math.abs
import kotlin.math.floor

/** Numeric cells of platform statements keep the original Java 21 text and small-integer policy. */
fun cashPlatformWorkbookNumberText(value: Double): String =
    if (value.isFinite() && value == floor(value) && abs(value) < 1e16) value.toLong().toString()
    else javaBinary64Text(value)

/** CCB's original reader converts every finite integral cell through the saturating Long boundary. */
fun cashCcbWorkbookNumberText(value: Double): String =
    if (value.isFinite() && value == floor(value)) value.toLong().toString()
    else javaBinary64Text(value)
