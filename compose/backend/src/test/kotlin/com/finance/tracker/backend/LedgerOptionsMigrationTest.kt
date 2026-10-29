package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class LedgerOptionsMigrationTest {
    private fun database(): Database {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    @Test fun cataloguePreservesEveryOriginalLabelOrderAndValidSubtypeForAllReaders() = testApplication {
        val db=database();application {financeModule(db)}
        val expected=Json.parseToJsonElement(javaClass.getResource("/python-ledger-options.json")!!.readText()).jsonObject
        val captures=mutableListOf<JsonElement>()
        val before=projectionRowCounts(db)
        for(token in listOf("synthetic-admin","synthetic-viewer","synthetic-new-directory")) {
            val response=client.get("/api/v1/cash-ledger/options") {bearerAuth(token)}
            assertEquals(200,response.status.value,response.bodyAsText());assertEquals(expected,Json.parseToJsonElement(response.bodyAsText()))
            captures+=buildJsonObject {put("token_role",if(token=="synthetic-viewer") "viewer" else "admin");put("body",response.bodyAsText())}
        }
        try {
            setAccountTestRole(db,"fixture-a","editor")
            val response=client.get("/api/v1/cash-ledger/options") {bearerAuth("synthetic-admin")}
            assertEquals(200,response.status.value);assertEquals(expected,Json.parseToJsonElement(response.bodyAsText()))
            captures+=buildJsonObject {put("token_role","editor");put("body",response.bodyAsText())}
        } finally {setAccountTestRole(db,"fixture-a","admin")}
        assertEquals(cashRecordTypes.toList(),expected.getValue("record_types").jsonArray.map {it.jsonObject.getValue("value").jsonPrimitive.content})
        for(raw in expected.getValue("record_types").jsonArray) {
            val option=raw.jsonObject;val type=option.getValue("value").jsonPrimitive.content
            val subtypes=option.getValue("subtypes").jsonArray.map {it.jsonObject.getValue("value").jsonPrimitive.content}
            assertEquals(cashRecordSubtypes.filter {validCashRecordPair(type,it)},subtypes)
        }
        assertEquals(before,projectionRowCounts(db))
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let {
            val output=Path.of(it);require(output.isAbsolute);Files.createDirectories(output)
            Files.writeString(output.resolve("ledger-options-responses.json"),buildJsonObject {put("responses",JsonArray(captures))}.toString())
        }
    }
    @Test fun noSessionOrCurrentMembershipCannotReadLedgerOptions() = testApplication {
        application {financeModule(database())}
        for((token,status) in listOf(null to 401,"synthetic-expired" to 401,"synthetic-empty" to 403,"synthetic-removed" to 403))
            assertEquals(status,client.get("/api/v1/cash-ledger/options") {token?.let {bearerAuth(it)}}.status.value)
    }    @Test fun optionsDoNotRetainMutableListsAcrossCalls() {
        val service=LedgerOptionsService(database())
        val first=service.options("synthetic-admin")
        val records=first.recordTypes as MutableList
        val original=records.toList()
        try {records.clear();assertEquals(17,service.options("synthetic-admin").recordTypes.size)} finally {records.addAll(original)}
        val subtypes=first.recordTypes.first().subtypes as MutableList
        val originalSubtypes=subtypes.toList()
        try {subtypes.clear();assertEquals(1,service.options("synthetic-admin").recordTypes.first().subtypes.size)} finally {subtypes.addAll(originalSubtypes)}
    }
    private fun projectionRowCounts(db: Database)=db.read {c ->listOf("cash_projection_states","cash_projection_datasets").map {table ->
        c.createStatement().use {statement ->statement.executeQuery("SELECT count(*) FROM $table").use {rows ->check(rows.next());rows.getLong(1)}}
    }}

}
