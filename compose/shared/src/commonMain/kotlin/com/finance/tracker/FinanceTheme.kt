package com.finance.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily

private val cobaltLight = lightColorScheme(
    primary = Color(0xFF0A63B8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E7FF),
    onPrimaryContainer = Color(0xFF001C3A),
    secondary = Color(0xFF526174),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E5EC),
    onSurfaceVariant = Color(0xFF434850),
    outline = Color(0xFF737982),
    outlineVariant = Color(0xFFC3C7D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val cobaltDark = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF174A7A),
    onPrimaryContainer = Color(0xFFD6E7FF),
    secondary = Color(0xFFBAC7DC),
    onSecondary = Color(0xFF243140),
    secondaryContainer = Color(0xFF3A4859),
    onSecondaryContainer = Color(0xFFD6E3F7),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF434850),
    onSurfaceVariant = Color(0xFFC3C7D0),
    outline = Color(0xFF8D919A),
    outlineVariant = Color(0xFF434850),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private fun financeTypography(family: FontFamily) = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}

@Composable
fun FinanceTheme(content: @Composable () -> Unit) {
    val fontFamily = platformFinanceFontFamily()
    var fontReady by remember(fontFamily) { mutableStateOf(!platformFinanceFontRequiresPreload) }
    val fontFamilyResolver = LocalFontFamilyResolver.current

    LaunchedEffect(fontFamily, fontFamilyResolver) {
        if (platformFinanceFontRequiresPreload) {
            runCatching {
                fontFamilyResolver.preload(fontFamily)
            }
            fontReady = true
        }
    }

    val colorScheme = if (isSystemInDarkTheme()) cobaltDark else cobaltLight
    MaterialTheme(
        colorScheme = colorScheme,
        typography = financeTypography(fontFamily),
    ) {
        if (fontReady) {
            content()
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
