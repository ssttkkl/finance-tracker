package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.ControlAccessibilityState
import com.finance.tracker.core.PlatformControlAccessibility

/** UI qualification derived from the published session; role permissions remain with each task. */
internal data class SessionConnection(
    val offline: Boolean = false,
    val checking: Boolean = false,
    val ready: Boolean = true,
    val error: String? = null,
    val reconnect: () -> Unit = {},
) {
    val canSubmit: Boolean get() = ready && !offline && !checking
}

internal val LocalSessionConnection = staticCompositionLocalOf { SessionConnection() }

@Composable
internal fun SessionConnectionNotice(modifier: Modifier = Modifier) {
    val connection = LocalSessionConnection.current
    if (connection.ready && !connection.offline && !connection.checking) return
    Column(modifier.fillMaxWidth().testTag("session.connection").semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localizedText(when { connection.checking -> "session_reconnecting"; connection.offline -> "session_offline"; else -> "session_connection_unavailable" }),
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            PlatformControlAccessibility("session.reconnect", ControlAccessibilityState.Button(!connection.checking))
            TextButton(onClick = connection.reconnect, enabled = !connection.checking,
                modifier = Modifier.testTag("session.reconnect")) { Text(localizedText("session_reconnect")) }
        }
        InlineError(connection.error)
    }
}
