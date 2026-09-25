package com.finance.tracker

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import web.navigator.navigator
import kotlin.js.js

class JsPlatform: Platform {
    private val userAgent = navigator.userAgent
    private val browserList = listOf("Chrome", "Firefox", "Safari", "Edge")

    override val name: String = userAgent.findAnyOf(browserList, ignoreCase = true)
            ?.let { (startIndex) -> userAgent.substring(startIndex).substringBefore(" ") }
            ?: "Unknown"
}

actual val isWebPlatform: Boolean = true

@Composable
actual fun platformFinanceFontFamily(): FontFamily = FontFamily.SansSerif

actual val platformFinanceFontRequiresPreload: Boolean = false

actual fun getPlatform(): Platform = JsPlatform()

actual fun getBrowserLocation(): BrowserLocation? =
    BrowserLocation(browserPathname(), browserSearch())

actual fun pushBrowserPath(path: String) {
    pushBrowserHistoryPath(path)
}

actual fun replaceBrowserPath(path: String) {
    replaceBrowserHistoryPath(path)
}

actual fun observeBrowserHistory(onPopState: () -> Unit): () -> Unit {
    addPopStateListener(onPopState)
    return { removePopStateListener(onPopState) }
}

actual fun observeBrowserTabNavigation(onTab: (backwards: Boolean) -> Unit): () -> Unit = registerBrowserTabNavigation(onTab)

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

actual fun createPlatformTokenStore(): TokenStore = object : TokenStore {
    override suspend fun get(): String? = runCatching { readBrowserToken() }.getOrNull()
    override suspend fun set(value: String) { runCatching { writeBrowserToken(value) } }
    override suspend fun clear() { runCatching { clearBrowserToken() } }
}

actual fun readDisplayPreference(key: String): String? = js("window.localStorage.getItem(key)")

actual fun writeDisplayPreference(key: String, value: String) { js("window.localStorage.setItem(key, value)") }

actual fun getConfiguredApiOrigin(): String = js("window.FT_API_ORIGIN || ''")

actual fun getLocalTimeZoneId(): String = js("Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'")

actual fun getLocalDateForInput(): String = js("(() => { const d = new Date(); const p = n => String(n).padStart(2, '0'); return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()); })()")

actual fun getLocalDateForInstant(instant: String): String = js("(() => { const d = new Date(instant); if (Number.isNaN(d.getTime())) return instant.slice(0, 10); const p = n => String(n).padStart(2, '0'); return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()); })()")

actual fun formatLocalDateTime(instant: String): String = js("(() => { const d = new Date(instant); if (Number.isNaN(d.getTime())) return instant.replace('T', ' ').slice(0, 16); const p = n => String(n).padStart(2, '0'); return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()); })()")

actual fun getConfiguredWebOrigin(): String = js("window.location.origin")

actual suspend fun copyTextToClipboard(value: String): Boolean = suspendCoroutine { continuation ->
    writeClipboard(value) { copied -> continuation.resume(copied) }
}

private fun browserPathname(): String = js("window.location.pathname")

private fun browserSearch(): String = js("window.location.search")

private fun pushBrowserHistoryPath(path: String) {
    js("window.history.pushState({}, '', path)")
}

private fun replaceBrowserHistoryPath(path: String) {
    js("window.history.replaceState({}, '', path)")
}

private fun addPopStateListener(onPopState: () -> Unit) {
    js("window.addEventListener('popstate', onPopState)")
}

private fun removePopStateListener(onPopState: () -> Unit) {
    js("window.removeEventListener('popstate', onPopState)")
}

private fun registerBrowserTabNavigation(onTab: (Boolean) -> Unit): () -> Unit = js(
    """(() => {
        const listener = event => {
            if (event.key !== 'Tab') return;
            event.preventDefault();
            event.stopPropagation();
            onTab(event.shiftKey);
        };
        window.addEventListener('keydown', listener, true);
        return () => window.removeEventListener('keydown', listener, true);
    })()""",
)

private fun readBrowserToken(): String? = js("window.localStorage.getItem('finance-tracker:session-token')")

private fun writeBrowserToken(value: String) {
    js("window.localStorage.setItem('finance-tracker:session-token', value)")
}

private fun clearBrowserToken() {
    js("window.localStorage.removeItem('finance-tracker:session-token')")
}

private fun writeClipboard(value: String, done: (Boolean) -> Unit): Unit = js(
    "navigator.clipboard.writeText(value).then(() => done(true)).catch(() => done(false))",
)
