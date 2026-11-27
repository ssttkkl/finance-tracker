package com.finance.tracker.backend

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopPreparing
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun Application.installCashImportConfirmationWorker(service: CashImportConfirmationService) {
    val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "cash-import-confirmation").apply { isDaemon = true }
    }
    monitor.subscribe(ApplicationStarted) {
        worker.scheduleWithFixedDelay({
            try {
                service.workOnce()
            } catch (failure: StorageFailure) {
                if (failure.code != "storage.busy") reportCashImportWorkerFailure(failure)
            } catch (failure: Exception) {
                reportCashImportWorkerFailure(failure)
            }
        }, 0, 200, TimeUnit.MILLISECONDS)
    }
    monitor.subscribe(ApplicationStopPreparing) { worker.shutdown() }
    monitor.subscribe(ApplicationStopped) {
        worker.shutdown()
        worker.awaitTermination(2, TimeUnit.SECONDS)
    }
}

private fun reportCashImportWorkerFailure(failure: Exception) {
    val frames = failure.stackTrace.map { "${it.fileName}:${it.lineNumber}:${it.methodName}" }
    serverDiagnosticLog.error("cash_import_worker_failure type={} frames={}", failure.javaClass.simpleName, frames)
}
