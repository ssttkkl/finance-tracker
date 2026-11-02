package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.*

private class InvestmentImportRejected(val result: JsonObject) : RuntimeException()
internal class InvestmentImportService(private val database: Database, private val now: () -> Instant = Instant::now,
    private val parse: (String, String, ByteArray, String?) -> List<Map<String, JsonElement>> = InvestmentDocument::read,
    private val afterParse: () -> Unit = {}, private val beforeReturn: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    private data class Account(val id: Long, val type: String, val metadata: JsonObject)
    private fun result(ok: Boolean, message: String, count: Int = 0, details: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("ok", ok); put("message", message); put("count", count); put("findings", JsonArray(emptyList())); put("export", JsonNull); put("details", details)
    }
    private fun reject(message: String): Nothing = throw InvestmentImportRejected(result(false, message))
    private fun account(context: CashSqlContext, name: String): Account {
        val a = ACCOUNTS
        val row = context.sql.select(a.ID, a.TYPE, a.METADATA_JSON).from(a).where(a.WORKSPACE_ID.eq(context.workspace)).and(a.NAME.eq(name)).fetchOne()
            ?: reject("找不到账户：$name")
        val type = row[a.TYPE]!!
        if (type !in setOf("security", "crypto")) reject("账户类型必须是 security 或 crypto，当前为：$type")
        return Account(row[a.ID]!!, type, row[a.METADATA_JSON]!!.jsonObject)
    }
    fun importStatement(token: String?, source: String, path: Path, account: String, currency: String? = null, password: String? = null, requestWorkspace: String? = null): JsonObject {
        reads.requireContext(token, true)
        val bytes = try { Files.readAllBytes(path) }
            catch (_: NoSuchFileException) { return result(false, "找不到文件：$path") }
            catch (failure: java.io.IOException) { return result(false, "读取文件失败：${failure.message}") }
        return importBytes(token, source, path.fileName.toString(), bytes, account, currency, password, requestWorkspace)
    }
    fun importBytes(token: String?, source: String, filename: String, bytes: ByteArray, accountName: String,
        currency: String? = null, password: String? = null, requestWorkspace: String? = null): JsonObject = try {
        val initial = database.read { c ->
            val principal = reads.context(c, token, true); val workspace = principal.workspaceId!!
            if (requestWorkspace != null && requestWorkspace != workspace) throw AccessFailure(true)
            workspace to account(CashSqlContext(c, workspace, database.sqlite, now), accountName)
        }
        val rows = try { parse(source, filename, bytes, password) }
            catch (failure: IllegalArgumentException) { reject("解析账单失败：${failure.message}") }
            catch (failure: ArithmeticException) { reject("解析账单失败：${failure.message}") }
            catch (failure: java.io.IOException) { reject("解析账单失败：${failure.message}") }
            catch (_: StatementPdfPasswordFailure) { reject("解析账单失败：PDF 密码无效") }
            catch (_: StatementDocumentFailure) { reject("解析账单失败：PDF 无法读取") }
        if (rows.isEmpty()) reject("账单中没有可导入的交易记录")
        val resolved = try { InvestmentDocument.currency(source, currency, rows) } catch (failure: IllegalArgumentException) { reject(failure.message.orEmpty()) }
        afterParse()
        database.write { c ->
            val workspace = initial.first
            if (!c.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
            reads.lockedContext(c, token, workspace, true)
            val context = CashSqlContext(c, workspace, database.sqlite, now)
            val current = account(context, accountName)
            if (current.id != initial.second.id || current.type != initial.second.type) reject("找不到账户：$accountName")
            val count = try {
                val sourceType = InvestmentDocument.sourceType(source)
                val ids = rows.map { valuationStrip(InvestmentDocument.identity(source, it)).removePrefix("$sourceType:") }
                val payloads = rows.map { InvestmentDocument.sourcePayload(source, it) }
                val repository = InvestmentEventRepository(context)
                val existing = repository.existing(sourceType, ids)
                val snapshot = context.snapshot.load(lock = true)
                val rawBases = current.metadata["base_currencies"]?.takeIf(::pythonTruthy)
                val bases = when (rawBases) {
                    null -> null
                    is JsonArray -> rawBases.map(::pythonString)
                    is JsonObject -> rawBases.keys
                    is JsonPrimitive -> if (rawBases.isString) rawBases.content.codePoints().toArray().map { String(Character.toChars(it)) }
                        else throw IllegalArgumentException("'${if (rawBases.booleanOrNull != null) "bool" else if (rawBases.content.any { it in ".eE" }) "float" else "int"}' object is not iterable")
                }
                var count = 0
                for (i in rows.indices) {
                    if (ids[i] in existing) continue
                    val row = InvestmentDocument.event(source, rows[i], accountName, resolved)
                    InvestmentProjection.applyEvent(snapshot, row, resolved, bases)
                    repository.insert(current.id, row, sourceType, ids[i], payloads[i])
                    existing += ids[i]; count++
                }
                if (count > 0) context.snapshot.save(snapshot)
                count
            } catch (failure: IllegalArgumentException) { reject("导入失败：${failure.message}") }
              catch (failure: ArithmeticException) { reject("导入失败：${failure.message}") }
            val snapshot = context.snapshot.load(lock = true)
            try { validateInvestmentSnapshot(snapshot.value) } catch (failure: IllegalArgumentException) { reject("校准结果校验失败：${failure.message}") }
            context.snapshot.save(snapshot)
            beforeReturn(); reads.lockedContext(c, token, workspace, true)
            result(true, if (count == 0) "没有可导入的新记录" else "已导入 $count 条投资事件", count, buildJsonObject {
                put("duplicate", count == 0); put("new_rows", count); put("batch_id", JsonNull)
                rows.first()["_usmart_ignored_trade_mirrors"]?.let { put("ignored_trade_mirrors", it) }
            }).also { manualOriginalUtf8(JsonPrimitive(it.toString()), null) }
        }
    } catch (rejected: InvestmentImportRejected) { rejected.result }

}
