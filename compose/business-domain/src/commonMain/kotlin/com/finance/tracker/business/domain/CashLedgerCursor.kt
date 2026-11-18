package com.finance.tracker.business.domain

import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.json.*

/** Descending keyset position; an unknown occurrence date still uses the projection's real sortAt. */
data class CashLedgerCursorPosition(val sortAt: Instant, val projectionId: String) {
    fun isAfter(sortAt: Instant, projectionId: String): Boolean =
        sortAt < this.sortAt || sortAt == this.sortAt && pythonStringOrder(projectionId, this.projectionId) < 0
}

/** One derived read scope. This binds pagination, without granting access to a workspace. */
class CashLedgerCursorScope(
    private val workspaceId: String,
    private val ledgerSeq: Long,
    filters: Map<String, String?>,
    private val rulesVersion: String = CASH_RULES_VERSION,
    private val projectionRulesVersion: String = CASH_PROJECTION_RULES_VERSION,
) {
    private val filters = JsonObject(filters.mapValues { (_, value) -> value?.let(::JsonPrimitive) ?: JsonNull })
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    init {
        require(workspaceId.isNotEmpty() && ledgerSeq >= 0 && rulesVersion.isNotEmpty() && projectionRulesVersion.isNotEmpty())
    }

    fun encode(sortAt: Instant, projectionId: String): String {
        require(projectionId.isNotEmpty())
        val value = buildJsonObject {
            put("v", 1)
            put("workspace", workspaceId)
            put("ledger_seq", ledgerSeq.toString())
            put("rules_version", rulesVersion)
            put("projection_rules_version", projectionRulesVersion)
            put("filters", filters)
            put("sort_at", sortAt.toString())
            put("projection_id", projectionId)
        }
        return base64.encode(value.toString().encodeToByteArray(throwOnInvalidSequence = true))
    }

    fun decode(cursor: String): CashLedgerCursorPosition {
        fun invalid(): Nothing = throw CashProjectionDomainFailure("invalid_cursor")
        val value: JsonObject
        val position: CashLedgerCursorPosition
        val version: Long
        val rules: String
        val projectionRules: String
        try {
            val bytes = base64.decode(cursor)
            value = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: invalid()
            if (value.keys != setOf("v", "workspace", "ledger_seq", "rules_version", "projection_rules_version", "filters", "sort_at", "projection_id")) invalid()
            fun text(name: String): String = (value[name] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content ?: invalid()
            if (value["v"] != JsonPrimitive(1) || text("workspace") != workspaceId || value["filters"] != filters) invalid()
            val versionText = text("ledger_seq")
            version = versionText.toLongOrNull()?.takeIf { it >= 0 && it.toString() == versionText } ?: invalid()
            rules = text("rules_version").takeIf(String::isNotEmpty) ?: invalid()
            projectionRules = text("projection_rules_version").takeIf(String::isNotEmpty) ?: invalid()
            position = CashLedgerCursorPosition(Instant.parse(text("sort_at")), text("projection_id").takeIf(String::isNotEmpty) ?: invalid())
        } catch (_: IllegalArgumentException) {
            invalid()
        } catch (_: CharacterCodingException) {
            invalid()
        }
        if (version != ledgerSeq || rules != rulesVersion || projectionRules != projectionRulesVersion) {
            throw CashProjectionDomainFailure("read_snapshot_changed")
        }
        return position
    }
}
