package com.finance.tracker.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.WindowSizeClass
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Hallmark: task-grouped settings, existing FinanceTheme; one scroll owner and one directory. */
@Composable
internal fun ManagementLayout(
    sizeClass: WindowSizeClass,
    isAdmin: Boolean,
    initialSection: ManagementSection,
    ready: Boolean,
    directoryOpen: Boolean,
    onCloseDirectory: (restoreFocus: Boolean) -> Unit,
    status: @Composable () -> Unit = {},
    content: @Composable (ManagementSection) -> Unit,
) {
    val sections = managementSections(isAdmin)
    val scroll = rememberScrollState()
    var initialAnchoring by remember(initialSection) { mutableStateOf(initialSection != ManagementSection.WORKSPACE) }
    val offsets = remember { mutableStateMapOf<ManagementSection, Int>() }
    val heights = remember { mutableStateMapOf<ManagementSection, Int>() }
    val focusTargets = remember { ManagementSection.entries.associateWith { FocusRequester() } }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val insetPx = with(density) { 24.dp.roundToPx() }
    val current by remember(isAdmin, insetPx) { derivedStateOf { currentManagementSection(offsets.filterKeys { it in sections }, scroll.value + insetPx + 1) } }
    val navigate: (ManagementSection) -> Unit = { section ->
        initialAnchoring = false
        onCloseDirectory(false)
        scope.launch {
            val offset = offsets[section] ?: return@launch
            scroll.scrollTo((offset - insetPx).coerceAtLeast(0))
            snapshotFlow { windowInfo.isWindowFocused }.first { it }
            withFrameNanos { }
            focusTargets.getValue(section).requestFocus()
        }
    }
    LaunchedEffect(initialSection, ready, initialAnchoring, offsets[initialSection], scroll.maxValue) {
        if (ready && initialAnchoring) {
            withFrameNanos { }
            val offset = offsets[initialSection] ?: return@LaunchedEffect
            scroll.scrollTo((offset - insetPx).coerceAtLeast(0))
        }
    }
    LaunchedEffect(sizeClass) {
        if (sizeClass != WindowSizeClass.COMPACT && directoryOpen) onCloseDirectory(false)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("workspace-management.screen")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { status() }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewportHeight = maxHeight
            val sectionGap = if (sizeClass == WindowSizeClass.COMPACT) 16.dp else 24.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .onPreviewKeyEvent { initialAnchoring = false; false }
                        .pointerInput(initialSection) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) initialAnchoring = false
                                }
                            }
                        }
                        .verticalScroll(scroll)
                        .padding(24.dp).testTag("management.content"),
                    verticalArrangement = Arrangement.spacedBy(sectionGap),
                ) {
                    sections.forEach { section ->
                        key(section) {
                            Box(
                                Modifier.fillMaxWidth()
                                    .onGloballyPositioned { coordinates ->
                                        offsets[section] = coordinates.positionInParent().y.roundToInt()
                                        heights[section] = coordinates.size.height
                                    }
                                    .focusRequester(focusTargets.getValue(section)).focusable()
                                    .testTag("management.section.${section.key}"),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    if (section == sections.first { it.group == section.group }) {
                                        Text(
                                            localizedText(section.group.titleKey),
                                            Modifier.padding(bottom = 24.dp).semantics { heading() }
                                                .testTag("management.group.${section.group.key}"),
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                    }
                                    content(section)
                                    if (section != sections.last() && sections[sections.indexOf(section) + 1].group != section.group) {
                                        HorizontalDivider(Modifier.padding(top = sectionGap), color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                            }
                        }
                    }
                    // Allows even the last short section to align beneath the fixed Toolbar.
                    val lastHeight = with(density) { (heights[sections.last()] ?: 0).toDp() }
                    Spacer(Modifier.height((viewportHeight - lastHeight - 48.dp).coerceAtLeast(0.dp)))
                }
                if (sizeClass != WindowSizeClass.COMPACT) {
                    ManagementDirectory(
                        sections, current, navigate,
                        Modifier.width(if (sizeClass == WindowSizeClass.REGULAR) 184.dp else 208.dp)
                            .fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(top = 24.dp, end = 16.dp).testTag("management.directory"),
                    )
                }
            }
        }
    }
    if (directoryOpen && sizeClass == WindowSizeClass.COMPACT) {
        SecondaryPageContainer(
            title = localizedText("management_directory"),
            sizeClass = sizeClass,
            onClose = { onCloseDirectory(true) },
            modifier = Modifier.testTag("management.directory.dialog"),
            showFooter = false,
            footer = {},
        ) {
            ManagementDirectory(sections, current, navigate, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ManagementDirectory(
    sections: List<ManagementSection>,
    current: ManagementSection,
    onSelected: (ManagementSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sections.groupBy { it.group }.forEach { (group, children) ->
            Column(Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { onSelected(children.first()) },
                    modifier = Modifier.fillMaxWidth().semantics { heading() }
                        .testTag("management.directory.group.${group.key}"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Text(localizedText(group.titleKey), color = if (group == current.group)
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                children.forEach { section ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(24.dp).then(
                            if (section == current) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier,
                        ))
                        TextButton(
                            onClick = { onSelected(section) },
                            modifier = Modifier.weight(1f).semantics { selected = section == current }
                                .testTag("management.directory.item.${section.key}"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                        ) {
                            Box(Modifier.fillMaxWidth()) {
                                Text(localizedText(section.titleKey), color = if (section == current)
                                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ManagementSettingRow(
    label: String,
    sizeClass: WindowSizeClass,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (sizeClass == WindowSizeClass.WIDE) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(label, Modifier.width(160.dp).alignByBaseline(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f).alignByBaseline()) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    } else {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** A task region in the shared management surface, without a second container. */
@Composable
internal fun ManagementSectionContent(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
