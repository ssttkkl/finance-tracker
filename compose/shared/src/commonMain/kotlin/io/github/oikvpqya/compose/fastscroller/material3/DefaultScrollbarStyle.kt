package io.github.oikvpqya.compose.fastscroller.material3

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.github.oikvpqya.compose.fastscroller.ScrollbarStyle
import io.github.oikvpqya.compose.fastscroller.ThumbStyle
import io.github.oikvpqya.compose.fastscroller.TrackStyle

// Modified by Finance Tracker: keep MaterialTheme reads in the shared app module for Wasm.
/**
 * Simple default [ScrollbarStyle] with applying MaterialTheme.
 */
@Composable
fun defaultMaterialScrollbarStyle(): ScrollbarStyle {
    return ScrollbarStyle(
        minimalHeight = 52.dp,
        thickness = 4.dp,
        hoverDurationMillis = 300,
        thumbStyle = ThumbStyle(
            shape = RoundedCornerShape(4.dp),
            unhoverColor = MaterialTheme.colorScheme.primary,
            hoverColor = MaterialTheme.colorScheme.primary,
        ),
        trackStyle = TrackStyle(
            shape = RoundedCornerShape(4.dp),
            unhoverColor = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp),
            hoverColor = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp),
        ),
    )
}
