package com.finance.tracker.data

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

internal class LedgerAssistantPromptExecutor(
    private val api: FinanceApiClient,
    private val credentials: ApiCredentialSnapshot,
    private val checkScope: suspend () -> Unit,
    private val onPreview: suspend (String) -> Unit,
) : PromptExecutor() {
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        val body = assistantInferenceHistory(prompt.messages)
        val endpoint = normalizeApiOrigin(credentials.apiOrigin, api.allowInsecureHttp) + "/api/v1/ledger-assistant/inference"
        val statement = api.httpClient.preparePost(endpoint) {
            credentials.token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            header("X-FT-Workspace-ID", checkNotNull(api.expectedWorkspace))
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        val text = StringBuilder()
        var publishedLength = 0
        var lastPublish = TimeSource.Monotonic.markNow()
        suspend fun publish(force: Boolean) {
            if (text.length == publishedLength || text.isBlank()) return
            if (!force && publishedLength != 0 && lastPublish.elapsedNow() < 80.milliseconds) return
            checkScope()
            onPreview(text.toString())
            lastPublish = TimeSource.Monotonic.markNow()
            publishedLength = text.length
        }
        return try {
            checkScope()
            val result = statement.execute { response ->
                if (response.status.value !in 200..299) {
                    api.rejectApiPayload(response.status.value, response.bodyAsText(), credentials.token, false)
                }
                if (response.contentType()?.match(ContentType.Text.EventStream) != true) {
                    parseAssistantObject(response.bodyAsText())
                } else {
                    val channel = response.bodyAsChannel()
                    var complete: JsonObject? = null
                    while (complete == null) {
                        val line = channel.readUTF8Line(520 * 1024) ?: throw unavailableAssistantResponse()
                        if (!line.startsWith("data:")) continue
                        val frame = parseAssistantObject(line.substringAfter(':').trimStart())
                        val value = frame["value"]
                        when (frame["type"]) {
                            JsonPrimitive("text") -> {
                                val delta = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                                    ?: throw unavailableAssistantResponse()
                                text.append(delta)
                                if (text.length > 512 * 1024) throw unavailableAssistantResponse()
                                publish(false)
                            }
                            JsonPrimitive("complete") -> complete = value as? JsonObject ?: throw unavailableAssistantResponse()
                            else -> throw unavailableAssistantResponse()
                        }
                    }
                    publish(true)
                    complete
                }
            }
            checkScope()
            assistantResponse(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publish(true)
            if (failure is ApiFailure || failure is FinanceTransportFailure) throw failure
            if (failure is IOException || failure is HttpRequestTimeoutException || api.isTrackedTransportFailure(failure)) {
                throw FinanceTransportFailure(failure)
            }
            throw unavailableAssistantResponse()
        }
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
        error("The ledger graph checkpoints complete messages from the streaming transport")

    override suspend fun moderate(prompt: Prompt, model: LLModel) =
        error("Unsupported ledger assistant moderation request")

    override fun close() = Unit
}

private fun unavailableAssistantResponse() = ApiFailure("assistant_unavailable", 503)

private fun parseAssistantObject(source: String): JsonObject =
    runCatching { Json.parseToJsonElement(source) as? JsonObject }.getOrNull() ?: throw unavailableAssistantResponse()

internal fun assistantInferenceHistory(messages: List<Message>): JsonArray {
    val wire = messages.flatMap(::assistantWireMessages)
    val lastInput = wire.indexOfLast { it["role"] == JsonPrimitive("user") }.coerceAtLeast(0)
    val followsToolResult = wire.getOrNull(lastInput - 1)?.get("role") == JsonPrimitive("tool")
    val activeStart = if (followsToolResult) (lastInput - 2).coerceAtLeast(0) else lastInput
    val active = wire.drop(activeStart)
    var remaining = 480 * 1024 - active.sumOf { it.toString().encodeToByteArray().size + 1 }
    val previous = wire.take(activeStart).asReversed().mapNotNull { item ->
        val content = (item["content"] as? JsonPrimitive)?.takeIf { it.isString } ?: return@mapNotNull null
        val role = item["role"]
        if (role != JsonPrimitive("assistant") && role != JsonPrimitive("user")) return@mapNotNull null
        buildJsonObject { put("role", role); put("content", content) }
    }.takeWhile { item ->
        remaining -= item.toString().encodeToByteArray().size + 1
        remaining >= 0
    }.asReversed()
    return JsonArray(previous + active)
}

internal fun assistantWireMessages(message: Message): List<JsonObject> = when (message) {
    is Message.User -> message.parts.map { part ->
        buildJsonObject {
            when (part) {
                is MessagePart.Text -> {
                    put("role", "user")
                    put("content", part.text)
                }
                is MessagePart.Tool.Result -> {
                    put("role", "tool")
                    put("tool_call_id", checkNotNull(part.id))
                    put("content", part.output)
                }
                else -> error("Unsupported ledger assistant input part")
            }
        }
    }
    is Message.Assistant -> listOf(buildJsonObject {
        put("role", "assistant")
        val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
        put("content", if (text.isEmpty()) JsonNull else JsonPrimitive(text))
        val calls = message.parts.filterIsInstance<MessagePart.Tool.Call>()
        if (calls.isNotEmpty()) put("tool_calls", JsonArray(calls.map { call ->
            buildJsonObject {
                put("id", checkNotNull(call.id))
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", call.tool)
                    put("arguments", call.args)
                })
            }
        }))
    })
    else -> error("Unsupported ledger assistant message role")
}

internal fun assistantResponse(response: JsonObject): Message.Assistant {
    check(response["role"] == JsonPrimitive("assistant"))
    val parts = buildList {
        val content = response["content"]
        if (content is JsonPrimitive && content.isString) add(MessagePart.Text(content.content))
        (response["tool_calls"] as? JsonArray)?.forEach { raw ->
            val call = raw as JsonObject
            val function = call.getValue("function") as JsonObject
            add(MessagePart.Tool.Call(
                id = call.requiredAssistantString("id"),
                tool = function.requiredAssistantString("name"),
                args = function.requiredAssistantString("arguments"),
            ))
        }
    }
    check(parts.isNotEmpty())
    return Message.Assistant(parts, ResponseMetaInfo(Clock.System.now()))
}

private fun JsonObject.requiredAssistantString(key: String): String {
    val value = this[key] as? JsonPrimitive
    check(value != null && value.isString)
    return value.content
}
