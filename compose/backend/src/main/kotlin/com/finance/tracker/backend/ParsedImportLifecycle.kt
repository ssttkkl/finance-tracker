package com.finance.tracker.backend

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped

internal fun Application.installParsedImportRuntime(runtime: ParsedImportRuntime) {
    monitor.subscribe(ApplicationStarted) {
        try { runtime.cleanup.start() }
        catch (failure: Exception) {
            try { runtime.close() } catch (_: Exception) { /* Preserve the sanitized startup failure. */ }
            throw failure
        }
    }
    monitor.subscribe(ApplicationStopped) { runtime.close() }
}
