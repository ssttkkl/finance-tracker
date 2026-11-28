package com.finance.tracker.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LedgerAssistantScriptTest {
    @Test
    fun acceptsBoundedInputAndKeepsSourceInsideTheFunctionBody() {
        val source = "return input.name; // \"\n"
        val program = ledgerAssistantScriptProgram(source, JsonObject(mapOf("name" to JsonPrimitive("报表"))))
        val expected = JsonPrimitive(source).toString()
        check(program.contains(expected))
        check(program.contains("const invoke = globalThis.__ledgerInvoke"))
        check(program.contains("delete globalThis.__ledgerInvoke"))
    }

    @Test
    fun measuresLimitsInUtf8AndKeepsHostErrorsStructured() = runTest {
        assertFailsWith<LedgerAssistantScriptFailure> {
            ledgerAssistantScriptProgram("账".repeat(32_768), JsonObject(emptyMap()))
        }
        val denied = ledgerAssistantScriptHostResponse("{}") { _, _ -> error("should not dispatch") }
        assertEquals("""{"error":"invalid_request"}""", denied)
        val result = ledgerAssistantScriptHostResponse("""{"method":"fs.list","args":[]}""") { method, args ->
            assertEquals("fs.list", method)
            assertEquals(0, args.size)
            JsonPrimitive("报表")
        }
        assertEquals("""{"value":"报表"}""", result)
    }
}
