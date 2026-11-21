package com.finance.tracker.data

internal data class LargeBrowserJson(val status: Int, val text: String)

internal expect suspend fun fetchLargeBrowserJson(url: String, method: String, headers: Map<String, String>,
    body: String?, compress: Boolean, beforeSend: suspend () -> Unit, onStatus: (Int) -> Unit): LargeBrowserJson?
