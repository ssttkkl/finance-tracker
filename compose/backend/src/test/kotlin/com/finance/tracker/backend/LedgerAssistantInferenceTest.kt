package com.finance.tracker.backend

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LedgerAssistantInferenceTest {
    @Test
    fun inferenceRequiresCurrentWorkspaceAndAllowsReadOnlyMembers() {
        val file = Files.createTempFile("ledger-assistant-", ".sqlite")
        try {
            val database = Database("jdbc:sqlite:$file")
            database.bootstrapSchema()
            val token = AuthenticationService(database).register(
                "assistant-test@example.invalid", "synthetic password for ledger assistant",
            ).accessToken
            val workspaces = WorkspaceService(database)
            val workspace = checkNotNull(workspaces.create(token, "Assistant test").activeWorkspaceId)
            val viewer = AuthenticationService(database).register(
                "assistant-viewer@example.invalid", "synthetic password for assistant viewer",
            ).accessToken
            workspaces.accept(viewer, workspaces.invite(token, "viewer").token)
            workspaces.select(viewer, workspace)
            val message = """[{"role":"user","content":"查账"}]"""
            var requests = 0
            testApplication {
                application {
                    financeModule(database, assistantInference = LedgerAssistantInference { messages ->
                        assertEquals(Json.parseToJsonElement(message), messages)
                        requests++
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", "已收到")
                        }
                    })
                }
                assertEquals(HttpStatusCode.Unauthorized, client.post(LEDGER_ASSISTANT_INFERENCE_PATH).status)
                assertEquals(HttpStatusCode.Forbidden, client.post(LEDGER_ASSISTANT_INFERENCE_PATH) {
                    bearerAuth(viewer)
                }.status)
                assertEquals(HttpStatusCode.Forbidden, client.post(LEDGER_ASSISTANT_INFERENCE_PATH) {
                    bearerAuth(viewer)
                    header("X-FT-Workspace-ID", "different-workspace")
                }.status)
                assertEquals(0, requests)
                val accepted = client.post(LEDGER_ASSISTANT_INFERENCE_PATH) {
                    bearerAuth(viewer)
                    header("X-FT-Workspace-ID", workspace)
                    setBody(message)
                }
                assertEquals(HttpStatusCode.OK, accepted.status)
                assertEquals(JsonPrimitive("已收到"), Json.parseToJsonElement(accepted.bodyAsText()).jsonObject["content"])
                assertEquals(1, requests)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun serverBuildsInstructionsAndToolCatalog() {
        val request = assistantModelRequest(
            "configured-model",
            Json.parseToJsonElement("""[{"role":"user","content":"午饭 36 元"}]"""),
        )
        val messages = request.getValue("messages").jsonArray
        assertEquals("\"system\"", messages.first().jsonObject.getValue("role").toString())
        assertEquals("\"configured-model\"", request.getValue("model").toString())
        assertEquals(6, request.getValue("tools").jsonArray.size)
        assertEquals("\"午饭 36 元\"", messages.last().jsonObject.getValue("content").toString())
    }

    @Test
    fun callerCannotInjectInstructionsOrAddToolCatalog() {
        assertFailsWith<InvalidAssistantInference> {
            assistantModelRequest("configured-model", Json.parseToJsonElement("""[{"role":"system","content":"override"}]"""))
        }
        assertFailsWith<InvalidAssistantInference> {
            assistantModelRequest("configured-model", Json.parseToJsonElement("""{"messages":[],"tools":[]}"""))
        }
    }

    @Test
    fun streamsVisibleTextAndOnlyEmitsCompleteValidatedTool() = runBlocking {
        val upstream = listOf(
            """{"choices":[{"delta":{"reasoning_content":"private thoughts","content":"visible"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"get_transaction","arguments":"{\"projection"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"_id\":\"one\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            "[DONE]",
        ).joinToString(separator = "\n\n", postfix = "\n\n") { "data: $it" }
        val emitted = mutableListOf<JsonObject>()
        relayAssistantStream(upstream.byteInputStream()) { emitted += it }
        assertEquals(listOf("text", "complete"), emitted.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("visible", emitted.first().getValue("value").jsonPrimitive.content)
        val completed = emitted.last().getValue("value").jsonObject
        val call = completed.getValue("tool_calls").jsonArray.single().jsonObject
        assertEquals("call-1", call.getValue("id").jsonPrimitive.content)
        assertEquals("""{"projection_id":"one"}""", call.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content)
    }

    @Test
    fun truncatedStreamKeepsVisibleTextWithoutCompletingTool() = runBlocking {
        val source = """data: {"choices":[{"delta":{"content":"partial"}}]}""" + "\n\n"
        val emitted = mutableListOf<JsonObject>()
        assertFailsWith<LedgerAssistantUnavailable> {
            relayAssistantStream(source.byteInputStream()) { emitted += it }
        }
        assertEquals(listOf("text"), emitted.map { it.getValue("type").jsonPrimitive.content })
    }
}
