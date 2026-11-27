package com.finance.tracker

import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.Dispatchers

internal fun cashImportMockEngine(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): MockEngine =
    MockEngine(MockEngineConfig().apply {
        dispatcher = Dispatchers.Unconfined
        addHandler(handler)
    })
