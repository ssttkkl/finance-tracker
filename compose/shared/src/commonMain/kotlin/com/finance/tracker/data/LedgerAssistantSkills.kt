package com.finance.tracker.data

import com.finance.tracker.business.domain.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.TimeSource

internal val ledgerAssistantSkillToolNames = setOf("read_skill", "run_script", "send_file")
private const val EXPORT_SKILL = "ledger-analysis-export"

internal class LedgerAssistantSkillOutcome(
    val output: JsonObject,
    val attachment: LedgerAssistantFile? = null,
    val commit: LedgerAssistantSkillCommit? = null,
    val trace: JsonObject? = null,
)

internal class LedgerAssistantSkillCommit(
    val receipt: LedgerAssistantFileReceipt,
    val removed: Set<String> = emptySet(),
    val files: List<LedgerAssistantFileContent> = emptyList(),
    val checkBeforeCommit: suspend () -> Unit,
    val requiredSource: String? = null,
    val expectedLedgerVersion: Long? = null,
)

internal class LedgerAssistantSkills(
    private val reader: LedgerLocalReader,
    private val store: LedgerAssistantConversations,
    private val workspaceId: String,
    private val conversationId: String,
    private val messageId: String,
    private val checkScope: suspend () -> Unit,
) {
    suspend fun execute(callId: String, name: String, fields: JsonObject): LedgerAssistantSkillOutcome = try {
        checkScope()
        if (callId.isBlank() || callId.length > 512) throw LedgerAssistantScriptFailure("invalid_tool_input")
        if (name == "read_skill") {
            LedgerAssistantSkillOutcome(readSkill(fields))
        } else {
            val fingerprint = sha256Hex("$messageId\u0000$name\u0000${fields.canonicalSkillArguments()}")
            val existing = store.skillReceipt(conversationId, callId)
            if (existing != null) {
                if (existing.fingerprint != fingerprint) throw LedgerAssistantScriptFailure("call_conflict")
                LedgerAssistantSkillOutcome(existing.result)
            } else when (name) {
                "run_script" -> runScript(callId, fingerprint, fields)
                "send_file" -> sendFile(callId, fingerprint, fields)
                else -> throw LedgerAssistantScriptFailure("invalid_tool_input")
            }
        }
    } catch (failure: LedgerAssistantScriptFailure) {
        LedgerAssistantSkillOutcome(buildJsonObject { put("error", failure.reason) })
    }

    private suspend fun runScript(callId: String, fingerprint: String, fields: JsonObject): LedgerAssistantSkillOutcome {
        val code = fields["code"].skillText()
        val input = when (val value = fields["input"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> value
            else -> throw LedgerAssistantScriptFailure("invalid_tool_input")
        }
        if (fields.keys.any { it != "code" && it != "input" }) throw LedgerAssistantScriptFailure("invalid_tool_input")
        val directory = store.skillFiles(conversationId, now())
        val files = LedgerAssistantScriptFiles(directory, messageId) { id ->
            checkScope()
            store.readSkillFile(conversationId, id, now())?.text ?: throw LedgerAssistantScriptFailure("file_expired")
        }
        val ledger = LedgerAssistantScriptLedger(reader, workspaceId, checkScope)
        val reads = mutableMapOf<String, Int>()
        val started = TimeSource.Monotonic.markNow()
        val value = runLedgerAssistantScript(code, input) { method, args ->
            checkScope()
            when (method) {
                "fs.list", "fs.readText", "fs.writeText", "fs.remove" -> files.call(method, args)
                "csv.stringify" -> JsonPrimitive(stringifyLedgerAssistantCsv(
                    args.singleOrNull() as? JsonArray ?: throw LedgerAssistantScriptFailure("invalid_arguments"),
                ))
                "csv.parse" -> parseLedgerAssistantCsv(args.singleOrNull().skillText())
                else -> ledger.read(method, args).also {
                    reads[method] = reads.getOrElse(method) { 0 } + 1
                }
            }
        }
        ledger.checkVersion()
        val writes = files.writes()
        val output = buildJsonObject {
            put("value", value)
            put("files", JsonArray(writes.map { it.metadata.toolMetadata() }))
        }
        val trace = buildJsonObject {
            put("elapsed_ms", started.elapsedNow().inWholeMilliseconds)
            put("reads", buildJsonObject { reads.forEach { (method, count) -> put(method, count) } })
        }
        return LedgerAssistantSkillOutcome(
            output, trace = trace, commit = LedgerAssistantSkillCommit(
                LedgerAssistantFileReceipt(callId, fingerprint, output), files.removed(), writes, ledger::checkVersion,
                expectedLedgerVersion = ledger.version,
            ),
        )
    }

    private suspend fun sendFile(callId: String, fingerprint: String, fields: JsonObject): LedgerAssistantSkillOutcome {
        if (fields.keys != setOf("file_ref")) throw LedgerAssistantScriptFailure("invalid_tool_input")
        val reference = fields["file_ref"].skillText()
        val attachmentId = sha256Hex("$messageId\u0000$reference")
        val previous = store.readSkillFile(conversationId, attachmentId, now())
        val created = if (previous != null && previous.metadata.attached) null else {
            val source = store.readSkillFile(conversationId, reference, now())
                ?.takeUnless { it.metadata.attached } ?: throw LedgerAssistantScriptFailure("file_expired")
            LedgerAssistantFileContent(source.metadata.copy(id = attachmentId, attached = true), source.text)
        }
        val metadata = created?.metadata ?: checkNotNull(previous).metadata
        val output = metadata.toolMetadata()
        return LedgerAssistantSkillOutcome(output, created?.metadata, LedgerAssistantSkillCommit(
            LedgerAssistantFileReceipt(callId, fingerprint, output), files = listOfNotNull(created), checkBeforeCommit = checkScope,
            requiredSource = reference.takeIf { created != null },
        ))
    }

    private fun now() = Clock.System.now().toEpochMilliseconds()
}

private class LedgerAssistantScriptFiles(
    directory: List<LedgerAssistantFile>,
    private val messageId: String,
    private val read: suspend (String) -> String,
) {
    private val original = directory.filterNot { it.attached }.associateBy { it.path }
    private val attachments = directory.filter { it.attached }.associateBy { it.id }
    private val changed = linkedMapOf<String, LedgerAssistantFileContent?>()

    fun writes() = changed.values.filterNotNull()
    fun removed() = changed.keys.mapNotNull { original[it]?.id }.toSet()

    private fun files() = original.filterKeys { it !in changed }.values + writes().map { it.metadata }

    suspend fun call(method: String, args: JsonArray): JsonElement = when (method) {
        "fs.list" -> {
            if (args.isNotEmpty()) throw LedgerAssistantScriptFailure("invalid_arguments")
            JsonArray((files() + attachments.values).map { it.toolMetadata() })
        }
        "fs.readText" -> {
            val ref = args.singleOrNull().skillText()
            val staged = writes().firstOrNull { it.metadata.id == ref || it.metadata.path == ref }
            if (staged != null) JsonPrimitive(staged.text) else {
                val file = attachments[ref] ?: files().firstOrNull { it.id == ref || it.path == ref }
                    ?: throw LedgerAssistantScriptFailure("file_not_found")
                JsonPrimitive(read(file.id))
            }
        }
        "fs.writeText" -> {
            if (args.size != 2) throw LedgerAssistantScriptFailure("invalid_arguments")
            val path = args[0].skillText()
            val candidate = newLedgerAssistantTemporaryFile(path, args[1].skillText())
            val otherBytes = files().filterNot { it.path == path }.sumOf { it.size }
            if (candidate.metadata.size + otherBytes > LEDGER_ASSISTANT_TEMP_BYTES) {
                throw LedgerAssistantScriptFailure("directory_full")
            }
            val version = sha256Hex("$messageId\u0000$path\u0000${candidate.text}")
            val stableFile = LedgerAssistantFileContent(candidate.metadata.copy(id = version), candidate.text)
            changed[path] = stableFile
            stableFile.metadata.toolMetadata()
        }
        "fs.remove" -> {
            val path = args.singleOrNull().skillText()
            ledgerAssistantFileMime(path)
            val exists = files().any { it.path == path }
            if (exists) changed[path] = null
            JsonPrimitive(exists)
        }
        else -> throw LedgerAssistantScriptFailure("unknown_method")
    }
}

private fun LedgerAssistantFile.toolMetadata() = buildJsonObject {
    put(if (attached) "attachment_id" else "file_ref", id)
    put("name", name)
    put("path", path)
    put("content_type", mimeType)
    put("bytes", size)
}

private fun JsonElement.canonicalSkillArguments(): JsonElement = when (this) {
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { it.key to it.value.canonicalSkillArguments() })
    is JsonArray -> JsonArray(map { it.canonicalSkillArguments() })
    else -> this
}

private fun JsonElement?.skillText(): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw LedgerAssistantScriptFailure("invalid_arguments")

private fun readSkill(fields: JsonObject): JsonObject {
    val id = fields["skill_id"].skillText()
    if (id != EXPORT_SKILL || fields.keys.any { it != "skill_id" && it != "resource" }) {
        throw LedgerAssistantScriptFailure("skill_not_found")
    }
    val resource = fields["resource"]?.skillText() ?: "SKILL.md"
    val text = exportSkillResources[resource] ?: throw LedgerAssistantScriptFailure("skill_resource_not_found")
    return buildJsonObject {
        put("skill_id", EXPORT_SKILL)
        put("version", "1")
        put("resource", resource)
        put("text", text)
    }
}

private val exportSkillResources = mapOf(
    "SKILL.md" to """
        ---
        name: ledger-analysis-export
        description: Produce CSV, Markdown and plain text reports from the current ledger.
        ---
        Use the requested date range and the ledger time zone. If a relative period cannot be resolved, ask
        for the missing date or time zone. Resolve account or category identifiers from ft when filtering by name. Use summarize for monthly totals and async iteration for rows. Keep currency totals separate
        and use exact decimal strings. Keep full rows and private notes on the device. Return only small
        counts and summaries, then call send_file using the file_ref returned by run_script.
        Resources: reference.md, monthly.csv.js, summary.md.js.
    """.trimIndent(),
    "reference.md" to """
        Call run_script with {"code":"<async function body>","input":{...}}. The runtime supplies input, ft, fs,
        and csv. Pass only the function body, with no enclosing function declaration, imports, or Markdown fences.
        await ft.accounts.list() and await ft.categories.list() return directory rows.
        await ft.transactions.summarize(filter) returns ledger_seq and months[].currencies[] with exact decimals.
        for await (const row of ft.transactions.list(filter)) iterates transactions without a manual cursor.
        await ft.transactions.get(projection_id) takes a string projection ID.
        Pass a flat filter object with string values. Use date_from and date_to as ISO calendar dates (YYYY-MM-DD)
        and time_zone_id as an IANA zone name, such as Asia/Shanghai. Supply the first and last local dates
        of the requested month. Optional filter keys are category_id, account_id, currency, economic_type,
        counterparty, amount_min and amount_max. Omit unused keys. Use actual string identifiers.
        Example input for monthly.csv.js, for November 2026 in Shanghai:
        {"filter":{"date_from":"2026-11-01","date_to":"2026-11-30","time_zone_id":"Asia/Shanghai"}}
        Replace the example dates and zone with the confirmed values for the requested report.
        fs.list() lists this conversation's temporary files and previous attachments.
        fs.readText(refOrPath) reads a temporary path, file_ref or attachment_id.
        await fs.writeText(path, text) stages a .csv, .md or .txt file. fs.remove(path) removes a temporary file.
        await csv.stringify(rows) makes CSV and await csv.parse(text) returns rows as strings.
        Await all host calls. Files commit only when the script and version checks succeed.
        Once run_script succeeds, call send_file with {"file_ref":"<files[0].file_ref from run_script>"}.
        To make summary.md.js from a sent CSV, supply {"file":"<attachment_id returned by send_file>"} as input.
    """.trimIndent(),
    "monthly.csv.js" to """
        const { months } = await ft.transactions.summarize(input.filter);
        const rows = [["月份", "币种", "支出"]];
        for (const { month, currencies } of months) {
          for (const { currency, expense } of currencies) rows.push([month, currency, expense]);
        }
        await fs.writeText("月报.csv", await csv.stringify(rows));
        return { dataRowCount: rows.length - 1 };
    """.trimIndent(),
    "summary.md.js" to """
        const rows = await csv.parse(await fs.readText(input.file));
        const count = Math.max(0, rows.length - 1);
        await fs.writeText("月报摘要.md", "# 月报摘要\n\n共 " + count + " 条月度币种汇总。\n");
        return { dataRowCount: count };
    """.trimIndent(),
)
