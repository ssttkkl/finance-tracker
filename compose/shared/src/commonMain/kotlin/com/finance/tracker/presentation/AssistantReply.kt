package com.finance.tracker.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.serviceLoaderEnabled
import coil3.svg.SvgDecoder
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.MarkdownA11yLabels
import com.mikepenz.markdown.model.markdownExtendedSpans
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import io.ktor.client.HttpClient
import io.ktor.http.URLProtocol
import io.ktor.http.Url

internal fun assistantMarkdownWebUrl(value: String, httpsOnly: Boolean): Boolean {
    if (value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return false
    val authority = value.substringAfter("://", "").takeWhile { it !in "/?#" }
    if (authority.isEmpty()) return false
    val scheme = value.substringBefore("://").lowercase()
    if (scheme != "https" && (httpsOnly || scheme != "http")) return false
    val url = runCatching { Url(value) }.getOrNull() ?: return false
    return url.host.isNotBlank() && url.user == null && url.password == null &&
        (url.protocol == URLProtocol.HTTPS || (!httpsOnly && url.protocol == URLProtocol.HTTP))
}

@Composable
internal fun rememberAssistantMarkdownImages(): ImageTransformer {
    val context = LocalPlatformContext.current
    val client = remember { HttpClient { followRedirects = false } }
    val loader = remember(context, client) {
        ImageLoader.Builder(context)
            .serviceLoaderEnabled(false)
            .diskCache(null)
            .components {
                add(KtorNetworkFetcherFactory(httpClient = client))
                add(SvgDecoder.Factory())
            }
            .build()
    }
    DisposableEffect(loader, client) {
        onDispose {
            loader.shutdown()
            client.close()
        }
    }
    return remember(context, loader) {
        object : ImageTransformer by Coil3ImageTransformerImpl {
            @Composable
            override fun transform(link: String): ImageData? {
                if (!assistantMarkdownWebUrl(link, httpsOnly = true)) return null
                val request = remember(link) {
                    ImageRequest.Builder(context).data(link).size(1200).build()
                }
                return ImageData(rememberAsyncImagePainter(model = request, imageLoader = loader))
            }
        }
    }
}

@Composable
internal fun AssistantReply(text: String, images: ImageTransformer) {
    val type = MaterialTheme.typography
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val code = localizedText("assistant_markdown_code")
    val language = localizedText("assistant_markdown_language")
    val accessibility = MarkdownA11yLabels(
        blockquote = localizedText("assistant_markdown_quote"),
        codeBlock = code,
        codeBlockWithLanguage = { "$code · $it" },
        codeLanguage = { "$language · $it" },
        codeFallbackLanguage = code,
        copyCode = localizedText("assistant_markdown_copy"),
        alert = { it },
    )
    val defaultUriHandler = LocalUriHandler.current
    val links = remember(defaultUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val email = uri.startsWith("mailto:", ignoreCase = true) &&
                    '@' in uri.substringBefore('?') && uri.none { it.isISOControl() }
                if (assistantMarkdownWebUrl(uri, httpsOnly = false) || email) {
                    runCatching { defaultUriHandler.openUri(uri) }
                }
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides links, LocalMarkdownA11yLabels provides accessibility) {
        Markdown(
            content = text,
            modifier = Modifier.fillMaxWidth().testTag("assistant.reply"),
            typography = markdownTypography(
                h1 = type.headlineSmall,
                h2 = type.titleLarge,
                h3 = type.titleMedium,
                h4 = type.titleMedium,
                h5 = type.titleSmall,
                h6 = type.titleSmall,
                table = type.bodyMedium,
            ),
            imageTransformer = images,
            extendedSpans = markdownExtendedSpans { remember { ExtendedSpans(RoundedCornerSpanPainter()) } },
            components = markdownComponents(
                checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
                codeBlock = { model ->
                    key(dark) {
                        MarkdownHighlightedCodeBlock(
                            model.content, model.node, style = model.typography.code,
                            highlightsBuilder = remember(dark) { Highlights.Builder().theme(SyntaxThemes.atom(darkMode = dark)) },
                            showHeader = true,
                        )
                    }
                },
                codeFence = { model ->
                    key(dark) {
                        MarkdownHighlightedCodeFence(
                            model.content, model.node, style = model.typography.code,
                            highlightsBuilder = remember(dark) { Highlights.Builder().theme(SyntaxThemes.atom(darkMode = dark)) },
                            showHeader = true,
                        )
                    }
                },
            ),
            error = { modifier -> Text(text, modifier, style = type.bodyLarge) },
        )
    }
}
