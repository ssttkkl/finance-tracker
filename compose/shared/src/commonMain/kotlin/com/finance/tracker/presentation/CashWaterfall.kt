package com.finance.tracker.presentation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.finance.tracker.core.design.GeneratedTokens
import com.finance.tracker.core.SemanticIds
import com.finance.tracker.domain.CashMonthPosition
import com.finance.tracker.domain.CashMonthlySummary
import com.finance.tracker.core.WindowSizeClass
import kotlin.math.roundToInt

internal data class CashWaterfallHorizontalScroll(val state: ScrollState, val visibleWidth: Dp)

@Composable
internal fun CashWaterfallSectionHeader(
    month: String,
    summary: CashMonthlySummary?,
    showTotals: Boolean,
    sizeClass: WindowSizeClass,
    showSelection: Boolean,
    auxiliaryColumn: CashLedgerAuxiliaryColumn,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        if (showTotals) {
            CashProjectionMonthHeader(
                CashProjectionMonthGroup(month, emptyList(), summary),
                compact = sizeClass == WindowSizeClass.COMPACT,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            Text(localMonthLabel(month), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium)
        }
        if (sizeClass != WindowSizeClass.COMPACT) {
            CashLedgerTableHeader(Modifier.padding(horizontal = 16.dp), showSelection, auxiliaryColumn)
        }
    }
}

@Composable
internal fun CashWaterfallItem(
    month: String,
    first: Boolean,
    last: Boolean,
    gapBefore: Boolean,
    summary: CashMonthlySummary?,
    showTotals: Boolean,
    sizeClass: WindowSizeClass,
    showSelection: Boolean,
    auxiliaryColumn: CashLedgerAuxiliaryColumn,
    onHeaderHeight: (String, Int) -> Unit,
    row: @Composable () -> Unit,
) {
    CashLedgerGroupSurface(first, last, gapBefore) {
        Column(Modifier.fillMaxWidth()) {
            if (first) CashWaterfallSectionHeader(month, summary, showTotals, sizeClass, showSelection,
                auxiliaryColumn, Modifier.onSizeChanged { onHeaderHeight(month, it.height) })
            row()
        }
    }
}

@Composable
internal fun CashWaterfallViewport(
    listState: LazyListState,
    totalCount: Int,
    monthAt: (Int) -> String?,
    monthStartAt: (Int) -> Int,
    monthPositions: List<CashMonthPosition>,
    isOrdinalLoaded: (Int) -> Boolean,
    summaryForMonth: (String) -> CashMonthlySummary?,
    showTotals: Boolean,
    sizeClass: WindowSizeClass,
    showSelection: Boolean,
    auxiliaryColumn: CashLedgerAuxiliaryColumn,
    modifier: Modifier = Modifier,
    horizontalScroll: CashWaterfallHorizontalScroll? = null,
    content: @Composable BoxScope.((String, Int) -> Unit) -> Unit,
) {
    val heights = remember(listState) { mutableStateMapOf<String, Int>() }
    Box(modifier) {
        content { month, height -> if (heights[month] != height) heights[month] = height }
        val visible = listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { item ->
            if (item.index in 0 until totalCount) monthAt(item.index)?.let { item to it } else null
        }
        val stickyMonth = if (visible == null || !listState.canScrollBackward) null else {
            val (item, month) = visible
            val start = monthStartAt(item.index)
            val header = heights[month] ?: 0
            val startItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == start }
            val inset = with(LocalDensity.current) {
                ((if (start > 0) GeneratedTokens.Core.Space.Token3 else 0.dp) + 8.dp).toPx().roundToInt()
            }
            val incomingBelowTop = start > 0 && startItem != null && startItem.offset + inset > 0
            when {
                incomingBelowTop -> monthPositions.lastOrNull { it.startIndex < start }?.month
                    ?: monthAt((start - 1).coerceAtLeast(0))
                item.index > start -> month
                item.index == start && start == 0 && header > 0 && item.offset + header <= 0 -> month
                item.index == start -> month
                else -> null
            }
        }
        if (stickyMonth != null) {
            val stickyTag = if (sizeClass == WindowSizeClass.COMPACT) null else Modifier.testTag(SemanticIds.ledgerStickyTableHeader)
            Surface(
                Modifier.align(Alignment.TopStart).fillMaxWidth().testTag(SemanticIds.ledgerStickySummary),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
            ) {
                CashWaterfallSectionHeader(stickyMonth, summaryForMonth(stickyMonth), showTotals,
                    sizeClass, showSelection, auxiliaryColumn, stickyTag ?: Modifier)
            }
        }
        if (totalCount > 0) {
            val density = LocalDensity.current
            val railPosition = if (horizontalScroll == null) Modifier else Modifier.offset {
                IntOffset(horizontalScroll.state.value + with(density) { horizontalScroll.visibleWidth.roundToPx() }
                    - listState.layoutInfo.viewportSize.width, 0)
            }
            CashLedgerScrollbar(listState, totalCount, monthPositions, isOrdinalLoaded,
                compact = sizeClass == WindowSizeClass.COMPACT,
                modifier = Modifier.align(Alignment.CenterEnd).then(railPosition).zIndex(1f))
        }
    }
}
