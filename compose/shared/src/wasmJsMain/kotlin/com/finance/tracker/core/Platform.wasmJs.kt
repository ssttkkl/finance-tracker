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

private fun registerBrowserEscapeListener(onEscape: () -> Unit): () -> Unit = js(
    """(() => {
        const listener = event => {
            if (event.key !== 'Escape') return;
            const isVisible = element => {
                let current = element;
                while (current instanceof HTMLElement) {
                    const style = window.getComputedStyle(current);
                    if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0 || current.getAttribute('aria-hidden') === 'true' || current.hasAttribute('inert')) return false;
                    current = current.parentElement;
                }
                const bounds = element.getBoundingClientRect();
                return bounds.width > 0 && bounds.height > 0 && bounds.bottom > 0 && bounds.right > 0 && bounds.top < window.innerHeight && bounds.left < window.innerWidth;
            };
            const hasVisibleRoleModal = Array.from(document.querySelectorAll('[role="dialog"], [role="alertdialog"]')).some(isVisible);
            const hasMountedComposeModal = ['ledger.record-panel', 'ledger.filter.details', 'ledger.category-dialog']
                .some(id => document.getElementById(id) instanceof HTMLElement);
            if (hasVisibleRoleModal || hasMountedComposeModal) return;
            event.preventDefault();
            event.stopPropagation();
            onEscape();
        };
        window.addEventListener('keydown', listener, true);
        return () => window.removeEventListener('keydown', listener, true);
    })()""",
)

private fun registerBrowserModalEscapeListener(onEscape: () -> Unit): () -> Unit = js(
    """(() => {
        const listener = event => {
            if (event.key !== 'Escape') return;
            const isVisible = element => {
                let current = element;
                while (current instanceof HTMLElement) {
                    const style = window.getComputedStyle(current);
                    if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0 || current.getAttribute('aria-hidden') === 'true' || current.hasAttribute('inert')) return false;
                    current = current.parentElement;
                }
                const bounds = element.getBoundingClientRect();
                return bounds.width > 0 && bounds.height > 0 && bounds.bottom > 0 && bounds.right > 0 && bounds.top < window.innerHeight && bounds.left < window.innerWidth;
            };
            const hasVisibleAlert = Array.from(document.querySelectorAll('[role="alertdialog"]')).some(isVisible);
            const hasVisibleChoiceMenu = Array.from(document.querySelectorAll('[id="choice-overlay-menu"], [id^="choice-option-"]')).some(isVisible);
            if (hasVisibleAlert || hasVisibleChoiceMenu) return;
            event.preventDefault();
            event.stopPropagation();
            onEscape();
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
