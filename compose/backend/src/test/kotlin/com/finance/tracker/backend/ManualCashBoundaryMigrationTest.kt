package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class ManualCashBoundaryMigrationTest {
    @Test fun hugeExponentZerosKeepFixedSqliteRepresentationAndActualPostgresStorageRejection()=inWorkspace {db,workspace ->
        val initial=counts(db,workspace)
        for((amount,expected) in listOf("0E2147483648" to "0","-0E2147483648" to "-0")) {
            val response=postBody(body(mapOf("amount" to JsonPrimitive(amount))))
            assertEquals(if(db.sqlite) 201 else 503,response.status.value,response.bodyAsText())
            if(db.sqlite) {
                val record=Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("record").jsonObject
                assertEquals(JsonPrimitive(expected),record["amount"])
                assertEquals(JsonPrimitive(expected),record.getValue("components").jsonArray.single().jsonObject["amount"])
                db.read {c ->c.prepareStatement("SELECT amount FROM cash_transactions WHERE id=? AND workspace_id=?").use {
                    it.setLong(1,record.getValue("id").jsonPrimitive.long);it.setString(2,workspace);it.executeQuery().use {r ->assertTrue(r.next());assertEquals(expected,r.getString(1))}}}
            } else assertEquals(initial,counts(db,workspace))
        }
    }
    @Test fun unencodableUserTextAndErrorMessagesCannotCommitReplacementCharacters()=inWorkspace {db,workspace ->
        val initial=counts(db,workspace)
        for((index,patch) in listOf(mapOf("note" to JsonPrimitive("\ud800")),mapOf("counterparty" to JsonPrimitive("\ud800")),
            mapOf("components" to Json.parseToJsonElement("""[{"account_name":"账户","amount":"-10","label":"\ud800"}]""")),
            mapOf("record_type" to JsonPrimitive("\ud800")),mapOf("occurred_at" to JsonPrimitive("bad\ud800"))).withIndex()) {
            val response=postBody(body(patch));assertEquals(if(index<3) 400 else 500,response.status.value,response.bodyAsText());assertEquals(initial,counts(db,workspace))
        }
    }
    @Test fun specialDecimalTokensUseAsciiCaseAndDoNotSaveInvalidAmounts()=inWorkspace {db,workspace ->
        val initial=counts(db,workspace)
        for(token in listOf("İnf","ınf","ſNaN")) {
            val response=postBody(body(mapOf("amount" to JsonPrimitive(token))))
            assertEquals(400,response.status.value,response.bodyAsText())
            assertEquals("amount must be decimal-compatible",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
            assertEquals(initial,counts(db,workspace))
        }
    }
    @Test fun outOfRangeAccountIdsKeepTheActualDatabaseFailureBoundary()=inWorkspace {db,workspace ->
        val initial=counts(db,workspace)
        for(name in listOf("","账户")) {
            val response=postBody(body(mapOf("account_name" to JsonPrimitive(name),"components" to Json.parseToJsonElement("""[{"account_id":"9223372036854775808","amount":"-10"}]"""))))
            assertEquals(if(db.sqlite) 500 else 503,response.status.value,response.bodyAsText())
            if(!db.sqlite) assertEquals("storage.connect",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(initial,counts(db,workspace))
        }
    }
    private fun inWorkspace(action:suspend ApplicationTestBuilder.(Database,String)->Unit)=testApplication {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val service=WorkspaceService(db);val name="手工新建边界测试";val workspace=service.create("synthetic-admin",name).activeWorkspaceId!!
        deletionAccount(db,workspace)
        setAccountTestRole(db,workspace,"editor")
        application {financeModule(db)}
        try {action(db,workspace)} finally {setAccountTestRole(db,workspace,"admin");service.delete("synthetic-admin",name);service.select("synthetic-admin","fixture-a")}
    }
    private fun body(patch:Map<String,JsonElement> = emptyMap())=JsonObject(Json.parseToJsonElement("""{"account_name":"账户","amount":"-10","currency":"CNY","occurred_at":"2026-08-04T12:00:00+00:00","record_type":"consumption","record_subtype":"not_applicable","note":""}""").jsonObject+patch)
    private fun counts(db:Database,workspace:String)=db.read {c ->listOf("cash_transactions","cash_transaction_components").map {table ->
        c.prepareStatement("SELECT count(*) FROM $table").use {it.executeQuery().use {r ->assertTrue(r.next());r.getLong(1)}}
    }}
    private suspend fun ApplicationTestBuilder.postBody(body:JsonObject)=client.post("/api/v1/cash-records") {
        val encoded=buildString {for(char in body.toString()) {
            if(char.isSurrogate()) append("\\u"+char.code.toString(16).padStart(4,'0')) else append(char)
        }}
        bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(encoded)
    }
    @Test fun serverFieldsAreRejectedBeforeSavingAnything()=inWorkspace {db,workspace ->
        val initial=counts(db,workspace)
        val fields=mapOf("relation_metadata" to buildJsonObject {put("forged","private-input")},"manual_overrides" to Json.parseToJsonElement("""{"note":{"value":"forged"}}"""),
            "state" to JsonPrimitive("draft"),"source_type" to JsonPrimitive("fixture"),"record_id" to JsonPrimitive("forged"),"source_payload" to buildJsonObject {put("备注","private-input")},
            "workspace_id" to JsonPrimitive("other"),"row_revision" to JsonPrimitive(99),"deleted_at" to JsonPrimitive("2026-08-04"),"id" to JsonPrimitive(999),
            "components" to Json.parseToJsonElement("""[{"account_name":"账户","amount":"-10","metadata_json":{"forged":true}}]"""))
        for((key,value) in fields) {val response=postBody(body(mapOf(key to value)));assertEquals(422,response.status.value,key)
            assertFalse("private-input" in response.bodyAsText());assertFalse("forged" in response.bodyAsText());assertEquals(initial,counts(db,workspace),key)}
    }
    @Test fun directApplicationCannotBypassPublicFields()=inWorkspace {db,workspace ->
        val initial=counts(db,workspace);val service=ManualCashService(db)
        val patches=listOf(mapOf("manual_overrides" to JsonObject(emptyMap())),mapOf("relation_metadata" to JsonObject(emptyMap())))+
            listOf("metadata_json","source_key","component_id").map {key ->mapOf("components" to JsonArray(listOf(buildJsonObject {
                put("account_name","账户");put("amount","-10");put(key,if(key=="metadata_json") buildJsonObject {put("forged",true)} else JsonPrimitive(if(key=="component_id") "999" else "forged"))
            })))}
        for(patch in patches) {assertFailsWith<ManualCashInputFailure> {service.create("synthetic-admin",body(patch))};assertEquals(initial,counts(db,workspace))}
    }
    @Test fun explicitEmptyNoteAndCategoryDeriveProtectionAndOnlyPublicComponentFields()=inWorkspace {db,workspace ->
        val response=postBody(body(mapOf("category_id" to JsonNull,"components" to Json.parseToJsonElement("""[{"account_name":"账户","amount":"-10","currency":"CNY","label":"现金"}]"""))))
        assertEquals(201,response.status.value,response.bodyAsText());val id=Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("record").jsonObject.getValue("id").jsonPrimitive.long
        db.read {c ->c.prepareStatement("SELECT state,source_payload,relation_metadata,manual_overrides FROM cash_transactions WHERE workspace_id=? AND id=?").use {
            it.setString(1,workspace);it.setLong(2,id);it.executeQuery().use {r ->assertTrue(r.next());assertEquals("posted",r.getString(1));assertEquals(JsonNull,r.getString(2)?.let(Json::parseToJsonElement) ?: JsonNull)
                assertFalse(pythonTruthy(r.getString(3)?.let(Json::parseToJsonElement)));assertEquals(Json.parseToJsonElement("""{"note":{"value":""},"category_id":{"value":null}}"""),Json.parseToJsonElement(r.getString(4)))}}
            c.prepareStatement("SELECT cash_transaction_id,label,source_key,metadata_json FROM cash_transaction_components WHERE workspace_id=? AND cash_transaction_id=?").use {
                it.setString(1,workspace);it.setLong(2,id);it.executeQuery().use {r ->assertTrue(r.next());assertEquals(id,r.getLong(1));assertEquals("现金",r.getString(2));assertEquals("",r.getString(3));assertEquals(JsonObject(emptyMap()),Json.parseToJsonElement(r.getString(4)));assertFalse(r.next())}}
        }
    }
    @Test fun bodyIsStrictAndBoundedWithoutSavingAnything()=inWorkspace {db,workspace ->
        val initial=counts(db,workspace)
        for(text in listOf("""{"amount":"-1","amount":"-2"}""","""{"amount":1e999}""","[]","{\"note\":\""+"x".repeat(65536)+"\"}")) {
            val response=client.post("/api/v1/cash-records") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(text)}
            assertEquals(422,response.status.value,response.bodyAsText());assertEquals(initial,counts(db,workspace))
        }
    }
    @Test fun omittedNoteAndCategoryDoNotCreateProtection()=inWorkspace {db,workspace ->
        val response=postBody(JsonObject(body()-"note"));assertEquals(201,response.status.value,response.bodyAsText())
        val id=Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("record").jsonObject.getValue("id").jsonPrimitive.long
        db.read {c ->c.prepareStatement("SELECT manual_overrides FROM cash_transactions WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setLong(2,id);it.executeQuery().use {r ->assertTrue(r.next());assertEquals(JsonObject(emptyMap()),Json.parseToJsonElement(r.getString(1)))}}}
    }
    @Test fun invalidEncodingAndDeepJsonRemainSafeProtocolErrors()=inWorkspace {db,workspace ->
        val initial=counts(db,workspace)
        for(bytes in listOf(byteArrayOf(123,34,110,111,116,101,34,58,34,0xff.toByte(),34,125),("[".repeat(2000)+"0"+"]".repeat(2000)).toByteArray())) {
            val response=client.post("/api/v1/cash-records") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(bytes)}
            assertEquals(422,response.status.value,response.bodyAsText());assertEquals("请检查填写内容后重试。",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
            assertEquals(initial,counts(db,workspace))
        }
    }
}
