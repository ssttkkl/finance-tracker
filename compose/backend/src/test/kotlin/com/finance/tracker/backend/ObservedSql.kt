package com.finance.tracker.backend

import java.util.Locale

internal fun String.observedSql(): String = replace("\"", "").replace("`", "")
    .lowercase(Locale.ROOT)
    .replace(Regex("\\s+as\\s+"), " ")
    .replace(Regex("\\s+"), " ")
    .replace(Regex("\\s*,\\s*"), ",")
    .replace(Regex("\\s*=\\s*"), "=")
    .trim()
