package com.finance.tracker.core.design

import androidx.compose.ui.graphics.Color

/** Semantic roles shared by Finance UI components and Pen variables. */
data class FinanceColorRoles(
    val background: Color,
    val foreground: Color,
    val surface: Color,
    val overlay: Color,
    val accent: Color,
    val accentForeground: Color,
    val danger: Color,
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
        overlay = GeneratedTokens.Light.Color.SemanticOverlay,
        accent = GeneratedTokens.Core.Color.SemanticAccent,
        accentForeground = GeneratedTokens.Core.Color.SemanticAccentForeground,
        danger = GeneratedTokens.Core.Color.SemanticDanger,
        field = GeneratedTokens.Light.Color.SemanticField,
        fieldBorder = GeneratedTokens.Light.Color.SemanticFieldBorder,
        focus = GeneratedTokens.Light.Color.SemanticFocus,
        disabled = GeneratedTokens.Light.Color.SemanticDisabled,
    )

    val darkColors = FinanceColorRoles(
        background = GeneratedTokens.Dark.Color.SemanticBackground,
        foreground = GeneratedTokens.Dark.Color.SemanticForeground,
        surface = GeneratedTokens.Dark.Color.SemanticSurface,
        overlay = GeneratedTokens.Dark.Color.SemanticOverlay,
        accent = GeneratedTokens.Core.Color.SemanticAccent,
        accentForeground = GeneratedTokens.Core.Color.SemanticAccentForeground,
        danger = GeneratedTokens.Dark.Color.SemanticDanger,
        field = GeneratedTokens.Dark.Color.SemanticField,
        fieldBorder = GeneratedTokens.Dark.Color.SemanticFieldBorder,
        focus = GeneratedTokens.Dark.Color.SemanticFocus,
        disabled = GeneratedTokens.Dark.Color.SemanticDisabled,
    )
}
