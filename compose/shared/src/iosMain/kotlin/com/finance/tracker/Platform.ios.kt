package com.finance.tracker

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import platform.UIKit.UIDevice
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIPasteboard

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual val isWebPlatform: Boolean = false

@Composable
actual fun platformFinanceFontFamily(): FontFamily = FontFamily.SansSerif

actual val platformFinanceFontRequiresPreload: Boolean = false

actual fun getPlatform(): Platform = IOSPlatform()

actual fun getBrowserLocation(): BrowserLocation? = null

actual fun pushBrowserPath(path: String) = Unit

actual fun replaceBrowserPath(path: String) = Unit

actual fun observeBrowserHistory(onPopState: () -> Unit): () -> Unit = { }

actual fun observeBrowserTabNavigation(onTab: (backwards: Boolean) -> Unit): () -> Unit = { }

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

actual fun getConfiguredApiOrigin(): String = ""

actual fun readDisplayPreference(key: String): String? = NSUserDefaults.standardUserDefaults.stringForKey(key)

actual fun writeDisplayPreference(key: String, value: String) { NSUserDefaults.standardUserDefaults.setObject(value, forKey = key) }

actual fun getLocalTimeZoneId(): String = NSDateFormatter().timeZone.name

actual fun getLocalDateForInput(): String = NSDateFormatter().apply {
    dateFormat = "yyyy-MM-dd"
}.stringFromDate(NSDate())

actual fun getLocalDateForInstant(instant: String): String {
    val parser = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX"
        lenient = true
    }
    val parsed = parser.dateFromString(instant) ?: parser.apply {
        dateFormat = "yyyy-MM-dd'T'HH:mm:ssXXXXX"
    }.dateFromString(instant)
    return parsed?.let {
        NSDateFormatter().apply {
            dateFormat = "yyyy-MM-dd"
        }.stringFromDate(it)
    } ?: instant.take(10)
}

actual fun formatLocalDateTime(instant: String): String {
    val parser = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX"
        lenient = true
    }
    val parsed = parser.dateFromString(instant) ?: parser.apply {
        dateFormat = "yyyy-MM-dd'T'HH:mm:ssXXXXX"
    }.dateFromString(instant)
    return parsed?.let {
        NSDateFormatter().apply {
            dateFormat = "yyyy-MM-dd HH:mm"
        }.stringFromDate(it)
    } ?: instant.replace('T', ' ').take(16)
}

actual fun getConfiguredWebOrigin(): String = NSBundle.mainBundle.objectForInfoDictionaryKey("FT_WEB_ORIGIN") as? String ?: ""

actual suspend fun copyTextToClipboard(value: String): Boolean = runCatching {
    UIPasteboard.generalPasteboard.string = value
    true
}.getOrDefault(false)
