package com.finance.tracker.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiRendererConfig
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.compose.ComponentRegistry
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.protocol.ComponentDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class LedgerAssistantCardKind(val componentName: String) {
    QUERY("LedgerQuery"),
    DRAFT("LedgerDraft"),
    RESULT("LedgerResult"),
}

private const val ledgerAssistantCatalogId = "https://finance-tracker.local/a2ui/ledger-assistant/1"
private val ledgerAssistantCatalog = CatalogDefinition(
    catalogId = ledgerAssistantCatalogId,
    protocolVersion = "1.0",
    components = LedgerAssistantCardKind.entries.associate { kind ->
        kind.componentName to ComponentDefinition(buildJsonObject { put("type", "object") })
    },
)

@Composable
internal fun LedgerAssistantCard(
    kind: LedgerAssistantCardKind,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val current = rememberUpdatedState(content)
    val registry = remember(kind) {
        ComponentRegistry(mapOf(kind.componentName to ComponentRenderer { _, surfaceModifier ->
            OutlinedCard(surfaceModifier.fillMaxWidth()) { current.value() }
        }))
    }
    val renderer = remember(kind) {
        val component = buildJsonObject {
            put("id", "root")
            put("component", kind.componentName)
        }
        val create = buildJsonObject {
            put("version", "v1.0")
            put("createSurface", buildJsonObject {
                put("surfaceId", kind.componentName)
                put("catalogId", ledgerAssistantCatalogId)
            })
        }
        val update = buildJsonObject {
            put("version", "v1.0")
            put("updateComponents", buildJsonObject {
                put("surfaceId", kind.componentName)
                put("components", JsonArray(listOf(component)))
            })
        }
        val config = A2uiRendererConfig.Default.withCatalogs(listOf(ledgerAssistantCatalog))
        A2uiRenderer(config).apply {
            applyAll(listOf(create, update).map {
                A2uiJson.strict.decodeFromJsonElement(AgentToRendererMessage.serializer(), it)
            })
        }
    }
    A2uiSurface(renderer, kind.componentName, registry, modifier = modifier.fillMaxWidth().testTag("assistant.business-card"))
}
