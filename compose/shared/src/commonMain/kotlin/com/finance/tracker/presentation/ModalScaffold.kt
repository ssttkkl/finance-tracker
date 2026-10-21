package com.finance.tracker.presentation

import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_close

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource

internal enum class ModalHeaderVariant {
    BasicDialog,
    FullScreenDialog,
    SideSheet,
}

/** Shared inner structure for custom modal surfaces; the caller owns the outer host and surface. */
@Composable
internal fun ModalScaffold(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    contentFill: Boolean = true,
    dividerColor: Color = MaterialTheme.colorScheme.outlineVariant,
    footerDividerTestTag: String? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier) {
        header()
        HorizontalDivider(color = dividerColor)
        Box(
            modifier = Modifier.weight(1f, fill = contentFill)
                .fillMaxWidth()
                .then(contentModifier),
            content = content,
        )
        footer?.let {
            HorizontalDivider(
                color = dividerColor,
                modifier = footerDividerTestTag?.let { Modifier.testTag(it) } ?: Modifier,
            )
            it()
        }
    }
}

/** M3-measured title row with the shared left-side close action and a caller-owned title slot. */
@Composable
internal fun ModalHeader(
    variant: ModalHeaderVariant,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    titleSpacing: Dp? = null,
    headerTestTag: String? = null,
    closeTestTag: String? = null,
    closeEnabled: Boolean = true,
    title: @Composable () -> Unit,
) {
    val closeLabel = localizedText("copy_6c14bd7f6f")
    val spacing = titleSpacing ?: when (variant) {
        ModalHeaderVariant.BasicDialog -> 16.dp
        ModalHeaderVariant.FullScreenDialog -> 8.dp
        ModalHeaderVariant.SideSheet -> 12.dp
    }
    Row(
        modifier = modifier.fillMaxWidth()
            .then(
                when (variant) {
                    ModalHeaderVariant.BasicDialog -> Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp)
                    ModalHeaderVariant.FullScreenDialog -> Modifier.height(56.dp).padding(horizontal = 24.dp)
                    ModalHeaderVariant.SideSheet -> Modifier.padding(start = 16.dp, top = 24.dp, end = 24.dp)
                },
            )
            .then(headerTestTag?.let { Modifier.testTag(it) } ?: Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onClose,
            enabled = closeEnabled,
            modifier = Modifier
                .then(closeTestTag?.let { Modifier.testTag(it) } ?: Modifier)
                .semantics { contentDescription = closeLabel },
        ) {
            Icon(painterResource(Res.drawable.ic_close), contentDescription = null, modifier = Modifier.size(24.dp))
        }
        if (spacing > 0.dp) Spacer(Modifier.width(spacing))
        Box(Modifier.weight(1f)) { title() }
    }
}

/** The M3 Side Sheet footer: 16 dp top padding, a 72 dp action row, and 24 dp bottom padding. */
@Composable
internal fun SideSheetActionArea(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .then(modifier)
            .then(testTag?.let { Modifier.testTag(it) } ?: Modifier),
    ) {
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 24.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            content()
        }
        Spacer(Modifier.height(24.dp))
    }
}
