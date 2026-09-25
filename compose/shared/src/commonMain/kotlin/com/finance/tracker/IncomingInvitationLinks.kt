package com.finance.tracker

/** Bridges Android intents and iOS onOpenURL into the same invitation route. */
object IncomingInvitationLinks {
    private var currentToken: String? = null
    private val listeners = mutableListOf<(String) -> Unit>()

    fun currentToken(): String? = currentToken

    fun clear(token: String? = null) {
        if (token == null || currentToken == token) currentToken = null
    }

    fun observe(listener: (String) -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    fun receive(url: String) {
        val token = invitationTokenFromUrl(url) ?: return
        currentToken = token
        listeners.toList().forEach { it(token) }
    }
}

fun receiveIncomingInvitationUrl(url: String) = IncomingInvitationLinks.receive(url)
