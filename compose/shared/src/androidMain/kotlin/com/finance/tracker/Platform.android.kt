package com.finance.tracker

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.util.TimeZone
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual val isWebPlatform: Boolean = false

@Composable
actual fun platformFinanceFontFamily(): FontFamily = FontFamily.SansSerif

actual val platformFinanceFontRequiresPreload: Boolean = false

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun getBrowserLocation(): BrowserLocation? = null

actual fun pushBrowserPath(path: String) = Unit

actual fun replaceBrowserPath(path: String) = Unit

actual fun observeBrowserHistory(onPopState: () -> Unit): () -> Unit = { }

actual fun observeBrowserTabNavigation(onTab: (backwards: Boolean) -> Unit): () -> Unit = { }

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

actual fun createPlatformTokenStore(): TokenStore {
    val context = AndroidTokenStoreContext.applicationContext
        ?: throw ApiFailure("token_store_unavailable", 0)
    return AndroidKeyStoreTokenStore(context)
}

actual fun readDisplayPreference(key: String): String? = runCatching {
    val context = AndroidTokenStoreContext.applicationContext ?: return null
    context.getSharedPreferences("finance-tracker-display", Context.MODE_PRIVATE).getString(key, null)
}.getOrNull()

actual fun writeDisplayPreference(key: String, value: String) {
    val context = AndroidTokenStoreContext.applicationContext ?: return
    context.getSharedPreferences("finance-tracker-display", Context.MODE_PRIVATE).edit().putString(key, value).apply()
}

actual fun getConfiguredApiOrigin(): String = ""

actual fun getLocalTimeZoneId(): String = TimeZone.getDefault().id

actual fun getLocalDateForInput(): String = java.time.LocalDate.now().toString()

actual fun getLocalDateForInstant(instant: String): String = runCatching {
    java.time.OffsetDateTime.parse(instant).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate().toString()
}.getOrElse { instant.take(10) }

actual fun formatLocalDateTime(instant: String): String = runCatching {
    java.time.OffsetDateTime.parse(instant).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrElse { instant.replace('T', ' ').take(16) }

actual fun getConfiguredWebOrigin(): String = runCatching {
    val context = AndroidTokenStoreContext.applicationContext ?: return ""
    val appInfo = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
    appInfo.metaData?.getString("FT_WEB_ORIGIN").orEmpty()
}.getOrDefault("")

actual suspend fun copyTextToClipboard(value: String): Boolean = runCatching {
    val context = AndroidTokenStoreContext.applicationContext ?: return false
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Finance Tracker invite", value))
    true
}.getOrDefault(false)

object AndroidTokenStoreContext {
    internal var applicationContext: Context? = null
        private set

    fun install(context: Context) {
        applicationContext = context.applicationContext
    }
}

private class AndroidKeyStoreTokenStore(context: Context) : TokenStore {
    private val preferences = context.getSharedPreferences("finance-tracker-session", Context.MODE_PRIVATE)

    override suspend fun get(): String? {
        val iv = preferences.getString(IV_KEY, null)
        val ciphertext = preferences.getString(DATA_KEY, null)
        if (iv == null && ciphertext == null) return null
        check(iv != null && ciphertext != null)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).decodeToString()
    }

    override suspend fun set(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = Base64.encodeToString(cipher.doFinal(value.encodeToByteArray()), Base64.NO_WRAP)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        check(preferences.edit().putString(IV_KEY, iv).putString(DATA_KEY, ciphertext).commit())
    }

    override suspend fun clear() {
        check(preferences.edit().remove(IV_KEY).remove(DATA_KEY).commit())
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "finance-tracker-session-token"
        const val IV_KEY = "token-iv"
        const val DATA_KEY = "token-ciphertext"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
