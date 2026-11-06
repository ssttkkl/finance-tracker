package com.finance.tracker.data

import com.finance.tracker.business.contracts.SessionDto
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/** Only the captured session request may select offline recovery; the caller retains its cache owner. */
internal suspend fun FinanceApiClient.restoreSessionWithOfflineFallback(
    credentials: ApiCredentialSnapshot,
    readOffline: suspend () -> SessionDto?,
): SessionDto {
    if (credentials.token == null) throw ApiFailure("authentication_required", 401)
    val networkFailure = try {
        return session(credentials, preserveHttpRejectionOnBodyFailure = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        if (failure !is IOException && failure !is HttpRequestTimeoutException) throw failure
        failure
    }
    try {
        return readOffline() ?: throw networkFailure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        if (failure !== networkFailure) failure.addSuppressed(networkFailure)
        throw failure
    }
}
