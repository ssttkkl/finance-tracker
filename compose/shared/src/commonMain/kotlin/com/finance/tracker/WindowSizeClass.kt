package com.finance.tracker

enum class WindowSizeClass {
    COMPACT,
    REGULAR,
    WIDE;

    companion object {
        fun fromWidthDp(widthDp: Int): WindowSizeClass = when {
            widthDp < REGULAR_MIN_WIDTH_DP -> COMPACT
            widthDp < WIDE_MIN_WIDTH_DP -> REGULAR
            else -> WIDE
        }

        const val REGULAR_MIN_WIDTH_DP = 600
        const val WIDE_MIN_WIDTH_DP = 1024
    }
}
