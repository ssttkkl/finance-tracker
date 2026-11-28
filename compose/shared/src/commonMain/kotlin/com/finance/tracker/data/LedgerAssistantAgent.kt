package com.finance.tracker.data

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.execution.path
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.agents.snapshot.feature.withPersistence
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import com.finance.tracker.domain.DomainFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val CREATE_ASSISTANT_TRANSACTION = "create_transaction"
internal val ledgerAssistantReadTools = listOf(
    "list_accounts", "list_categories", "list_transactions", "summarize_transactions", "get_transaction",
)

internal class LedgerAssistantContinuation(
    val input: String?,
    val previousToolId: String? = null,
    val previousToolResult: JsonObject? = null,
)

internal class LedgerAssistantRound(
    val checkpoint: String,
    val messages: List<LedgerAssistantMessage>,
    val draft: LedgerAssistantDraft?,
)

private val assistantCheckpointJson = Json { ignoreUnknownKeys = false }
private val assistantAgentModel = LLModel(LLMProvider.OpenAI, "ledger-assistant", listOf(LLMCapability.Tools))

@Serializable
private data class LedgerAssistantHostInput(val unused: String? = null)

private class LedgerAssistantHostTool(name: String) : SimpleTool<LedgerAssistantHostInput>(
    typeToken<LedgerAssistantHostInput>(), name, "Ledger workspace tool",
) {
    override suspend fun execute(args: LedgerAssistantHostInput): String = error("Ledger tools are resolved by their host")
}

internal suspend fun runLedgerAssistantAgent(
    executor: PromptExecutor,
    conversationId: String,
    savedCheckpoint: String?,
    continuation: LedgerAssistantContinuation,
    onReadMessages: suspend (List<LedgerAssistantMessage>) -> Unit = {},
    executeSkill: (suspend (String, String, JsonObject) -> LedgerAssistantSkillOutcome)? = null,
    persistSkill: (suspend (LedgerAssistantSkillCommit, List<LedgerAssistantMessage>) -> Unit)? = null,
    readTool: suspend (String, JsonObject) -> JsonObject,
): LedgerAssistantRound {
    var appendInitialInput = savedCheckpoint == null
    var resumePreviousResponse = savedCheckpoint != null
    var latest: AgentCheckpointData? = null
    var draft: LedgerAssistantDraft? = null
    val messages = mutableListOf<LedgerAssistantMessage>()
    val agentStrategy = strategy<Unit, Unit>("ledger-assistant") {
        val ask by node<Unit, Message.Assistant> {
            val response = llm.writeSession {
                if (appendInitialInput) {
                    appendPrompt { user(checkNotNull(continuation.input)) }
                    appendInitialInput = false
                }
                requestLLM()
            }
            latest = checkNotNull(withPersistence { context ->
                createCheckpointAfterNode(
                    context, context.executionInfo.path(), response, typeToken<Message.Assistant>(), 0,
                )
            })
            response
        }
        val handle by node<Message.Assistant, Boolean> { response ->
            val calls = response.parts.filterIsInstance<MessagePart.Tool.Call>()
            if (resumePreviousResponse) {
                resumePreviousResponse = false
                val toolId = continuation.previousToolId
                llm.writeSession {
                    appendPrompt {
                        if (toolId != null) {
                            val call = calls.single()
                            check(call.id == toolId && call.tool == CREATE_ASSISTANT_TRANSACTION)
                            toolResult(tool = call.tool, id = toolId, output = checkNotNull(continuation.previousToolResult).toString())
                        } else {
                            check(calls.isEmpty())
                        }
                        continuation.input?.let { user(it) }
                    }
                }
                return@node true
            }

            val text = response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
            if (text.isNotBlank()) messages += LedgerAssistantMessage(reply = text)
            if (calls.isEmpty()) return@node false
            check(calls.size == 1) { "ledger_assistant_multiple_calls" }
            val call = calls.single()
            val id = checkNotNull(call.id)
            val fields = try {
                Json.parseToJsonElement(call.args) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            }
            val parameters = ledgerAssistantTraceParameters(call.tool, fields)
            if (call.tool == CREATE_ASSISTANT_TRANSACTION && fields != null) {
                val pending = LedgerAssistantDraft(newAssistantId(), id, fields)
                draft = pending
                messages += LedgerAssistantMessage(toolTrace = LedgerAssistantToolTrace(
                    call.tool, parameters, LedgerAssistantToolStatus.WAITING, draftId = pending.localId,
                ))
                return@node false
            }

            val readTrace = LedgerAssistantToolTrace(call.tool, parameters, LedgerAssistantToolStatus.RUNNING)
            messages += LedgerAssistantMessage(toolTrace = readTrace)
            onReadMessages(messages.toList())
            var skill: LedgerAssistantSkillOutcome? = null
            var output = try {
                when {
                    fields == null -> buildJsonObject { put("error", "invalid_tool_input") }
                    call.tool in ledgerAssistantSkillToolNames && executeSkill != null ->
                        executeSkill(id, call.tool, fields).also { skill = it }.output
                    call.tool in ledgerAssistantReadTools -> readTool(call.tool, fields)
                    else -> buildJsonObject { put("error", "invalid_tool_input") }
                }
            } catch (_: DomainFailure) {
                buildJsonObject { put("error", "ledger_read_unavailable") }
            }
            fun observeTool() {
                val failed = "error" in output
                val result = ledgerAssistantTraceResult(call.tool, output)
                val trace = skill?.trace
                messages[messages.lastIndex] = LedgerAssistantMessage(
                    result = if (failed || skill != null) null else LedgerAssistantToolResult(call.tool, output),
                    toolTrace = readTrace.copy(
                        status = if (failed) LedgerAssistantToolStatus.FAILED else LedgerAssistantToolStatus.COMPLETED,
                        result = if (trace == null) result else JsonObject(result.orEmpty() + trace),
                    ),
                    attachment = skill?.attachment,
                )
            }
            observeTool()
            val commit = skill?.commit
            if (commit == null) onReadMessages(messages.toList()) else {
                try {
                    checkNotNull(persistSkill)(commit, messages.toList())
                } catch (failure: LedgerAssistantScriptFailure) {
                    skill = LedgerAssistantSkillOutcome(buildJsonObject { put("error", failure.reason) })
                    output = checkNotNull(skill).output
                    observeTool()
                    onReadMessages(messages.toList())
                }
            }
            llm.writeSession {
                val response = if (call.tool == "summarize_transactions") {
                    JsonObject(output - "display_items" - "display_category")
                } else output
                appendPrompt { toolResult(tool = call.tool, id = id, output = response.toString()) }
            }
            true
        }
        edge(nodeStart forwardTo ask)
        edge(ask forwardTo handle)
        edge(handle forwardTo ask onCondition { it } transformed { Unit })
        edge(handle forwardTo nodeFinish onCondition { !it } transformed { Unit })
    }

    val agent = AIAgent(
        promptExecutor = executor,
        llmModel = assistantAgentModel,
        strategy = agentStrategy,
        toolRegistry = ToolRegistry {
            val enabledSkills = if (executeSkill == null) emptySet() else ledgerAssistantSkillToolNames
            (ledgerAssistantReadTools + CREATE_ASSISTANT_TRANSACTION + enabledSkills).forEach { tool(LedgerAssistantHostTool(it)) }
        },
        maxIterations = 24,
    ) {
        install(Persistence) { enableAutomaticPersistence = false }
    }
    try {
        if (savedCheckpoint == null) {
            agent.run(Unit, sessionId = conversationId)
        } else {
            val saved = assistantCheckpointJson.decodeFromString<AgentCheckpointData>(savedCheckpoint)
            val checkpoint = AgentCheckpointData(
                checkpointId = saved.checkpointId,
                createdAt = saved.createdAt,
                messageHistory = saved.messageHistory,
                llmParams = saved.llmParams,
                version = saved.version,
                graphProperties = checkNotNull(saved.graphProperties),
                llmModel = saved.llmModel,
                tools = saved.tools,
            )
            Persistence.runFromCheckpoint(agent, Unit, checkpoint, sessionId = conversationId)
        }
        return LedgerAssistantRound(assistantCheckpointJson.encodeToString(checkNotNull(latest)), messages, draft)
    } finally {
        agent.close()
    }
}
