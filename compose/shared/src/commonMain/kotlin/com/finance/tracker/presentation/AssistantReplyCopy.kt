package com.finance.tracker.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.client.LedgerAssistantDeliveryStatus
import com.finance.tracker.core.getLocalDateForInput
import com.finance.tracker.core.getLocalDateForInstant
import com.finance.tracker.data.LedgerAssistantMessage
import com.finance.tracker.data.LedgerAssistantToolStatus
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_content_copy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import kotlin.time.Clock

internal data class AssistantReplyCopy(val markdown: String, val time: String?)

internal fun assistantReplyCopies(
    rows: List<List<LedgerAssistantMessage>>,
    latestPending: Boolean,
): Map<Int, AssistantReplyCopy> = buildMap {
    var input: LedgerAssistantMessage? = null
    var waiting = false
    val fragments = mutableListOf<String>()
    fun finish(index: Int) {
        if (input?.delivery?.status == LedgerAssistantDeliveryStatus.DELIVERED && !waiting && fragments.isNotEmpty()) {
            put(index, AssistantReplyCopy(fragments.joinToString("\n\n"), input?.replyStartedAt))
        }
    }
    rows.forEachIndexed { index, row ->
        val first = row.firstOrNull()
        if (first?.delivery != null) {
            finish(index - 1)
            input = first
            waiting = false
            fragments.clear()
        }
        row.forEach { message ->
            message.reply?.takeIf(String::isNotBlank)?.let(fragments::add)
            when (message.toolTrace?.status) {
                LedgerAssistantToolStatus.WAITING, LedgerAssistantToolStatus.SUBMITTING, LedgerAssistantToolStatus.RUNNING -> waiting = true
                else -> Unit
            }
        }
    }
    if (!latestPending) finish(rows.lastIndex)
}

internal enum class AssistantReplyDay { TODAY, YESTERDAY, THIS_YEAR, OTHER_YEAR }

internal fun assistantReplyDay(date: String, today: String): AssistantReplyDay = when {
    date == today -> AssistantReplyDay.TODAY
    checkNotNull(isoDateToUtcMillis(today)) - checkNotNull(isoDateToUtcMillis(date)) == 86_400_000L -> AssistantReplyDay.YESTERDAY
    date.substringBefore('-') == today.substringBefore('-') -> AssistantReplyDay.THIS_YEAR
    else -> AssistantReplyDay.OTHER_YEAR
}

@Composable
internal fun rememberAssistantToday(): String {
    val focused = LocalWindowInfo.current.isWindowFocused
    val visible = rememberPageVisible()
    var today by remember { mutableStateOf(getLocalDateForInput()) }
    LaunchedEffect(focused, visible) {
        while (true) {
            today = getLocalDateForInput()
            delay(60_000L - Clock.System.now().toEpochMilliseconds().mod(60_000L))
        }
    }
    return today
}

@Composable
private fun assistantReplyTime(time: String?, today: String): String? {
    val parts = localDateTimeDisplayParts(time) ?: return null
    val date = getLocalDateForInstant(checkNotNull(time))
    val hourMinute = parts.time.removePrefix("0")
    return when (assistantReplyDay(date, today)) {
        AssistantReplyDay.TODAY -> hourMinute
        AssistantReplyDay.YESTERDAY -> localizedText("assistant_reply_yesterday", hourMinute)
        AssistantReplyDay.THIS_YEAR -> localizedText("assistant_reply_this_year", parts.date.month, parts.date.day, hourMinute)
        AssistantReplyDay.OTHER_YEAR -> localizedText("display_date_time_full", parts.date.year, parts.date.month, parts.date.day, hourMinute)
    }
}

@Composable
internal fun AssistantReplyActions(
    copy: AssistantReplyCopy?,
    today: String,
    onCopyResult: (Boolean) -> Unit,
    retry: (@Composable () -> Unit)? = null,
) {
    val clipboard = rememberAssistantReplyClipboard()
    val scope = rememberCoroutineScope()
    var busy by remember(copy?.markdown) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.testTag("assistant.reply-actions"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        retry?.invoke()
        if (copy != null) {
            IconButton(
                enabled = !busy,
                modifier = Modifier.testTag("assistant.reply-copy"),
                onClick = {
                    busy = true
                    scope.launch {
                        val copied = try {
                            clipboard(copy.markdown)
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        } finally {
                            busy = false
                        }
                        onCopyResult(copied)
                    }
                },
            ) {
                Icon(painterResource(Res.drawable.ic_content_copy), localizedText("assistant_reply_copy"),
                    tint = colors.onSurfaceVariant)
            }
            assistantReplyTime(copy.time, today)?.let {
                Text(it, Modifier.testTag("assistant.reply-time"), color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
internal expect fun rememberAssistantReplyClipboard(): suspend (String) -> Unit
