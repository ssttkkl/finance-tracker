package com.finance.tracker.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import io.github.oikvpqya.compose.fastscroller.ScrollbarAdapter
import io.github.oikvpqya.compose.fastscroller.VerticalScrollbar
import io.github.oikvpqya.compose.fastscroller.indicator.IndicatorShape
import io.github.oikvpqya.compose.fastscroller.material3.defaultMaterialScrollbarStyle
import com.finance.tracker.core.SemanticIds
import com.finance.tracker.domain.CashMonthPosition
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.math.floor

private const val DRAG_SEEK_INTERVAL_MS = 72L
private const val FINAL_SEEK_TIMEOUT_MS = 2_000L
private const val COMPACT_SCROLLBAR_IDLE_MS = 2_000L
private const val SCROLLBAR_FADE_DURATION_MS = 200
private const val SCROLLBAR_HIT_WIDTH_DP = 40

internal fun cashLedgerOrdinalForProgress(progress: Float, totalCount: Int): Int? {
    if (totalCount <= 0) return null
    if (totalCount == 1) return 0
    val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    return (safeProgress * (totalCount - 1)).roundToInt().coerceIn(0, totalCount - 1)
}

internal fun cashLedgerPixelOffsetForPosition(position: Double, rowSize: Int): Int {
    if (rowSize <= 0 || !position.isFinite()) return 0
    return ((position - floor(position)) * rowSize).roundToInt().coerceIn(0, rowSize - 1)
}

private data class ScrollbarSeekRequest(val position: Double, val exact: Boolean)

private class CashLedgerOrdinalScrollbarAdapter(
    private val totalCount: androidx.compose.runtime.State<Int>,
    private val positionState: MutableState<Double>,
    private val onSeek: (Double) -> Unit,
) : ScrollbarAdapter {
    override val scrollOffset: Double
        get() = positionState.value

    override val contentSize: Double
        get() = totalCount.value.coerceAtLeast(0).toDouble()

    // The visible list viewport is represented by one ordinal so that the thumb
    // spans exactly [0, totalCount - 1], independent of variable row heights.
    override val viewportSize: Double = 1.0

    override suspend fun scrollTo(scrollOffset: Double) {
        val count = totalCount.value
        if (count <= 1) return
        val position = scrollOffset.coerceIn(0.0, (count - 1).toDouble())
        positionState.value = position
        onSeek(position)
    }
}

@Composable
internal fun CashLedgerScrollbar(
    listState: LazyListState,
    totalCount: Int,
    monthPositions: List<CashMonthPosition>,
    isOrdinalLoaded: (Int) -> Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val seekRequests = remember(listState) { Channel<ScrollbarSeekRequest>(Channel.CONFLATED) }
    val currentIsOrdinalLoaded = rememberUpdatedState(isOrdinalLoaded)
    val currentMonthPositions = rememberUpdatedState(monthPositions)
    val currentTotalCount = rememberUpdatedState(totalCount.coerceAtLeast(0))
    val currentVisibleTransactions = listState.layoutInfo.visibleItemsInfo.count { it.index < totalCount }
    val visibleTransactionCount = currentVisibleTransactions.coerceAtLeast(1)
    val firstVisibleOrdinal = listState.firstVisibleItemIndex.coerceIn(0, (totalCount - 1).coerceAtLeast(0))
    val offsetState = remember(listState) { mutableStateOf(firstVisibleOrdinal.toDouble()) }
    val pendingSeekState = remember(listState) { mutableStateOf(false) }
    val interactionSource = remember(listState) { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    val compactScrollbarFocused = remember(listState) { mutableStateOf(false) }
    val compactControlActive = remember(listState) { mutableStateOf(false) }
    val compactInteractionRevision = remember(listState) { mutableStateOf(0) }
    var compactScrollbarVisible by remember(listState, compact) { mutableStateOf(false) }
    val currentIsDragged = rememberUpdatedState(isDragged)
    val wasDragged = remember(listState) { mutableStateOf(false) }
    val description = localizedText("ledger_scrollbar_description")
    val progress = if (totalCount <= 1) 0f else offsetState.value.coerceIn(0.0, (totalCount - 1).toDouble()).toFloat() / (totalCount - 1)
    val canSeek = totalCount > 1

    LaunchedEffect(listState, compact) {
        if (!compact) {
            compactScrollbarVisible = true
        } else {
            compactScrollbarVisible = false
            snapshotFlow {
                listState.isScrollInProgress || currentIsDragged.value || compactScrollbarFocused.value || compactControlActive.value
            }
                .collectLatest { isActive ->
                    if (isActive) {
                        compactScrollbarVisible = true
                    } else {
                        delay(COMPACT_SCROLLBAR_IDLE_MS)
                        compactScrollbarVisible = false
                    }
                }
        }
    }
    LaunchedEffect(compactInteractionRevision.value, compact) {
        if (compact && compactInteractionRevision.value > 0) {
            delay(COMPACT_SCROLLBAR_IDLE_MS)
            compactControlActive.value = false
        }
    }
    val markCompactControlActivity = {
        if (compact) {
            compactControlActive.value = true
            compactInteractionRevision.value += 1
        }
    }

    val requestTarget: (Double, Boolean) -> Unit = { requestedPosition, exact ->
        val count = currentTotalCount.value
        val position = requestedPosition.coerceIn(0.0, (count - 1).coerceAtLeast(0).toDouble())
        if (count > 1) {
            offsetState.value = position
            pendingSeekState.value = true
            seekRequests.trySend(ScrollbarSeekRequest(position, exact))
        }
    }
    val currentRequestTarget = rememberUpdatedState(requestTarget)
    val adapter = remember(listState) {
        CashLedgerOrdinalScrollbarAdapter(
            totalCount = currentTotalCount,
            positionState = offsetState,
            onSeek = { position -> currentRequestTarget.value(position, false) },
        )
    }

    LaunchedEffect(listState, totalCount) {
        snapshotFlow {
            val ordinal = listState.firstVisibleItemIndex
            val rowSize = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == ordinal }?.size?.coerceAtLeast(1) ?: 1
            ordinal.toDouble() + listState.firstVisibleItemScrollOffset.toDouble() / rowSize
        }.collect { position ->
            if (!currentIsDragged.value && !pendingSeekState.value) {
                offsetState.value = position.coerceIn(0.0, (currentTotalCount.value - 1).coerceAtLeast(0).toDouble())
            }
        }
    }

    LaunchedEffect(isDragged, totalCount) {
        if (isDragged) {
            wasDragged.value = true
        } else if (wasDragged.value) {
            wasDragged.value = false
            currentRequestTarget.value(offsetState.value, true)
        }
    }

    LaunchedEffect(seekRequests, totalCount) {
        for (request in seekRequests) {
            var latest = request
            if (!request.exact) {
                val requestedOrdinal = floor(request.position).toInt()
                delay(if (currentIsOrdinalLoaded.value(requestedOrdinal)) 16L else DRAG_SEEK_INTERVAL_MS)
                while (true) {
                    latest = seekRequests.tryReceive().getOrNull() ?: break
                }
            }
            val count = currentTotalCount.value
            if (count > 0) {
                val position = latest.position.coerceIn(0.0, (count - 1).toDouble())
                val target = floor(position).toInt().coerceIn(0, count - 1)
                val visibleRows = listState.layoutInfo.visibleItemsInfo.filter { it.index in 0 until count }
                val rowSize = visibleRows.firstOrNull { it.index == target }?.size
                    ?: visibleRows.map { it.size }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                    ?: 1
                listState.scrollToItem(target, cashLedgerPixelOffsetForPosition(position, rowSize))
                if (latest.exact) {
                    val loaded = withTimeoutOrNull(FINAL_SEEK_TIMEOUT_MS) {
                        snapshotFlow { currentIsOrdinalLoaded.value(target) }.first { it }
                    } == true
                    if (loaded) {
                        val actualSize = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }?.size ?: rowSize
                        listState.scrollToItem(target, cashLedgerPixelOffsetForPosition(position, actualSize))
                    }
                    pendingSeekState.value = false
                    offsetState.value = position
                }
            } else {
                pendingSeekState.value = false
            }
        }
    }

    val scrollbarStyle = defaultMaterialScrollbarStyle()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val trackHeightPx = with(density) { maxHeight.toPx() }
        val thumbHeightPx = with(density) {
            maxOf(scrollbarStyle.minimalHeight.toPx(), trackHeightPx / totalCount.coerceAtLeast(1))
        }
        val scrollbarHitWidth = SCROLLBAR_HIT_WIDTH_DP
        val scrollbarVisualOffsetX = (scrollbarHitWidth.dp - scrollbarStyle.thickness) / 2
        val monthBubbleOffsetX = -scrollbarStyle.thickness
        val bubbleHeightPx = with(density) { 56.dp.toPx() }
        val scrollbarModifier = Modifier
            .align(Alignment.CenterEnd)
            .width(scrollbarHitWidth.dp)
            .fillMaxHeight()
            .onFocusChanged {
                compactScrollbarFocused.value = it.isFocused
                if (it.isFocused) markCompactControlActivity()
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !canSeek) return@onKeyEvent false
                val first = offsetState.value.roundToInt()
                val target = when (event.key) {
                    Key.DirectionUp -> first - 1
                    Key.DirectionDown -> first + 1
                    Key.PageUp -> first - visibleTransactionCount
                    Key.PageDown -> first + visibleTransactionCount
                    Key.MoveHome -> 0
                    Key.MoveEnd -> totalCount - 1
                    else -> return@onKeyEvent false
                }.coerceIn(0, totalCount - 1)
                markCompactControlActivity()
                currentRequestTarget.value(target.toDouble(), true)
                true
            }
            .focusable(enabled = canSeek, interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = canSeek,
                role = Role.Button,
            ) {
                markCompactControlActivity()
                val nextPage = (offsetState.value + visibleTransactionCount)
                    .coerceIn(0.0, (currentTotalCount.value - 1).coerceAtLeast(0).toDouble())
                currentRequestTarget.value(nextPage, true)
            }
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                if (!canSeek) disabled()
                setProgress { requestedProgress ->
                    val ordinal = cashLedgerOrdinalForProgress(requestedProgress, currentTotalCount.value)
                    if (ordinal == null) false else {
                        currentRequestTarget.value(ordinal.toDouble(), true)
                        true
                    }
                }
            }
            .testTag(SemanticIds.ledgerScrollbar)
        AnimatedVisibility(
            visible = !compact || (compactScrollbarVisible && canSeek),
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(animationSpec = tween(SCROLLBAR_FADE_DURATION_MS)),
            exit = fadeOut(animationSpec = tween(SCROLLBAR_FADE_DURATION_MS)),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(scrollbarStyle.thickness)
                        .fillMaxHeight()
                        .testTag(SemanticIds.ledgerScrollbarTrack),
                )
                VerticalScrollbar(
                    adapter = adapter,
                    style = scrollbarStyle,
                    modifier = scrollbarModifier,
                    interactionSource = interactionSource,
                    enablePressToScroll = false,
                    visualOffsetX = scrollbarVisualOffsetX,
                    indicator = { position, isVisible ->
                        if (isVisible && trackHeightPx > 0f) {
                            Box(
                                Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(scrollbarStyle.thickness)
                                    .height(with(density) { thumbHeightPx.toDp() })
                                    .testTag(SemanticIds.ledgerScrollbarThumb),
                            )
                        }
                        val month = cashLedgerMonthForOrdinal(currentMonthPositions.value, offsetState.value.toInt())
                        if (isDragged && isVisible && month != null && trackHeightPx > 0f) {
                            val thumbCenter = (position + thumbHeightPx / 2f).coerceIn(
                                bubbleHeightPx / 2f,
                                (trackHeightPx - bubbleHeightPx / 2f).coerceAtLeast(bubbleHeightPx / 2f),
                            )
                            val verticalOffset = with(density) { (thumbCenter - trackHeightPx / 2f).toDp() }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .offset(x = monthBubbleOffsetX, y = verticalOffset)
                                    .graphicsLayer { clip = false },
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 72.dp, minHeight = 56.dp)
                                        .testTag(SemanticIds.ledgerScrollbarMonthBubble),
                                    shape = remember { IndicatorShape() },
                                    color = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = localMonthLabel(month),
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}
