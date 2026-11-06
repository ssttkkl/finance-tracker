@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import kotlin.js.JsAny
import kotlin.js.Promise

/** Kept by exactly one client; original rejected objects remain weakly held and unmodified. */
internal class BrowserFetchRejections {
    private val rejected = newRejectionSet()
    fun <T : JsAny> track(promise: Promise<T>): Promise<T> = trackRejection(rejected, promise)
    fun contains(value: JsAny): Boolean = containsRejection(rejected, value)
    fun containsDirectCause(value: JsAny): Boolean = containsRejectedCause(rejected, value)
}

private fun newRejectionSet(): JsAny = js("new WeakSet()")
private fun <T : JsAny> trackRejection(rejected: JsAny, promise: Promise<T>): Promise<T> = js("""
    promise.catch(error => {
        if (error !== null && (typeof error === 'object' || typeof error === 'function')) rejected.add(error);
        throw error;
    })
""")
private fun containsRejection(rejected: JsAny, value: JsAny): Boolean = js("rejected.has(value)")
private fun containsRejectedCause(rejected: JsAny, value: JsAny): Boolean = js("rejected.has(value.cause)")
