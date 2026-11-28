package com.finance.tracker.data

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

class LedgerAssistantAgentTest {
    @Test
    fun restoresCancelledCallBeforeOriginalInputInOneRequest() = runTest {
        val original = ScriptedPromptExecutor { messages ->
            assertEquals(listOf("午饭 38 元"), messages.textParts())
            MessagePart.Tool.Call("call-1", CREATE_ASSISTANT_TRANSACTION, """{"amount":"38"}""")
        }
        val prepared = runLedgerAssistantAgent(original, "chat-1", null, LedgerAssistantContinuation("午饭 38 元")) { _, _ ->
            error("Unexpected ledger read")
        }
        assertEquals(1, original.calls)
        assertEquals("call-1", prepared.draft?.toolCallId)
        val trace = prepared.messages.single().toolTrace
        assertEquals(prepared.draft?.localId, trace?.draftId)
        assertEquals(LedgerAssistantToolStatus.WAITING, trace?.status)
        assertEquals(JsonPrimitive("38"), trace?.parameters?.get("amount"))
        val correction = "  改成 36 元\n"
        val continuation = ScriptedPromptExecutor { messages ->
            val ordered = messages.flatMap(::assistantWireMessages)
            assertEquals(JsonPrimitive("tool"), ordered[2]["role"])
            assertEquals(JsonPrimitive("call-1"), ordered[2]["tool_call_id"])
            assertEquals(JsonPrimitive(correction), ordered[3]["content"])
            MessagePart.Text("请继续核对")
        }
        val delivered = runLedgerAssistantAgent(
            continuation, "chat-1", prepared.checkpoint,
            LedgerAssistantContinuation(correction, "call-1", buildJsonObject { put("status", "cancelled") }),
        ) { _, _ -> error("Unexpected ledger read") }
        assertEquals(1, continuation.calls)
        assertEquals("请继续核对", delivered.messages.single().reply)
        assertNull(delivered.draft)
    }

    @Test
    fun onlyReadsWhenModelRequestsKnownReadTool() = runTest {
        val executor = ScriptedPromptExecutor { messages ->
            if (messages.flatMap { it.parts }.none { it is MessagePart.Tool.Result }) {
                MessagePart.Tool.Call("read-1", "list_accounts", "{}")
            } else {
                MessagePart.Text("找到一个账户")
            }
        }
        val round = runLedgerAssistantAgent(executor, "chat-2", null, LedgerAssistantContinuation("查账户")) { tool, args ->
            assertEquals("list_accounts", tool)
            assertEquals(0, args.size)
            buildJsonObject { put("count", 1) }
        }
        assertEquals(2, executor.calls)
        assertEquals("list_accounts", round.messages.first().result?.tool)
        assertEquals("找到一个账户", round.messages.last().reply)
    }

    @Test
    fun retainsReadTracesInOrderWithSafeResults() = runTest {
        val executor = ScriptedPromptExecutor { messages ->
            when (messages.flatMap { it.parts }.count { it is MessagePart.Tool.Result }) {
                0 -> MessagePart.Tool.Call("read-1", "list_accounts", """{"credentials":"private"}""")
                1 -> MessagePart.Tool.Call("read-2", "get_transaction", """{"projection_id":"missing","prompt":"private"}""")
                else -> MessagePart.Text("Done")
            }
        }
        val snapshots = mutableListOf<List<LedgerAssistantMessage>>()
        val round = runLedgerAssistantAgent(executor, "chat-3", null, LedgerAssistantContinuation("Review"), { snapshots.add(it) }) { tool, _ ->
            if (tool == "list_accounts") {
                JsonObject(mapOf("accounts" to JsonArray(listOf(JsonObject(mapOf("private" to JsonPrimitive("value")))))))
            } else {
                JsonObject(mapOf("error" to JsonPrimitive("internal-details")))
            }
        }
        val observed = snapshots.last().mapNotNull { it.toolTrace }
        assertEquals(listOf("list_accounts", "get_transaction"), observed.map { it.tool })
        assertEquals(
            listOf(LedgerAssistantToolStatus.RUNNING, LedgerAssistantToolStatus.COMPLETED,
                LedgerAssistantToolStatus.RUNNING, LedgerAssistantToolStatus.FAILED),
            snapshots.map { it.last().toolTrace?.status },
        )
        assertEquals(observed, round.messages.mapNotNull { it.toolTrace })
        assertEquals(JsonObject(emptyMap()), observed[0].parameters)
        assertEquals(JsonObject(mapOf("count" to JsonPrimitive(1))), observed[0].result)
        assertEquals(setOf("projection_id"), observed[1].parameters.keys)
        assertEquals(LedgerAssistantToolStatus.FAILED, observed[1].status)
        assertNull(observed[1].result)
        assertNull(round.messages[1].result)
    }
}

private class ScriptedPromptExecutor(
    private val response: (List<Message>) -> MessagePart.ResponsePart,
) : PromptExecutor() {
    var calls = 0
        private set

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        calls += 1
        return Message.Assistant(response(prompt.messages), ResponseMetaInfo(Clock.System.now()))
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
        error("Unexpected streaming")

    override suspend fun moderate(prompt: Prompt, model: LLModel) = error("Unexpected moderation")

    override fun close() = Unit
}

private fun List<Message>.textParts(): List<String> =
    flatMap { it.parts }.filterIsInstance<MessagePart.Text>().map { it.text }
