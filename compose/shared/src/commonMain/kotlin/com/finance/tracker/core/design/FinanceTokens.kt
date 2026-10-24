package com.finance.tracker.core.design

import androidx.compose.ui.graphics.Color

/** Semantic roles consumed by Finance UI components. */
data class FinanceColorRoles(
    val background: Color,
    val foreground: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val overlay: Color,
    val accent: Color,
    val accentForeground: Color,
    val danger: Color,
    val successSurface: Color,
    val warning: Color,
    val warningSurface: Color,
    val field: Color,
    val fieldBorder: Color,
    val focus: Color,
    val disabled: Color,
)

object FinanceTokens {
    fun forTheme(dark: Boolean): FinanceColorRoles = if (dark) darkColors else lightColors

    val lightColors = FinanceColorRoles(
        background = GeneratedTokens.Light.Color.SemanticBackground,
        foreground = GeneratedTokens.Light.Color.SemanticForeground,
        surface = GeneratedTokens.Light.Color.SemanticSurface,
        surfaceMuted = GeneratedTokens.Light.Color.SurfaceMuted,
        overlay = GeneratedTokens.Light.Color.SemanticOverlay,
        accent = GeneratedTokens.Light.Color.SemanticAccent,
        accentForeground = GeneratedTokens.Core.Color.SemanticAccentForeground,
        danger = GeneratedTokens.Core.Color.SemanticDanger,
        successSurface = GeneratedTokens.Light.Color.StateSuccessSurface,
        warning = GeneratedTokens.Core.Color.SemanticWarning,
        warningSurface = GeneratedTokens.Light.Color.StateWarningSurface,
        field = GeneratedTokens.Light.Color.SemanticField,
        fieldBorder = GeneratedTokens.Light.Color.SemanticFieldBorder,
        focus = GeneratedTokens.Light.Color.SemanticFocus,
        disabled = GeneratedTokens.Light.Color.SemanticDisabled,
    )

    val darkColors = FinanceColorRoles(
        background = GeneratedTokens.Dark.Color.SemanticBackground,
        foreground = GeneratedTokens.Dark.Color.SemanticForeground,
        surface = GeneratedTokens.Dark.Color.SemanticSurface,
        surfaceMuted = GeneratedTokens.Dark.Color.SurfaceMuted,
        overlay = GeneratedTokens.Dark.Color.SemanticOverlay,
        accent = GeneratedTokens.Core.Color.SemanticAccent,
        accentForeground = GeneratedTokens.Dark.Color.SemanticAccentForeground,
        danger = GeneratedTokens.Dark.Color.SemanticDanger,
        successSurface = GeneratedTokens.Dark.Color.StateSuccessSurface,
        warning = GeneratedTokens.Dark.Color.SemanticWarning,
        warningSurface = GeneratedTokens.Dark.Color.StateWarningSurface,
        field = GeneratedTokens.Dark.Color.SemanticField,
        fieldBorder = GeneratedTokens.Dark.Color.SemanticFieldBorder,
        focus = GeneratedTokens.Dark.Color.SemanticFocus,
        disabled = GeneratedTokens.Dark.Color.SemanticDisabled,
    )
}
