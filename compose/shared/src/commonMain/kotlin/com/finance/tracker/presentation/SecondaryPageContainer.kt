package com.finance.tracker.presentation

import com.finance.tracker.core.PlatformModalEscapeHandler
import com.finance.tracker.core.WindowSizeClass
import com.finance.tracker.core.isWebPlatform
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_close

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.painterResource

/** Hosts a multi-step modal task using M3's compact full-screen and wider side-sheet variants. */
@Composable
internal fun SecondaryPageContainer(
    title: String,
    sizeClass: WindowSizeClass,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit,
    showFooter: Boolean = true,
    escapeEnabled: Boolean = true,
    contentStateKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PlatformModalEscapeHandler(enabled = isWebPlatform && escapeEnabled, onEscape = onClose)
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isWebPlatform && escapeEnabled,
            dismissOnClickOutside = sizeClass != WindowSizeClass.COMPACT,
        ),
    ) {
        if (sizeClass == WindowSizeClass.COMPACT) {
            SecondaryPageFullScreen(title, sizeClass, onClose, modifier, showFooter, contentStateKey, content, footer)
        } else {
            SecondaryPageSideSheet(title, sizeClass, onClose, modifier, showFooter, contentStateKey, content, footer)
        }
    }
}

@Composable
private fun SecondaryPageFullScreen(
    title: String,
    sizeClass: WindowSizeClass,
    onClose: () -> Unit,
    modifier: Modifier,
    showFooter: Boolean,
    contentStateKey: Any?,
    content: @Composable ColumnScope.() -> Unit,
    footer: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize()
            .testTag("secondary-page-fullscreen")
            .semantics { paneTitle = title },
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            SecondaryPageFullScreenHeader(title, onClose)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SecondaryPageBody(Modifier.weight(1f), MaterialTheme.colorScheme.surface, contentStateKey, content)
            if (showFooter) SecondaryPageFooter(sizeClass, MaterialTheme.colorScheme.surface, footer)
        }
    }
}

@Composable
private fun SecondaryPageSideSheet(
    title: String,
    sizeClass: WindowSizeClass,
    onClose: () -> Unit,
    modifier: Modifier,
    showFooter: Boolean,
    contentStateKey: Any?,
    content: @Composable ColumnScope.() -> Unit,
    footer: @Composable () -> Unit,
) {
    val widthFraction = when (sizeClass) {
        WindowSizeClass.REGULAR -> 0.9f
        WindowSizeClass.WIDE -> 0.7f
        WindowSizeClass.COMPACT -> 1f
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            modifier = modifier.fillMaxWidth(widthFraction)
                .fillMaxHeight()
                .testTag("secondary-page-modal")
                .semantics { paneTitle = title },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                SecondaryPageBasicHeader(title, onClose)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SecondaryPageBody(Modifier.weight(1f), MaterialTheme.colorScheme.surfaceContainerLow, contentStateKey, content)
                if (showFooter) SecondaryPageFooter(sizeClass, MaterialTheme.colorScheme.surfaceContainerLow, footer)
            }
        }
    }
}

@Composable
private fun SecondaryPageBasicHeader(title: String, onClose: () -> Unit) {
    val closeLabel = localizedText("copy_6c14bd7f6f")
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 24.dp, end = 24.dp).testTag("secondary-page-header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.semantics { contentDescription = closeLabel }.testTag("secondary-page-close"),
        ) {
            Icon(painterResource(Res.drawable.ic_close), contentDescription = null, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            modifier = Modifier.weight(1f).testTag("secondary-page-title"),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SecondaryPageFullScreenHeader(
    title: String,
    onClose: () -> Unit,
) {
    val closeLabel = localizedText("copy_6c14bd7f6f")
    TopAppBar(
        modifier = Modifier.testTag("secondary-page-header"),
        title = {
            Text(
                title,
                modifier = Modifier.testTag("secondary-page-title"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = closeLabel }.testTag("secondary-page-close"),
            ) {
                Icon(painterResource(Res.drawable.ic_close), contentDescription = null, modifier = Modifier.size(24.dp))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun SecondaryPageBody(
    modifier: Modifier,
    surface: androidx.compose.ui.graphics.Color,
    contentStateKey: Any?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().background(surface).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        key(contentStateKey) { content() }
    }
}

@Composable
private fun SecondaryPageFooter(
    sizeClass: WindowSizeClass,
    containerColor: androidx.compose.ui.graphics.Color,
    footer: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(containerColor)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.testTag("secondary-page-footer-divider"))
        if (sizeClass == WindowSizeClass.COMPACT) {
            Box(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 24.dp).testTag("secondary-page-footer"),
            ) { footer() }
        } else {
            Box(
                Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 24.dp).testTag("secondary-page-footer"),
                contentAlignment = Alignment.CenterStart,
            ) { footer() }
        }
    }
}
