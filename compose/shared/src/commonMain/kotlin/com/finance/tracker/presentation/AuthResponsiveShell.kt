package com.finance.tracker.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.SemanticIds
import com.finance.tracker.core.WindowSizeClass

@Composable
internal fun AuthResponsiveShell(
    content: @Composable (modifier: Modifier, compact: Boolean) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().testTag(SemanticIds.authScreen),
    ) {
        val compact = WindowSizeClass.fromWidthDp(maxWidth.value.toInt()) == WindowSizeClass.COMPACT
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (compact) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background),
            contentAlignment = if (compact) Alignment.TopCenter else Alignment.Center,
        ) {
            if (compact) {
                content(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, top = 52.dp, end = 24.dp, bottom = 24.dp),
                    true,
                )
            } else {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().testTag(SemanticIds.authCard),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        content(
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(32.dp),
                            false,
                        )
                    }
                }
            }
        }
    }
}
