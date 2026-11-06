package com.finance.tracker.backend

import kotlinx.serialization.json.JsonElement

/** Compatibility for the existing non-credential name and role fields only. */
internal fun pythonString(value: JsonElement): String =
    com.finance.tracker.business.domain.pythonJsonText(value)

internal fun pythonFloatText(value: Double): String =
    com.finance.tracker.business.domain.pythonFloatText(value)

internal fun pythonStringRepr(text: String, separateSurrogates: Set<Int> = emptySet()): String =
    com.finance.tracker.business.domain.pythonJsonStringRepr(text, separateSurrogates)
