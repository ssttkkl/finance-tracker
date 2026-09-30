package com.finance.tracker.core

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

import com.finance.tracker.core.design.GeneratedTokens
import com.finance.tracker.core.design.FinanceTokens

private val cobaltLight = lightColorScheme(
    primary = FinanceTokens.lightColors.accent,
    onPrimary = FinanceTokens.lightColors.accentForeground,
    primaryContainer = Color(0xFFD6E7FF),
    onPrimaryContainer = Color(0xFF001C3A),
    secondary = Color(0xFF526174),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    background = FinanceTokens.lightColors.background,
    onBackground = FinanceTokens.lightColors.foreground,
    surface = FinanceTokens.lightColors.surface,
    onSurface = FinanceTokens.lightColors.foreground,
    surfaceVariant = Color(0xFFE0E5EC),
    onSurfaceVariant = Color(0xFF434850),
    outline = GeneratedTokens.Light.Color.ContentMuted,
    outlineVariant = FinanceTokens.lightColors.fieldBorder,
    error = FinanceTokens.lightColors.danger,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val cobaltDark = darkColorScheme(
    primary = FinanceTokens.darkColors.accent,
    onPrimary = FinanceTokens.darkColors.accentForeground,
    primaryContainer = Color(0xFF174A7A),
    onPrimaryContainer = Color(0xFFD6E7FF),
    secondary = Color(0xFFBAC7DC),
    onSecondary = Color(0xFF243140),
    secondaryContainer = Color(0xFF3A4859),
    onSecondaryContainer = Color(0xFFD6E3F7),
    background = FinanceTokens.darkColors.background,
    onBackground = FinanceTokens.darkColors.foreground,
    surface = FinanceTokens.darkColors.surface,
    onSurface = FinanceTokens.darkColors.foreground,
    surfaceVariant = Color(0xFF434850),
    onSurfaceVariant = Color(0xFFC3C7D0),
    outline = GeneratedTokens.Dark.Color.ContentMuted,
    outlineVariant = FinanceTokens.darkColors.fieldBorder,
    error = FinanceTokens.darkColors.danger,
    onError = FinanceTokens.darkColors.background,
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
        shapes = Shapes(
            extraSmall = RoundedCornerShape(GeneratedTokens.Core.Radius.Token1),
            small = RoundedCornerShape(GeneratedTokens.Core.Radius.Token1),
            medium = RoundedCornerShape(GeneratedTokens.Core.Radius.Token2),
            large = RoundedCornerShape(GeneratedTokens.Core.Radius.Token2),
            extraLarge = RoundedCornerShape(GeneratedTokens.Core.Radius.Token2),
        ),
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
