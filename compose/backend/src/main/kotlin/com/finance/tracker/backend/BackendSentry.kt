package com.finance.tracker.backend

import io.sentry.SentryClient
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import java.net.URI
import java.util.UUID

internal class BackendSentry private constructor(private val client: SentryClient) : AutoCloseable {
    fun report(requestId: UUID?, route: String, failure: Throwable, status: Int = 500) {
        runCatching {
            val event = SentryEvent().apply {
                logger = LOGGER
                level = SentryLevel.ERROR
                setTag("route", route)
                setTag("http_status", status.toString())
                requestId?.let { setTag("request_id", it.toString()) }
                exceptions = generateSequence(failure) { it.cause }.take(4).toList().asReversed().map { cause ->
                    SentryException().apply {
                        type = cause.javaClass.simpleName.ifBlank { "Throwable" }
                        module = cause.javaClass.packageName
                        stacktrace = SentryStackTrace(cause.stackTrace.take(32).asReversed().map { frame ->
                            SentryStackFrame().apply {
                                module = frame.className
                                function = frame.methodName
                                if (frame.lineNumber > 0) lineno = frame.lineNumber
                            }
                        })
                    }
                }
            }
            client.captureEvent(event, null, null)
        }
    }

    override fun close() = client.close()

    companion object {
        private const val LOGGER = "finance-tracker.api"
        private val commit = Regex("[0-9a-f]{40}")

        fun create(dsn: String?, release: String?): BackendSentry? {
            if (dsn.isNullOrBlank()) return null
            return runCatching {
                val uri = URI(dsn)
                require(uri.host != null && uri.rawUserInfo?.matches(Regex("[A-Za-z0-9]+")) == true)
                require(uri.scheme == "https" || uri.scheme == "http" && uri.host in listOf("localhost", "127.0.0.1"))
                require(uri.query == null && uri.fragment == null)
                val build = release?.takeIf(commit::matches)
                val options = SentryOptions().apply {
                    this.dsn = dsn
                    setSendDefaultPii(false)
                    dataCollection.userInfo = false
                    isAttachStacktrace = false
                    isAttachThreads = false
                    isAttachServerName = false
                    isSendModules = false
                    isSendClientReports = false
                    isEnableAutoSessionTracking = false
                    isEnableUncaughtExceptionHandler = false
                    isEnableShutdownHook = false
                    integrations.clear()
                    eventProcessors.clear()
                    beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                        if (event.logger != LOGGER) null else SentryEvent(event.timestamp).apply {
                            eventId = event.eventId
                            logger = LOGGER
                            level = SentryLevel.ERROR
                            environment = "production"
                            this.release = build
                            tags = event.tags.orEmpty().filterKeys { it in setOf("route", "http_status", "request_id") }
                            exceptions = event.exceptions
                        }
                    }
                }
                BackendSentry(SentryClient(options))
            }.getOrElse {
                serverDiagnosticLog.warn("sentry_configuration_invalid")
                null
            }
        }
    }
}
