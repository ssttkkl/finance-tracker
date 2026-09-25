@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import financetracker.shared.generated.resources.Res
import financetracker.shared.generated.resources.noto_sans_sc
import org.jetbrains.compose.resources.Font
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.js.js

class WasmPlatform: Platform {
    override val name: String = "Web with Kotlin/Wasm"
}

actual val isWebPlatform: Boolean = true

@Composable
actual fun platformFinanceFontFamily(): FontFamily {
    val notoSansScFont = Font(Res.font.noto_sans_sc)
    return remember { FontFamily(notoSansScFont) }
}

actual val platformFinanceFontRequiresPreload: Boolean = true

actual fun getPlatform(): Platform = WasmPlatform()

actual fun getBrowserLocation(): BrowserLocation? =
    BrowserLocation(currentBrowserPathname(), currentBrowserSearch())

actual fun pushBrowserPath(path: String) {
    pushBrowserHistoryPath(path)
}

actual fun replaceBrowserPath(path: String) {
    replaceBrowserHistoryPath(path)
}

actual fun observeBrowserHistory(onPopState: () -> Unit): () -> Unit =
    registerBrowserPopState(onPopState)

actual fun observeBrowserTabNavigation(onTab: (backwards: Boolean) -> Unit): () -> Unit =
    registerBrowserTabNavigation(onTab)

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

private fun currentBrowserPathname(): String = js("window.location.pathname")

private fun currentBrowserSearch(): String = js("window.location.search")

private fun pushBrowserHistoryPath(path: String): Unit = js("window.history.pushState({}, '', path)")

private fun replaceBrowserHistoryPath(path: String): Unit = js("window.history.replaceState({}, '', path)")

private fun registerBrowserPopState(onPopState: () -> Unit): () -> Unit = js(
    """(() => {
        const listener = () => onPopState();
        window.addEventListener('popstate', listener);
        return () => window.removeEventListener('popstate', listener);
    })()""",
)

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

private fun writeBrowserToken(value: String): Unit = js("window.localStorage.setItem('finance-tracker:session-token', value)")

private fun clearBrowserToken(): Unit = js("window.localStorage.removeItem('finance-tracker:session-token')")

private fun writeClipboard(value: String, done: (Boolean) -> Unit): Unit = js(
    "navigator.clipboard.writeText(value).then(() => done(true)).catch(() => done(false))",
)
