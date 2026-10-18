@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.font.FontFamily
import com.finance.tracker.shared.ComposeBuildConfig
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.noto_sans_sc
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

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable
actual fun PlatformEscapeHandler(enabled: Boolean, onEscape: () -> Unit) {
    val currentOnEscape = rememberUpdatedState(onEscape)
    DisposableEffect(enabled) {
        val unregister = if (enabled) registerBrowserEscapeListener { currentOnEscape.value() } else ({})
        onDispose(unregister)
    }
}

@Composable
actual fun PlatformModalEscapeHandler(enabled: Boolean, onEscape: () -> Unit) {
    val currentOnEscape = rememberUpdatedState(onEscape)
    DisposableEffect(enabled) {
        val unregister = if (enabled) registerBrowserModalEscapeListener { currentOnEscape.value() } else ({})
        onDispose(unregister)
    }
}

actual fun createPlatformTokenStore(): TokenStore = object : TokenStore {
    override suspend fun get(): String? = runCatching { readBrowserToken() }.getOrNull()
    override suspend fun set(value: String) { runCatching { writeBrowserToken(value) } }
    override suspend fun clear() { runCatching { clearBrowserToken() } }
}

actual fun readDisplayPreference(key: String): String? = js("window.localStorage.getItem(key)")

actual fun writeDisplayPreference(key: String, value: String) { js("window.localStorage.setItem(key, value)") }

actual fun getConfiguredApiOrigin(): String =
    ComposeBuildConfig.FT_API_ORIGIN.ifBlank { currentBrowserOrigin() }

actual fun getLocalTimeZoneId(): String = js("Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'")

actual fun getLocalDateForInput(): String = js("(() => { const d = new Date(); const p = n => String(n).padStart(2, '0'); return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()); })()")

actual fun getLocalDateForInstant(instant: String): String = js("(() => { const d = new Date(instant); if (Number.isNaN(d.getTime())) return instant.slice(0, 10); const p = n => String(n).padStart(2, '0'); return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()); })()")

actual fun formatLocalDateTime(instant: String): String = js("(() => { const d = new Date(instant); if (Number.isNaN(d.getTime())) return instant.replace('T', ' ').slice(0, 16); const p = n => String(n).padStart(2, '0'); return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()); })()")

actual fun getConfiguredWebOrigin(): String = js("window.location.origin")

actual suspend fun copyTextToClipboard(value: String): Boolean = suspendCoroutine { continuation ->
    writeClipboard(value) { copied -> continuation.resume(copied) }
}

private fun currentBrowserPathname(): String = js("window.location.pathname")

private fun currentBrowserOrigin(): String = js("window.location.origin")

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

private fun registerBrowserEscapeListener(onEscape: () -> Unit): () -> Unit = registerBrowserEscapeHandler(onEscape, 2)

private fun registerBrowserModalEscapeListener(onEscape: () -> Unit): () -> Unit = registerBrowserEscapeHandler(onEscape, 1)

private fun registerBrowserEscapeHandler(onEscape: () -> Unit, escapePriority: Int): () -> Unit = js(
    """(() => {
        const stackKey = '__financeTrackerEscapeHandlers';
        const listenerKey = '__financeTrackerEscapeListener';
        const handlers = window[stackKey] || (window[stackKey] = []);
        const entry = { callback: onEscape, priority: escapePriority };
        handlers.push(entry);
        if (!window[listenerKey]) {
            window[listenerKey] = event => {
                if (event.key !== 'Escape') return;
                const active = (window[stackKey] || []).reduce((top, candidate) =>
                    !top || candidate.priority >= top.priority ? candidate : top,
                    null,
                );
                if (!active) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                active.callback();
            };
            window.addEventListener('keydown', window[listenerKey], true);
        }
        return () => {
            const activeHandlers = window[stackKey] || [];
            const index = activeHandlers.indexOf(entry);
            if (index >= 0) activeHandlers.splice(index, 1);
            if (activeHandlers.length === 0 && window[listenerKey]) {
                window.removeEventListener('keydown', window[listenerKey], true);
                delete window[listenerKey];
                delete window[stackKey];
            }
        };
    })()""",
)

private fun readBrowserToken(): String? = js("window.localStorage.getItem('finance-tracker:session-token')")

private fun writeBrowserToken(value: String): Unit = js("window.localStorage.setItem('finance-tracker:session-token', value)")

private fun clearBrowserToken(): Unit = js("window.localStorage.removeItem('finance-tracker:session-token')")

private fun writeClipboard(value: String, done: (Boolean) -> Unit): Unit = js(
    "navigator.clipboard.writeText(value).then(() => done(true)).catch(() => done(false))",
)
