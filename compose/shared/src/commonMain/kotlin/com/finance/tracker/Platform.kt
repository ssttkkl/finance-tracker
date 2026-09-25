package com.finance.tracker

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

interface Platform {
    val name: String
}

expect val isWebPlatform: Boolean

@Composable
expect fun platformFinanceFontFamily(): FontFamily

expect val platformFinanceFontRequiresPreload: Boolean

expect fun getPlatform(): Platform

data class BrowserLocation(val pathname: String, val search: String)

expect fun getBrowserLocation(): BrowserLocation?

expect fun pushBrowserPath(path: String)

expect fun replaceBrowserPath(path: String)

expect fun observeBrowserHistory(onPopState: () -> Unit): () -> Unit

expect fun observeBrowserTabNavigation(onTab: (backwards: Boolean) -> Unit): () -> Unit

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

expect fun createPlatformTokenStore(): TokenStore

expect fun readDisplayPreference(key: String): String?

expect fun writeDisplayPreference(key: String, value: String)

expect fun getConfiguredApiOrigin(): String

expect fun getLocalTimeZoneId(): String

expect fun getLocalDateForInput(): String

expect fun getLocalDateForInstant(instant: String): String

expect fun formatLocalDateTime(instant: String): String

expect fun getConfiguredWebOrigin(): String

expect suspend fun copyTextToClipboard(value: String): Boolean
