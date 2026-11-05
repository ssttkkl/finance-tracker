package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import com.finance.tracker.business.contracts.Role
import kotlinx.serialization.json.*

internal open class ConnectorFailure(message: String) : RuntimeException(message)
internal class ConnectorAuthFailure(message: String) : ConnectorFailure(message)
internal class ConnectorDataFailure(message: String) : ConnectorFailure(message)
internal data class ConnectorResult(val events: List<JsonObject> = emptyList(), val nextCursor: String? = null, val rawCount: Int = 0)
internal interface InvestmentConnector {
    val sourceType: String
    fun fetch(since: String?): ConnectorResult
}
internal class SyncCursorRepository(private val context: CashSqlContext) {
    fun get(account: Long, source: String): String? {
        val t = SYNC_CURSORS
        return context.sql.select(t.CURSOR_VALUE).from(t).where(t.WORKSPACE_ID.eq(context.workspace)).and(t.ACCOUNT_ID.eq(account)).and(t.SOURCE_TYPE.eq(source)).fetchOne(t.CURSOR_VALUE)
    }
    fun upsert(account: Long, source: String, cursor: String) {
        val t = SYNC_CURSORS; val at = context.now()
        context.sql.insertInto(t).set(t.WORKSPACE_ID, context.workspace).set(t.ACCOUNT_ID, account).set(t.SOURCE_TYPE, source).set(t.CURSOR_VALUE, cursor).set(t.UPDATED_AT, at)
            .onConflict(t.WORKSPACE_ID, t.ACCOUNT_ID, t.SOURCE_TYPE).doUpdate().set(t.CURSOR_VALUE, cursor).set(t.UPDATED_AT, at).execute()
    }
}
private class SyncRejected(val result: JsonObject) : RuntimeException()
internal class SyncService(private val database: Database, private val now: () -> Instant = Instant::now,
    private val applyEvent: (CashSnapshotDocument, JsonObject, String) -> Unit = { snapshot, event, currency -> InvestmentProjection.applyEvent(snapshot, event, currency) },
    private val afterFetch: () -> Unit = {}, private val beforeReturn: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    private data class Account(val id: Long, val type: String)
    private fun result(ok: Boolean, message: String, count: Int = 0, details: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("ok", ok); put("message", message); put("count", count); put("findings", JsonArray(emptyList())); put("export", JsonNull); put("details", details)
    }
    private fun reject(message: String): Nothing = throw SyncRejected(result(false, message))
    private fun account(context: CashSqlContext, name: String, provider: String): Account {
        val t = ACCOUNTS
        val row = context.sql.select(t.ID, t.TYPE).from(t).where(t.WORKSPACE_ID.eq(context.workspace)).and(t.NAME.eq(name)).fetchOne() ?: reject("找不到账户：$name")
        val allowed = if (provider == "polymarket") setOf("crypto", "security") else setOf("crypto")
        val type = row[t.TYPE]!!
        if (type !in allowed) reject("账户 $name 的类型是 $type；同步 $provider 需要 ${allowed.sorted().joinToString(" 或 ")} 类型的账户")
        return Account(row[t.ID]!!, type)
    }
    fun sync(token: String?, provider: String, accountName: String, full: Boolean = false, batchSize: Int = 500,
        connector: InvestmentConnector? = null, requestWorkspace: String? = null, requireAdministrator: Boolean = false): JsonObject = try {
        fun permitted(principal: Principal): Principal {
            if (requireAdministrator && principal.role != Role.ADMIN) throw AccessFailure(true)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("sync interrupted")
            return principal
        }
        permitted(reads.requireContext(token, true))
        if (provider !in setOf("binance", "kraken", "okx", "polymarket")) reject("未知的同步数据源：$provider。支持：binance, kraken, okx, polymarket")
        if (batchSize <= 0) reject("batch_size 必须大于 0")
        val source = "${provider}_api"
        val initial = database.read { c ->
            val workspace = permitted(reads.context(c, token, true)).workspaceId!!
            if (requestWorkspace != null && requestWorkspace != workspace) throw AccessFailure(true)
            val context = CashSqlContext(c, workspace, database.sqlite, now)
            val account = account(context, accountName, provider)
            Triple(workspace, account, if (full) null else SyncCursorRepository(context).get(account.id, source))
        }
        if (connector == null) reject("没有为数据源 $provider 配置连接器")
        fun fetch(since: String?, retry: Boolean): ConnectorResult = try { connector.fetch(since) }
            catch (failure: ConnectorAuthFailure) { reject("数据源 $provider 认证失败：${failure.message}") }
            catch (failure: ConnectorDataFailure) { reject("数据源 $provider 返回的数据不符合要求：${failure.message}") }
            catch (failure: ConnectorFailure) {
                if (retry) reject("数据源 $provider 连接失败，使用旧同步游标重试也失败：${failure.message}")
                if (since == null) reject("数据源 $provider 连接失败：${failure.message}")
                throw failure
            }
        val fetched = try { fetch(initial.third, false) } catch (_: ConnectorFailure) { fetch(null, true) }
        afterFetch()
        val events = fetched.events
        val counts = try { database.write { c ->
            val workspace = initial.first
            if (!c.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
            permitted(reads.lockedContext(c, token, workspace, true))
            val context = CashSqlContext(c, workspace, database.sqlite, now)
            val account = account(context, accountName, provider)
            if (account != initial.second) reject("找不到账户：$accountName")
            val repository = InvestmentEventRepository(context)
            val ids = events.map { valuationStrip(pythonString(it["record_id"] ?: JsonPrimitive(""))) }
            val existing = repository.existing(source, ids)
            val snapshot = context.snapshot.load(lock = true)
            var added = 0; var skipped = 0
            for (batch in events.indices.toList().chunked(batchSize)) for (index in batch) {
                if (ids[index] in existing) { skipped++; continue }
                val event = JsonObject(events[index] + mapOf("source_type" to JsonPrimitive(source), "record_id" to JsonPrimitive(ids[index]), "account_name" to JsonPrimitive(accountName)))
                val currency = valuationUpper(pythonString(event["currency"] ?: JsonPrimitive("USD")))
                applyEvent(snapshot, event, currency)
                val payload = event["source_payload"]?.takeUnless { it == JsonNull }
                repository.insert(account.id, event, source, ids[index], payload)
                existing += ids[index]; added++
            }
            if (added > 0) { validateInvestmentSnapshot(snapshot.value); context.snapshot.save(snapshot) }
            fetched.nextCursor?.let { SyncCursorRepository(context).upsert(account.id, source, it) }
            beforeReturn(); permitted(reads.lockedContext(c, token, workspace, true))
            added to skipped
        } } catch (failure: IllegalArgumentException) { throw SyncRejected(failedImport(failure, fetched.rawCount)) }
            catch (failure: ArithmeticException) { throw SyncRejected(failedImport(failure, fetched.rawCount)) }
        val message = if (events.isEmpty()) "数据源 $provider 没有新的交易记录" else
            "已从数据源 $provider 同步 ${counts.first} 条新投资事件" + if (counts.second > 0) "（跳过 ${counts.second} 条重复记录）" else ""
        result(true, message, counts.first, buildJsonObject {
            put("raw_count", fetched.rawCount); put("new_count", counts.first); put("skipped_count", counts.second)
            put("batch_count", if (events.isEmpty()) 0 else (events.size - 1) / batchSize + 1)
        })
    } catch (rejected: SyncRejected) { rejected.result }
    private fun failedImport(failure: RuntimeException, rawCount: Int) = result(false, "同步失败：${failure.message}。未写入投资事件、校准结果或同步游标。", details = buildJsonObject {
        put("raw_count", rawCount); put("new_count", 0); put("skipped_count", 0)
    })
}
