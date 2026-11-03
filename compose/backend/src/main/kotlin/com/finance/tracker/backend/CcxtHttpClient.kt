package com.finance.tracker.backend

import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

internal class CcxtHttpClient(private val delegate: HttpClient) : HttpClient() {
    private val lock = Any()
    private var stopped = false
    private val requests = mutableSetOf<CompletableFuture<*>>()
    private fun <T> track(send: () -> CompletableFuture<T>): CompletableFuture<T> = synchronized(lock) {
        check(!stopped) { "CCXT HTTP client is closed" }
        val future = send()
        requests.add(future)
        future.whenComplete { _, _ -> synchronized(lock) { requests.remove(future) } }
        future
    }
    override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> =
        track { delegate.sendAsync(request, handler) }
    override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>, push: HttpResponse.PushPromiseHandler<T>): CompletableFuture<HttpResponse<T>> =
        track { delegate.sendAsync(request, handler, push) }
    override fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
        throw UnsupportedOperationException("CCXT requires asynchronous HTTP")
    override fun shutdownNow() {
        val pending = synchronized(lock) { stopped = true; requests.toList() }
        pending.forEach { it.cancel(true) }
        delegate.shutdownNow()
    }
    override fun shutdown() = shutdownNow()
    override fun awaitTermination(duration: Duration): Boolean = delegate.awaitTermination(duration)
    override fun isTerminated(): Boolean = delegate.isTerminated
    override fun cookieHandler(): Optional<CookieHandler> = delegate.cookieHandler()
    override fun connectTimeout(): Optional<Duration> = delegate.connectTimeout()
    override fun followRedirects(): Redirect = delegate.followRedirects()
    override fun proxy(): Optional<ProxySelector> = delegate.proxy()
    override fun sslContext(): SSLContext = delegate.sslContext()
    override fun sslParameters(): SSLParameters = delegate.sslParameters()
    override fun authenticator(): Optional<Authenticator> = delegate.authenticator()
    override fun version(): Version = delegate.version()
    override fun executor(): Optional<Executor> = delegate.executor()
}
