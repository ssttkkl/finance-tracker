package com.finance.tracker.data

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

suspend fun FinanceApiClient.fetchInvestmentPage(filters: InvestmentFiltersDto, cursor: String? = null): InvestmentPageDto = request(
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

suspend fun FinanceApiClient.fetchInvestmentEvidence(eventId: String): InvestmentEvidenceDto = request(
    HttpMethod.Get,
    "/api/v1/evidence/investment-events/${encodePathSegment(eventId)}",
)

suspend fun FinanceApiClient.fetchInvestmentPortfolio(
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

suspend fun FinanceApiClient.refreshInvestmentPortfolio(displayCurrency: String? = null, period: String = "24h"): OkResultDto = request(
    HttpMethod.Post,
    appendQuery("/api/v1/investment-portfolio/refresh", listOf(
        "timezone" to getLocalTimeZoneId(),
        "display_currency" to displayCurrency,
        "period" to period,
    )),
)

suspend fun FinanceApiClient.streamInvestmentPortfolio(
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

suspend fun FinanceApiClient.fetchInvestmentAccounts(): List<AccountDto> =
    request<AccountListDto>(HttpMethod.Get, "/api/v1/accounts?view=investment").items


@Serializable
private data class InvestmentPortfolioStreamDto(val version: Long? = null, val portfolio: PortfolioDto? = null)

internal fun decodeInvestmentPortfolioEvent(event: String?, data: String?): PortfolioDto? {
    if (event != "portfolio" || data.isNullOrBlank()) return null
    return runCatching { apiJson.decodeFromString<InvestmentPortfolioStreamDto>(data).portfolio }.getOrNull()
}
