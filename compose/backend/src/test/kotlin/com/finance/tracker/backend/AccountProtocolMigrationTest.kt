package com.finance.tracker.backend

import kotlin.test.*

class AccountProtocolMigrationTest {
    @Test fun strictJsonRejectsEveryRawStringControlIncludingKeysAndNestedValues() {
        for(code in 0..31) {
            val character=code.toChar()
            for(raw in listOf("""{"name":"a${character}b"}""","""{"na${character}me":"ok"}""","""{"patch":{"name":"a${character}b"}}"""))
                assertFailsWith<AccountFailure>("raw control $code") {AccountJson(raw).parse()}
        }
        assertEquals("a\nb",AccountJson("""{"name":"a\nb"}""").parse().getValue("name").toString().let { kotlinx.serialization.json.Json.parseToJsonElement(it).let { value -> (value as kotlinx.serialization.json.JsonPrimitive).content } })
    }
}
