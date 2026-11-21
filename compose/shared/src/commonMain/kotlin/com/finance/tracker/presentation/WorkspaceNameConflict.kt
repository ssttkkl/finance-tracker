package com.finance.tracker.presentation

import com.finance.tracker.domain.normalizeWorkspaceName

internal fun hasSameWorkspaceName(normalizedName: String, names: List<String>): Boolean =
    names.any { normalizeWorkspaceName(it)?.equals(normalizedName, ignoreCase = true) == true }
