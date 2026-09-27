package com.finance.tracker.core

interface TokenStore {
    suspend fun get(): String?
    suspend fun set(value: String)
    suspend fun clear()
}

const val SESSION_TOKEN_STORAGE_KEY = "finance-tracker:session-token"
