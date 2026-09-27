package com.finance.tracker.presentation

import com.finance.tracker.resources.Res
import com.finance.tracker.resources.allStringResources
import org.jetbrains.compose.resources.stringResource

@androidx.compose.runtime.Composable
internal fun localizedText(resourceKey: String, vararg formatArgs: Any): String {
    val resource = requireNotNull(Res.allStringResources[resourceKey]) {
        "Missing Compose string resource: $resourceKey"
    }
    return if (formatArgs.isEmpty()) {
        stringResource(resource)
    } else {
        stringResource(resource, *formatArgs)
    }
}
