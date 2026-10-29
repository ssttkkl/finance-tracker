package com.finance.tracker.backend

import com.finance.tracker.business.contracts.AccountCommandViewDto
import com.finance.tracker.business.contracts.AccountReferenceDto
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

class AccountFailure(val code: String, val receiptId: String? = null) : RuntimeException(code)

class AccountService(private val database: Database, private val now: () -> Instant = Instant::now,
    private val receiptId: () -> String = { UUID.randomUUID().toString() },
    private val beforeCommit: () -> Unit = {}, private val afterCommit: () -> Unit = {},
    private val beforeWrite: () -> Unit = {}, private val beforeWorkspaceLock: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    private val lock get() = if (database.sqlite) "" else " FOR UPDATE"
    private val jsonValue get() = if (database.sqlite) "?" else "CAST(? AS JSON)"
    private data class Row(val id: Long, var name: String, val type: String, var active: Boolean,
        var currencies: List<String>, var revision: Long)
    private fun invalid(): Nothing = throw AccountFailure("account_payload_invalid")
    private fun id(value: String): Long = value.takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) }?.toLongOrNull() ?: invalid()
    private fun expected(value: Long): Long = value.takeIf { it in 1 until Long.MAX_VALUE } ?: invalid()
    private fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    private fun fields(payload: JsonObject, create: Boolean): JsonObject {
        val allowed = if (create) setOf("name", "type", "currencies") else setOf("name", "active", "currencies")
        if (payload.keys.any { it !in allowed } || create && !payload.keys.containsAll(listOf("name", "type"))) invalid()
        val result = payload.toMutableMap()
        if ("name" in payload) {
            val name = text(payload["name"]).trim { it.code in pythonWhitespace }
            if (name.codePointCount(0,name.length) !in 1..255) invalid()
            try { strictUtf8(name) } catch (_: java.nio.charset.CharacterCodingException) { invalid() }
            result["name"] = JsonPrimitive(name)
        }
        if (create && text(payload["type"]) !in setOf("cash", "loan", "lend", "security", "crypto")) invalid()
        if ("active" in payload && (payload["active"] as? JsonPrimitive)?.let { it.isString || it.booleanOrNull == null } != false) invalid()
        if (create && "currencies" !in result) result["currencies"] = JsonArray(emptyList())
        if ("currencies" in result) {
            val values = result["currencies"] as? JsonArray ?: invalid()
            if (values.size > 100) invalid()
            result["currencies"] = JsonArray(values.map { text(it).trim { c -> c.code in pythonWhitespace }.also {
                if (!it.matches(Regex("[A-Za-z]{3}"))) invalid()
            }.uppercase() }.distinct().sorted().map(::JsonPrimitive))
        }
        return JsonObject(result)
    }
    private fun <T> context(token: String?, write: Boolean, action: (Connection, Principal) -> T): T {
        val operation: (Connection) -> T = { connection ->
            val first = reads.session(connection,token)
            val workspace = first.workspaceId ?: throw AccessFailure(true)
            if (write) {
                beforeWorkspaceLock()
                val exists = connection.prepareStatement("SELECT id FROM workspaces WHERE id=?$lock").use {
                    it.setString(1,workspace); it.executeQuery().use { rows -> rows.next() }
                }
                if (!exists || reads.session(connection,token,lock=true).workspaceId != workspace) throw AccessFailure(true)
                connection.prepareStatement("SELECT role FROM workspace_memberships WHERE workspace_id=? AND user_id=?$lock").use {
                    it.setString(1,workspace);it.setString(2,first.userId);it.executeQuery().use { rows ->
                        if (!rows.next() || rows.getString(1) !in setOf("admin","editor")) throw AccessFailure(true)
                    }
                }
            }
            val principal = reads.context(connection,token,write)
            if (principal.workspaceId != workspace) throw AccessFailure(true)
            action(connection,principal)
        }
        return try { if (write) database.write(operation) else database.read(operation) }
        catch(failure: AccessFailure) {
            if(failure.forbidden) throw AccountFailure("workspace_forbidden")
            throw failure
        }
    }
    private fun accounts(connection: Connection, workspace: String): List<Row> = connection.prepareStatement(
        "SELECT id,name,type,active,currencies,revision FROM accounts WHERE workspace_id=?").use {
        it.setString(1,workspace);it.executeQuery().use { rows -> buildList {
            while (rows.next()) add(Row(rows.getLong(1),rows.getString(2),rows.getString(3),rows.getBoolean(4),
                Json.parseToJsonElement(rows.getString(5)).jsonArray.map { value -> value.jsonPrimitive.content },rows.getLong(6)))
        } }
    }
    private fun account(connection: Connection, workspace: String, id: Long, write: Boolean): Row = connection.prepareStatement(
        "SELECT id,name,type,active,currencies,revision FROM accounts WHERE workspace_id=? AND id=?" + if(write) lock else "").use {
        it.setString(1,workspace);it.setLong(2,id);it.executeQuery().use { rows ->
            if (!rows.next()) throw AccountFailure("account_not_found")
            Row(rows.getLong(1),rows.getString(2),rows.getString(3),rows.getBoolean(4),
                Json.parseToJsonElement(rows.getString(5)).jsonArray.map { v -> v.jsonPrimitive.content },rows.getLong(6))
        }
    }
    private fun view(row: Row, deleted: Boolean = false) = AccountCommandViewDto(row.id.toString(),row.name,row.type,row.active,row.currencies,row.revision,deleted)
    private fun viewJson(row: Row, deleted: Boolean = false) = accountProtocolJson.encodeToJsonElement(view(row,deleted)).jsonObject
    // All account foreign keys in schema 20261028_51; permanent result text IDs are deliberately absent.
    private fun referenced(c: Connection, workspace: String, row: Row): Boolean = listOf("statement_account_mappings",
        "cash_transactions","cash_transaction_components","cash_projections","investment_events","account_aliases","sync_cursors").any { table ->
        c.prepareStatement("SELECT 1 FROM $table WHERE workspace_id=? AND account_id=? LIMIT 1").use {
            it.setString(1,workspace);it.setLong(2,row.id);it.executeQuery().use { rows -> rows.next() }
        }
    }
    private fun snapshot(c: Connection, workspace: String, models: List<Row>, write: Boolean): JsonObject {
        val raw = c.prepareStatement("SELECT payload FROM ledger_snapshots WHERE workspace_id=?" + if(write) lock else "").use {
            it.setString(1,workspace);it.executeQuery().use { rows -> if(rows.next()) Json.parseToJsonElement(rows.getString(1)).jsonObject else
                Json.parseToJsonElement("""{"updated_at":"","accounts":{"cash":{},"loan":{},"lend":{},"security":{}}}""").jsonObject }
        }
        val buckets = raw["accounts"]?.let { check(it is JsonObject) { "invalid_snapshot_accounts" };it } ?: JsonObject(emptyMap())
        val byId = models.associateBy { it.id }
        return JsonObject(raw + ("accounts" to JsonObject(buckets.mapValues { (_,value) ->
            if(value !is JsonObject) value else {
                val named = linkedMapOf<String,JsonElement>()
                for((key,pockets) in value) {
                    val name = snapshotAccountId(key)?.let { byId[it]?.name } ?: key
                    val previous = named[name]
                    named[name] = if(previous is JsonObject && pockets is JsonObject) JsonObject(previous+pockets) else pockets
                }
                JsonObject(named)
            }
        })))
    }
    private fun bucket(snapshot: JsonObject, row: Row): JsonElement {
        val accounts = snapshot["accounts"] ?: JsonObject(emptyMap())
        return if(accounts is JsonObject) accounts[if(row.type in setOf("security","crypto")) "security" else row.type] ?: JsonObject(emptyMap()) else accounts
    }
    private fun decimalDigits(value: String): String? {
        val result=StringBuilder()
        for(code in value.codePoints().toArray()) {
            val start=pythonIntegerDigitStarts.firstOrNull { code in it until it+10 } ?: return null
            result.append('0'+(code-start))
        }
        return result.toString()
    }
    private fun snapshotAccountId(key: String): Long? {
        if(key.isEmpty()) return null
        val digits=decimalDigits(key)
        if(digits!=null) {if(digits.length>4300) throw AccountFailure("account_payload_invalid");return digits.toLongOrNull()}
        if(key.codePoints().allMatch { code -> code in pythonNondecimalDigits || pythonIntegerDigitStarts.any { code in it until it+10 } })
            throw AccountFailure("account_payload_invalid")
        return null
    }
    private fun zero(value: JsonElement): Boolean {
        if(value !is JsonPrimitive || value is JsonNull || (!value.isString && !value.content.matches(Regex("-?[0-9]+")))) return false
        val normalized=buildString { for(code in value.content.trim { it.code in pythonWhitespace }.replace("_", "").codePoints().toArray()) {
            val start=pythonIntegerDigitStarts.firstOrNull { code in it until it+10 }
            if(start==null) appendCodePoint(code) else append('0'+(code-start))
        } }
        val match=Regex("[+-]?(?:([0-9]+)(?:\\.([0-9]*))?|\\.([0-9]+))(?:[eE]([+-]?[0-9]+))?").matchEntire(normalized) ?: return false
        if((match.groupValues[1]+match.groupValues[2]+match.groupValues[3]).any { it!='0' }) return false
        val exponent=try { java.math.BigInteger(match.groupValues[4].ifEmpty { "0" })-java.math.BigInteger.valueOf((match.groupValues[2].length+match.groupValues[3].length).toLong()) }
            catch (_: NumberFormatException) {return false}
        return exponent >= java.math.BigInteger("-1999999999999999997") && exponent <= java.math.BigInteger("999999999999999999")
    }
    private fun inUse(bucket: JsonElement, row: Row): Boolean = bucket !is JsonObject || bucket[row.name]?.let { pockets ->
        pockets !is JsonObject || pockets.values.any { !zero(it) }
    } == true
    fun list(token: String?, view: String): JsonObject {
        if(view !in setOf("cash","investment")) throw AccountFailure("invalid_filter")
        return context(token,false) {c,user ->
            val types=if(view=="cash") "'cash','loan','lend'" else "'security','crypto'"
            val items=c.prepareStatement("SELECT id,name,type,active,currencies FROM accounts WHERE workspace_id=? AND type IN ($types) ORDER BY id").use {
                it.setString(1,user.workspaceId);it.executeQuery().use {rows ->buildList {
                    while(rows.next()) {
                        val currencies=if(view=="investment") null else {
                            val stored=Json.parseToJsonElement(rows.getString(5))
                            val values=when {
                                !pythonTruthy(stored) -> emptyList()
                                stored is JsonArray -> stored.toList()
                                stored is JsonObject -> stored.keys.map(::JsonPrimitive)
                                stored is JsonPrimitive && stored.isString -> stored.content.codePoints().toArray().map {code ->JsonPrimitive(String(Character.toChars(code)))}
                                else -> error("invalid_account_currencies")
                            }
                            values.map {value ->buildString {
                                pythonString(value).codePoints().forEach {code ->append(pythonUpperCase[code] ?: String(Character.toChars(code)))}
                            }}
                        }
                        add(accountProtocolJson.encodeToJsonElement(AccountReferenceDto(rows.getLong(1),rows.getString(2),rows.getString(3),rows.getBoolean(4),currencies)))
                    }
                } }
            }
            buildJsonObject {put("items",JsonArray(items))}
        }
    }
    fun get(token: String?, accountId: String): AccountCommandViewDto {
        val id = id(accountId)
        return context(token,false) { c,user ->
            val workspace = requireNotNull(user.workspaceId)
            val row = account(c,workspace,id,false)
            val writable = user.role?.canWrite == true
            val canDelete = writable && !row.active && !referenced(c,workspace,row) && !inUse(bucket(snapshot(c,workspace,accounts(c,workspace),false),row),row)
            view(row).copy(capabilities=mapOf("can_update" to writable,"can_delete" to canDelete))
        }
    }
    fun create(token: String?, payload: JsonObject, key: String) = execute(token,"account_create",fields(payload,true),key)
    fun update(token: String?, accountId: String, patch: JsonObject, revision: Long, key: String) =
        execute(token,"account_update",fields(patch,false),key,id(accountId),expected(revision))
    fun delete(token: String?, accountId: String, revision: Long, key: String) =
        execute(token,"account_delete",JsonObject(emptyMap()),key,id(accountId),expected(revision))
    private fun sortedJson(value: JsonElement): JsonElement = when(value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { sortedJson(it.value) })
        is JsonArray -> JsonArray(value.map(::sortedJson))
        else -> value
    }
    private fun execute(token: String?, operation: String, payload: JsonObject, key: String, accountId: Long? = null, revision: Long? = null): JsonObject {
        if(!key.matches(Regex("[A-Za-z0-9_:.~-]{1,255}"))) throw AccountFailure("account_payload_invalid")
        val digest = tokenDigest(sortedJson(buildJsonObject {
            put("operation",operation);put("account_id",accountId?.let(::JsonPrimitive) ?: JsonNull)
            put("expected_revision",revision?.let(::JsonPrimitive) ?: JsonNull);put("payload",payload)
        }).toString())
        beforeWrite()
        return context(token,true) { c,user ->
            val workspace = requireNotNull(user.workspaceId)
            val receipts=ReceiptRepository(c,workspace,database.sqlite,now,receiptId)
            try {receipts.replay(user.userId,key,digest,operation)?.let {return@context it}}
            catch(failure: ReceiptConflict) {throw AccountFailure(failure.code,failure.receiptId)}
            try {
                var row = accountId?.let { account(c,workspace,it,true) }
                if(row!=null && row.revision!=revision) throw AccountFailure("account_changed")
                if("name" in payload) c.prepareStatement("SELECT id FROM accounts WHERE workspace_id=? AND name=? AND id<>?").use {
                    it.setString(1,workspace);it.setString(2,payload.getValue("name").jsonPrimitive.content);it.setLong(3,row?.id ?: -1)
                    it.executeQuery().use { rows -> if(rows.next()) throw AccountFailure("account_name_unavailable") }
                }
                if(operation=="account_create") {
                    val at=now()
                    val sql="INSERT INTO accounts(workspace_id,name,type,active,revision,currencies,metadata_json,created_at,updated_at) VALUES(?,?,?, ?,1,$jsonValue,$jsonValue,?,?) RETURNING id"
                    val id=c.prepareStatement(sql).use {
                        it.setString(1,workspace);it.setString(2,payload.getValue("name").jsonPrimitive.content);it.setString(3,payload.getValue("type").jsonPrimitive.content)
                        it.setBoolean(4,true);it.setString(5,payload.getValue("currencies").toString());it.setString(6,"{}")
                        it.instant(7,at,database.sqlite);it.instant(8,at,database.sqlite);it.executeQuery().use { rows -> check(rows.next());rows.getLong(1) }
                    }
                    row=account(c,workspace,id,true)
                } else if(operation=="account_update") {
                    val current=requireNotNull(row)
                    payload["name"]?.let { current.name=it.jsonPrimitive.content }
                    payload["active"]?.let { current.active=it.jsonPrimitive.boolean }
                    payload["currencies"]?.let { current.currencies=it.jsonArray.map { currency -> currency.jsonPrimitive.content } }
                    current.revision++
                    c.prepareStatement("UPDATE accounts SET name=?,active=?,currencies=$jsonValue,revision=?,updated_at=? WHERE workspace_id=? AND id=? AND revision=?").use {
                        it.setString(1,current.name);it.setBoolean(2,current.active);it.setString(3,JsonArray(current.currencies.map(::JsonPrimitive)).toString())
                        it.setLong(4,current.revision);it.instant(5,now(),database.sqlite);it.setString(6,workspace);it.setLong(7,current.id);it.setLong(8,requireNotNull(revision))
                        if(it.executeUpdate()!=1) throw AccountFailure("account_changed")
                    }
                }
                val current=requireNotNull(row)
                val models=accounts(c,workspace)
                val state=snapshot(c,workspace,models,true).toMutableMap()
                val buckets=(state["accounts"] as? JsonObject)?.toMutableMap()
                if(operation=="account_delete") {
                    if(current.active) throw AccountFailure("account_active")
                    if(referenced(c,workspace,current) || inUse(bucket(JsonObject(state),current),current)) throw AccountFailure("account_in_use")
                    val kind=if(current.type in setOf("security","crypto")) "security" else current.type
                    val pockets=(buckets?.get(kind) as? JsonObject)?.toMutableMap()
                    pockets?.remove(current.name)
                    if(pockets!=null) buckets[kind]=JsonObject(pockets)
                    c.prepareStatement("DELETE FROM accounts WHERE workspace_id=? AND id=? AND revision=?").use {
                        it.setString(1,workspace);it.setLong(2,current.id);it.setLong(3,requireNotNull(revision));if(it.executeUpdate()!=1) throw AccountFailure("account_changed")
                    }
                    current.revision++
                } else if(current.type in setOf("cash","loan","lend")) {
                    val all=buckets ?: throw AccountFailure("account_in_use")
                    val kind=all[current.type] ?: JsonObject(emptyMap())
                    if(kind !is JsonObject) throw AccountFailure("account_in_use")
                    val pockets=kind[current.name] ?: JsonObject(emptyMap())
                    if(pockets !is JsonObject) throw AccountFailure("account_in_use")
                    val balances=pockets.toMutableMap()
                    (payload["currencies"] as? JsonArray)?.forEach { balances.putIfAbsent(it.jsonPrimitive.content,JsonPrimitive("0")) }
                    all[current.type]=JsonObject(kind+(current.name to JsonObject(balances)))
                }
                if(buckets!=null) state["accounts"]=JsonObject(buckets)
                saveSnapshot(c,workspace,JsonObject(state),models)
                val account=viewJson(current,operation=="account_delete")
                val summary=buildJsonObject { put("status","completed");put("account_id",current.id.toString());put("account_revision",current.revision);put("account",account) }
                val saved=try {receipts.create(user.userId,key,digest,operation,summary,listOf(buildJsonObject {
                    put("result_kind","account");put("item_ref",current.id.toString());put("outcome",operation.removePrefix("account_")+"d");put("account_id",current.id.toString())
                }))} catch(failure: ReceiptConflict) {throw AccountFailure(failure.code,failure.receiptId)}
                beforeCommit()
                saved
            } catch(failure: SQLException) {
                if(failure.sqlState?.startsWith("23")==true || failure.errorCode and 0xff == 19)
                    throw AccountFailure(if(operation=="account_delete") "account_in_use" else "account_name_unavailable")
                throw failure
            }
        }.also { afterCommit() }
    }
    private fun saveSnapshot(c: Connection, workspace: String, value: JsonObject, models: List<Row>) {
        val buckets=value["accounts"] as? JsonObject
        val payload=if(buckets==null) value else JsonObject(value+("accounts" to JsonObject(buckets.mapValues { (kind,bucket) ->
            if(bucket !is JsonObject) bucket else JsonObject(bucket.mapKeys { (name,_) ->
                models.firstOrNull { it.name==name && (it.type==kind || kind=="security" && it.type=="crypto") }?.id?.toString() ?: name
            })
        })))
        c.prepareStatement("INSERT INTO ledger_snapshots(workspace_id,payload,version,updated_at) VALUES(?,$jsonValue,1,?) ON CONFLICT(workspace_id) DO UPDATE SET payload=excluded.payload,version=ledger_snapshots.version+1,updated_at=excluded.updated_at").use {
            it.setString(1,workspace);it.setString(2,payload.toString());it.instant(3,now(),database.sqlite);it.executeUpdate()
        }
    }
}
