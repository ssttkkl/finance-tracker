package com.finance.tracker

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

interface TokenStore {
    suspend fun get(): String?
    suspend fun set(value: String)
    suspend fun clear()
}

private val apiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private object NoRequestBody

interface FileSource {
    val name: String
    val mediaType: String?
    val size: Long?
    suspend fun read(): ByteArray
}

data class ImportRequestOptions(
    val source: String = "",
    val currency: String? = null,
    val password: String? = null,
    val passwords: Map<String, String> = emptyMap(),
    val previewDigest: String? = null,
    val previewRelationDigest: String? = null,
    val previewChannel: String? = null,
    val relations: List<JsonObject>? = null,
    val mapping: List<ImportMappingDecisionDto>? = null,
    val importToken: String? = null,
    val idempotencyKey: String? = null,
    val batch: Boolean = false,
)

class FinanceApiClient(
    private val baseUrl: () -> String,
    private val tokenStore: TokenStore,
    engine: HttpClientEngine? = null,
    private val allowInsecureHttp: Boolean = false,
) {
    private val httpClient = engine?.let { injectedEngine ->
        HttpClient(injectedEngine) {
            expectSuccess = false
            install(ContentNegotiation) { json(apiJson) }
            install(SSE)
        }
    } ?: HttpClient {
        expectSuccess = false
        install(ContentNegotiation) { json(apiJson) }
        install(SSE)
    }

    fun close() = httpClient.close()

    suspend fun hasStoredSessionToken(): Boolean = readToken() != null

    suspend fun session(): SessionDto = request(HttpMethod.Get, "/api/v1/auth/session")

    suspend fun restoreSession(attempts: Int = 3, retryDelayMillis: Long = 200): SessionDto {
        if (!hasStoredSessionToken()) throw ApiFailure("authentication_required", 401)
        repeat(attempts.coerceAtLeast(1) - 1) {
            try {
                return session()
            } catch (failure: ApiFailure) {
                if (failure.status == 401 || failure.code == "authentication_required") throw failure
                delay(retryDelayMillis)
            }
        }
        return session()
    }

    suspend fun login(email: String, password: String): SessionDto = authenticate("login", email, password)

    suspend fun register(email: String, password: String): SessionDto = authenticate("register", email, password)

    suspend fun logout(): LogoutResultDto {
        return try {
            request(HttpMethod.Post, "/api/v1/auth/logout", body = null)
        } finally {
            clearToken()
        }
    }

    suspend fun selectWorkspace(id: String): SessionDto = request(
        HttpMethod.Post,
        "/api/v1/auth/workspaces/${encodePathSegment(id)}/select",
        body = null,
    )

    suspend fun createWorkspace(name: String): SessionDto = request(
        HttpMethod.Post,
        "/api/v1/auth/workspaces",
        body = WorkspaceNameRequest(name),
    )

    suspend fun invitationPreview(token: String): InvitationPreviewDto = request(
        HttpMethod.Get,
        "/api/v1/auth/invitations/${encodePathSegment(token)}",
    )

    suspend fun acceptInvitation(token: String): SessionDto = request(
        HttpMethod.Post,
        "/api/v1/auth/invitations/${encodePathSegment(token)}/accept",
        body = null,
    )

    suspend fun workspaceDetails(): WorkspaceMembersDto = request(HttpMethod.Get, "/api/v1/auth/workspace")

    suspend fun updateWorkspace(name: String): SessionDto = request(
        HttpMethod.Put,
        "/api/v1/auth/workspace",
        body = WorkspaceNameRequest(name),
    )

    suspend fun deleteWorkspace(name: String): SessionDto = request(
        HttpMethod.Delete,
        "/api/v1/auth/workspace",
        body = WorkspaceNameRequest(name),
    )

    suspend fun invite(role: Role): InviteResultDto = request(
        HttpMethod.Post,
        "/api/v1/auth/invitations",
        body = InviteRequest(role),
    )

    suspend fun members(): WorkspaceMembersDto = request(HttpMethod.Get, "/api/v1/auth/members")

    suspend fun fetchInvestmentPage(filters: InvestmentFiltersDto, cursor: String? = null): InvestmentPageDto = request(
        HttpMethod.Get,
        appendQuery("/api/v1/investment-events", listOf(
            "date_from" to filters.dateFrom,
            "date_to" to filters.dateTo,
            "account_id" to filters.accountId,
            "record_type" to filters.recordType,
            "ticker" to filters.ticker,
            "timezone" to getLocalTimeZoneId(),
            "cursor" to cursor,
        )),
    )

    suspend fun fetchInvestmentEvidence(eventId: String): InvestmentEvidenceDto = request(
        HttpMethod.Get,
        "/api/v1/evidence/investment-events/${encodePathSegment(eventId)}",
    )

    suspend fun fetchInvestmentPortfolio(
        displayCurrency: String? = null,
        period: String = "24h",
        phase: String = "valuation",
    ): PortfolioDto = request(
        HttpMethod.Get,
        appendQuery("/api/v1/investment-portfolio", listOf(
            "timezone" to getLocalTimeZoneId(),
            "display_currency" to displayCurrency,
            "period" to period,
            "phase" to phase,
        )),
    )

    suspend fun refreshInvestmentPortfolio(displayCurrency: String? = null, period: String = "24h"): OkResultDto = request(
        HttpMethod.Post,
        appendQuery("/api/v1/investment-portfolio/refresh", listOf(
            "timezone" to getLocalTimeZoneId(),
            "display_currency" to displayCurrency,
            "period" to period,
        )),
    )

    suspend fun streamInvestmentPortfolio(
        displayCurrency: String? = null,
        period: String = "24h",
        onPortfolio: (PortfolioDto) -> Unit,
        onRefreshError: () -> Unit,
    ) {
        try {
            val origin = normalizeApiOrigin(baseUrl(), allowInsecureHttp)
            val token = readToken()
            val path = appendQuery("/api/v1/investment-portfolio/stream", listOf(
                "timezone" to getLocalTimeZoneId(),
                "display_currency" to displayCurrency,
                "period" to period,
            ))
            httpClient.sse(urlString = origin + path, request = {
                header(HttpHeaders.Accept, "text/event-stream")
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            }) {
                if (call.response.status.value !in 200..299) {
                    onRefreshError()
                    return@sse
                }
                incoming.collect { event ->
                    when (event.event) {
                        "portfolio" -> {
                            val portfolio = decodeInvestmentPortfolioEvent(event.event, event.data)
                            if (portfolio == null) onRefreshError() else onPortfolio(portfolio)
                        }
                        "refresh_error" -> onRefreshError()
                    }
                }
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Throwable) {
            onRefreshError()
        }
    }

    suspend fun detectCashImport(file: FileSource, currency: String? = null, password: String? = null): ImportDetectionDto =
        importFileRequest("/api/v1/cash-import/detect", file, ImportRequestOptions(currency = currency, password = password))

    suspend fun scanCashImport(
        files: List<FileSource>,
        currency: String? = null,
        passwords: Map<String, String> = emptyMap(),
        importToken: String? = null,
    ): ImportScanDto {
        if (files.isEmpty() && importToken == null) throw ApiFailure("request_failed", 0)
        if (importToken != null) {
            return importJsonRequest("/api/v1/cash-import/scan", ImportRequestOptions(
                currency = currency,
                passwords = passwords,
                importToken = importToken,
                batch = files.size != 1,
            ))
        }
        val payload = ImportBatchUploadDto(
            files = files.map { file -> ImportFileUploadDto(file.name, base64(file.read())) },
            currency = currency,
        )
        return importJsonRequest("/api/v1/cash-import/scan", ImportRequestOptions(passwords = passwords), payload)
    }

    suspend fun previewCashImport(
        importToken: String,
        source: String = "",
        currency: String? = null,
        passwords: Map<String, String> = emptyMap(),
        mapping: List<ImportMappingDecisionDto>? = null,
        batch: Boolean = false,
    ): ImportPreviewDto = importJsonRequest(
        "/api/v1/cash-import/preview",
        ImportRequestOptions(source = source, currency = currency, passwords = passwords, mapping = mapping, importToken = importToken, batch = batch),
    )

    suspend fun commitCashImport(
        importToken: String,
        source: String = "",
        currency: String? = null,
        passwords: Map<String, String> = emptyMap(),
        previewDigest: String? = null,
        previewRelationDigest: String? = null,
        previewChannel: String? = null,
        relations: List<JsonObject>? = null,
        mapping: List<ImportMappingDecisionDto>? = null,
        idempotencyKey: String? = null,
        batch: Boolean = false,
    ): ImportCommitResultDto = importJsonRequest(
        "/api/v1/cash-import/commit",
        ImportRequestOptions(
            source = source,
            currency = currency,
            passwords = passwords,
            previewDigest = previewDigest,
            previewRelationDigest = previewRelationDigest,
            previewChannel = previewChannel,
            relations = relations,
            mapping = mapping,
            importToken = importToken,
            idempotencyKey = idempotencyKey,
            batch = batch,
        ),
    )

    private suspend fun importFileRequest(path: String, file: FileSource, options: ImportRequestOptions): ImportDetectionDto =
        importRawRequest(path, file, options)

    suspend fun fetchCashPage(filters: CashFiltersDto, cursor: String? = null): CashPageDto = request(
        HttpMethod.Get,
        appendQuery("/api/v1/cash-projections", listOf(
            "date_from" to filters.dateFrom,
            "date_to" to filters.dateTo,
            "account_id" to filters.accountId,
            "counterparty" to filters.counterparty,
            "category_id" to filters.categoryId,
            "uncategorized" to filters.uncategorized,
            "currency" to filters.currency,
            "amount_min" to filters.amountMin,
            "amount_max" to filters.amountMax,
            "economic_type" to filters.economicType,
            "transfer_subtype" to filters.transferSubtype,
            "composition" to filters.composition,
            "timezone" to getLocalTimeZoneId(),
            "cursor" to cursor,
        )),
    )

    suspend fun fetchCashAccounts(): List<AccountDto> =
        request<AccountListDto>(HttpMethod.Get, "/api/v1/accounts?view=cash").items

    suspend fun fetchInvestmentAccounts(): List<AccountDto> =
        request<AccountListDto>(HttpMethod.Get, "/api/v1/accounts?view=investment").items

    suspend fun fetchEvidence(id: String): EvidenceDto = request(
        HttpMethod.Get,
        "/api/v1/evidence/cash-projections/${encodePathSegment(id)}",
    )

    suspend fun fetchLedgerOptions(): LedgerOptionsDto = request(HttpMethod.Get, "/api/v1/cash-ledger/options")

    suspend fun fetchCashCategories(): CashCategoryDirectoryDto = request(HttpMethod.Get, "/api/v1/cash-categories")

    suspend fun createCashCategory(name: String, parentId: String?, description: String, expectedRevision: Long): CashCategoryDto =
        request(HttpMethod.Post, "/api/v1/cash-categories", body = categoryWriteBody(name, parentId, description, expectedRevision))

    suspend fun updateCashCategory(id: String, name: String, parentId: String?, description: String, expectedRevision: Long): CashCategoryDto =
        request(HttpMethod.Patch, "/api/v1/cash-categories/${encodePathSegment(id)}", body = categoryWriteBody(name, parentId, description, expectedRevision))

    suspend fun reorderCashCategory(id: String, direction: String, expectedRevision: Long): CashCategoryDto = request(
        HttpMethod.Post,
        "/api/v1/cash-categories/${encodePathSegment(id)}/reorder",
        body = buildJsonObject {
            put("direction", direction)
            put("expected_revision", expectedRevision)
        },
    )

    suspend fun fetchCashCategoryDeletionImpact(id: String): CashCategoryDeleteImpactDto = request(
        HttpMethod.Get,
        "/api/v1/cash-categories/${encodePathSegment(id)}/deletion-impact",
    )

    suspend fun deleteCashCategory(id: String, impact: CashCategoryDeleteImpactDto): CashCategoryDeleteResultDto = request(
        HttpMethod.Delete,
        "/api/v1/cash-categories/${encodePathSegment(id)}",
        body = buildJsonObject {
            put("expected_revision", impact.revision)
            put("expected_category_revision", impact.categoryRevision)
            put("expected_usage_count", impact.directUsageCount)
            put("confirmed", true)
        },
    )

    suspend fun classifyCashProjections(ids: List<String>, projectionVersion: Long, categoryId: String?): CashClassificationResultDto = request(
        HttpMethod.Put,
        "/api/v1/cash-projections/categories",
        body = buildJsonObject {
            put("projection_ids", apiJson.encodeToJsonElement(ids))
            put("projection_version", projectionVersion)
            put("category_id", categoryId?.let(::JsonPrimitive) ?: JsonNull)
        },
    )

    suspend fun fetchCashProjectionDeleteImpact(ids: List<String>, projectionVersion: Long): CashProjectionDeleteImpactDto = request(
        HttpMethod.Post,
        "/api/v1/cash-projections/delete-impact",
        body = buildJsonObject {
            put("projection_ids", apiJson.encodeToJsonElement(ids))
            put("projection_version", projectionVersion)
        },
    )

    suspend fun deleteCashProjections(ids: List<String>, projectionVersion: Long): CashProjectionDeleteResultDto = request(
        HttpMethod.Delete,
        "/api/v1/cash-projections",
        body = buildJsonObject {
            put("projection_ids", apiJson.encodeToJsonElement(ids))
            put("projection_version", projectionVersion)
            put("confirmed", true)
        },
    )

    suspend fun fetchCashRecord(id: String): CashRecordDetailDto = request(
        HttpMethod.Get,
        "/api/v1/cash-records/${encodePathSegment(id)}",
    )

    suspend fun fetchCashRecords(
        query: String? = null,
        excludeId: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        cursor: String? = null,
        limit: Int = 20,
    ): CashRecordPageDto = request(
        HttpMethod.Get,
        appendQuery("/api/v1/cash-records", listOf(
            "query" to query,
            "exclude_id" to excludeId,
            "date_from" to dateFrom,
            "date_to" to dateTo,
            "timezone" to getLocalTimeZoneId(),
            "cursor" to cursor,
            "limit" to limit.toString(),
        )),
    )

    suspend fun createCashRecord(body: JsonObject): CashRecordDetailDto = request(HttpMethod.Post, "/api/v1/cash-records", body)

    suspend fun updateCashRecord(id: String, body: JsonObject): CashRecordDetailDto = request(
        HttpMethod.Put,
        "/api/v1/cash-records/${encodePathSegment(id)}",
        body,
    )

    suspend fun deleteCashRecord(id: String, mode: String): CashRecordDeleteResultDto = request(
        HttpMethod.Delete,
        "/api/v1/cash-records/${encodePathSegment(id)}",
        body = buildJsonObject { put("mode", mode) },
    )

    suspend fun createCashRelation(primaryFactId: String, secondaryFactId: String, kind: String): CashRecordDetailDto = request(
        HttpMethod.Post,
        "/api/v1/cash-relations",
        body = buildJsonObject {
            put("primary_fact_id", primaryFactId)
            put("secondary_fact_id", secondaryFactId)
            put("kind", kind)
            put("status", "accepted")
        },
    )

    suspend fun updateCashRelation(id: String, kind: String): CashRecordDetailDto = request(
        HttpMethod.Put,
        "/api/v1/cash-relations/${encodePathSegment(id)}",
        body = buildJsonObject { put("kind", kind) },
    )

    suspend fun cancelCashRelation(id: String): OkResultDto = request(
        HttpMethod.Delete,
        "/api/v1/cash-relations/${encodePathSegment(id)}",
        body = buildJsonObject {},
    )

    suspend fun dissolveCashRelations(factId: String): CashRecordDetailDto = request(
        HttpMethod.Post,
        "/api/v1/cash-relations/dissolve",
        body = buildJsonObject { put("fact_id", factId) },
    )

    suspend fun updateMember(id: String, role: Role): EmptyResultDto = request(
        HttpMethod.Put,
        "/api/v1/auth/members/${encodePathSegment(id)}",
        body = UpdateMemberRequest(role),
    )

    suspend fun removeMember(id: String): OkResultDto = request(
        HttpMethod.Delete,
        "/api/v1/auth/members/${encodePathSegment(id)}",
    )

    private suspend fun authenticate(action: String, email: String, password: String): SessionDto {
        val response: AuthResponseDto = request(
            HttpMethod.Post,
            "/api/v1/auth/$action",
            body = CredentialsRequest(email.trim(), password),
        )
        try {
            tokenStore.set(response.accessToken)
        } catch (_: Throwable) {
            throw ApiFailure("token_store_unavailable", 0)
        }
        return response.session()
    }

    private suspend inline fun <reified T> request(
        method: HttpMethod,
        path: String,
        body: Any? = NoRequestBody,
        extraHeaders: Map<String, String> = emptyMap(),
        bodyContentType: ContentType? = null,
    ): T {
        val origin = normalizeApiOrigin(baseUrl(), allowInsecureHttp)
        val token = readToken()
        val response = httpClient.request(origin + path) {
            this.method = method
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            extraHeaders.forEach { (name, value) -> header(name, value) }
            if (body !== NoRequestBody) {
                val requestContentType = bodyContentType ?: ContentType.Application.Json
                contentType(requestContentType)
                if (body is JsonElement && requestContentType == ContentType.Application.Json) {
                    setBody(TextContent(body.toString(), ContentType.Application.Json))
                } else {
                    setBody(body)
                }
            }
        }
        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val code = apiErrorCode(response.status.value, responseText) ?: "request_failed"
            if (response.status == HttpStatusCode.Unauthorized) clearToken()
            val importToken = runCatching {
                (apiJson.parseToJsonElement(responseText) as? JsonObject)?.get("import_token")?.let { (it as? JsonPrimitive)?.content }
            }.getOrNull()
            throw ApiFailure(code, response.status.value, importToken)
        }
        if (response.status == HttpStatusCode.NoContent || responseText.isBlank()) {
            @Suppress("UNCHECKED_CAST")
            return EmptyResultDto() as T
        }
        return try {
            apiJson.decodeFromString<T>(responseText)
        } catch (_: Throwable) {
            throw ApiFailure("request_failed", response.status.value)
        }
    }

    private suspend inline fun <reified T> importJsonRequest(
        path: String,
        options: ImportRequestOptions,
        payload: Any? = importPayload(options),
    ): T {
        val headers = buildMap {
            if (options.password != null) put("X-FT-Statement-Password", options.password)
            if (options.passwords.isNotEmpty()) put("X-FT-Statement-Passwords", apiJson.encodeToString(options.passwords))
            if (options.idempotencyKey != null) put("Idempotency-Key", options.idempotencyKey)
        }
        return request(HttpMethod.Post, path, payload, extraHeaders = headers)
    }

    private suspend inline fun <reified T> importRawRequest(
        path: String,
        file: FileSource,
        options: ImportRequestOptions,
    ): T {
        val query = appendQuery(path, listOf(
            "source" to options.source,
            "filename" to file.name,
            "currency" to options.currency,
            "preview_digest" to options.previewDigest,
            "preview_relation_digest" to options.previewRelationDigest,
            "preview_channel" to options.previewChannel,
            "relations" to options.relations?.let(apiJson::encodeToString),
            "mapping" to options.mapping?.let(apiJson::encodeToString),
        ))
        val headers = buildMap {
            if (options.password != null) put("X-FT-Statement-Password", options.password)
            if (options.passwords.isNotEmpty()) put("X-FT-Statement-Passwords", apiJson.encodeToString(options.passwords))
            put(HttpHeaders.ContentType, "application/octet-stream")
            if (options.idempotencyKey != null) put("Idempotency-Key", options.idempotencyKey)
        }
        return request(HttpMethod.Post, query, file.read(), headers, ContentType.Application.OctetStream)
    }

    private suspend fun readToken(): String? = try {
        tokenStore.get()?.takeIf(String::isNotEmpty)
    } catch (_: Throwable) {
        throw ApiFailure("token_store_unavailable", 0)
    }

    private suspend fun clearToken() {
        try {
            tokenStore.clear()
        } catch (_: Throwable) {
            throw ApiFailure("token_store_unavailable", 0)
        }
    }
}

@Serializable
private data class InvestmentPortfolioStreamDto(val version: Long? = null, val portfolio: PortfolioDto? = null)

internal fun decodeInvestmentPortfolioEvent(event: String?, data: String?): PortfolioDto? {
    if (event != "portfolio" || data.isNullOrBlank()) return null
    return runCatching { apiJson.decodeFromString<InvestmentPortfolioStreamDto>(data).portfolio }.getOrNull()
}

@Serializable
data class CredentialsRequest(val email: String, val password: String)

@Serializable
data class WorkspaceNameRequest(val name: String)

@Serializable
data class InviteRequest(val role: Role)

@Serializable
data class UpdateMemberRequest(val role: Role)

@Serializable
data class InviteResultDto(val token: String)

@Serializable
data class OkResultDto(val ok: Boolean)

@Serializable
data class LogoutResultDto(val ok: Boolean)

@Serializable
data class EmptyResultDto(val unused: Boolean = true)

private val HTTPS_ORIGIN = Regex("^https://(?:\\[[0-9A-Fa-f:]+\\]|[A-Za-z0-9.-]+)(?::([0-9]{1,5}))?$")
private val HTTP_ORIGIN = Regex("^http://(\\[[0-9A-Fa-f:]+\\]|[A-Za-z0-9.-]+):([0-9]{1,5})$")

private fun normalizeApiOrigin(value: String, allowInsecureHttp: Boolean): String {
    val candidate = value.trim().removeSuffix("/")
    val secureMatch = HTTPS_ORIGIN.matchEntire(candidate)
    if (secureMatch != null) {
        val port = secureMatch.groupValues.getOrNull(1)?.toIntOrNull()
        if (port != null && port !in 1..65535) throw ApiFailure("api_origin_invalid", 0)
        return candidate
    }
    val insecureMatch = HTTP_ORIGIN.matchEntire(candidate) ?: throw ApiFailure("api_origin_invalid", 0)
    val host = insecureMatch.groupValues[1].removePrefix("[").removeSuffix("]").lowercase()
    val port = insecureMatch.groupValues[2].toIntOrNull()
    val localDevelopmentHost = host == "localhost" || host == "127.0.0.1" || host == "::1"
    if (port !in 1..65535 || (!allowInsecureHttp && !localDevelopmentHost)) {
        throw ApiFailure("api_origin_invalid", 0)
    }
    return candidate
}

private fun encodePathSegment(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xff
        val character = unsigned.toChar()
        if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "-_.~") {
            append(character)
        } else {
            append('%')
            append("0123456789ABCDEF"[unsigned shr 4])
            append("0123456789ABCDEF"[unsigned and 15])
        }
    }
}

private fun appendQuery(path: String, values: List<Pair<String, String?>>): String {
    val query = values.asSequence()
        .filter { (_, value) -> value != null && value.isNotEmpty() }
        .joinToString("&") { (key, value) -> "${encodeQueryComponent(key)}=${encodeQueryComponent(value.orEmpty())}" }
    if (query.isEmpty()) return path
    return path + if ('?' in path) "&$query" else "?$query"
}

private fun encodeQueryComponent(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xff
        val character = unsigned.toChar()
        if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "-_.~") {
            append(character)
        } else {
            append('%')
            append("0123456789ABCDEF"[unsigned shr 4])
            append("0123456789ABCDEF"[unsigned and 15])
        }
    }
}

private fun categoryWriteBody(name: String, parentId: String?, description: String, expectedRevision: Long) = buildJsonObject {
    put("name", name)
    put("description", description)
    put("parent_id", parentId?.let(::JsonPrimitive) ?: JsonNull)
    put("expected_revision", expectedRevision)
}

private fun importPayload(options: ImportRequestOptions) = buildJsonObject {
    if (options.importToken != null) put("import_token", options.importToken)
    if (options.batch) put("batch", true)
    put("source", options.source)
    put("currency", options.currency?.let(::JsonPrimitive) ?: JsonNull)
    put("preview_digest", options.previewDigest?.let(::JsonPrimitive) ?: JsonNull)
    if (options.previewRelationDigest != null) put("preview_relation_digest", options.previewRelationDigest)
    put("preview_channel", options.previewChannel?.let(::JsonPrimitive) ?: JsonNull)
    if (options.relations != null) put("relations", apiJson.encodeToJsonElement(options.relations))
    if (options.mapping != null) put("mapping", apiJson.encodeToJsonElement(options.mapping))
}

private fun base64(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    return buildString(((bytes.size + 2) / 3) * 4) {
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xff
            val second = bytes.getOrNull(index + 1)?.toInt()?.and(0xff)
            val third = bytes.getOrNull(index + 2)?.toInt()?.and(0xff)
            append(alphabet[first shr 2])
            append(alphabet[((first and 0x03) shl 4) or ((second ?: 0) shr 4)])
            append(if (second == null) '=' else alphabet[((second and 0x0f) shl 2) or ((third ?: 0) shr 6)])
            append(if (third == null) '=' else alphabet[third and 0x3f])
            index += 3
        }
    }
}
