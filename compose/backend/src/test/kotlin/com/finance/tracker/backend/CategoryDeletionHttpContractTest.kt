package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.time.*
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CategoryDeletionHttpContractTest {
    @Test fun deletionHttpAndAllFinancialRowsMatchPython() = testApplication {
        val root=Path.of(requireNotNull(System.getenv("FT_KOTLIN_CATEGORY_DELETION_CONTRACT_DIR")))
        val config=Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val seed=Json.parseToJsonElement(root.resolve("deletion-seed.json").readText()).jsonObject
        application {financeModule(db,categories=CategoryService(db,now={deletionTime}))}
        val captured=mutableListOf<JsonElement>()
        restoreContractTables(db,seed)
        try {
            for(raw in Json.parseToJsonElement(root.resolve("deletions.json").readText()).jsonArray) {
                val case=raw.jsonObject
                val response=client.request(case.getValue("path").jsonPrimitive.content) {
                    method=HttpMethod.parse(case.getValue("method").jsonPrimitive.content)
                    case.getValue("headers").jsonObject.forEach {(key,value)->header(key,value.jsonPrimitive.content)}
                    if(case["request"]!=JsonNull) {contentType(ContentType.Application.Json);setBody(case.getValue("request").toString())}
                }
                val context=case.getValue("method").toString()+case.getValue("path")+case["request"]
                assertEquals(case.getValue("status").jsonPrimitive.int,response.status.value,context+response.bodyAsText())
                assertEquals(case["body"],Json.parseToJsonElement(response.bodyAsText()),context)
                compareContractTables(db,case.getValue("effects").jsonObject,context)
                if(response.status.value==200) captured+=buildJsonObject {put("method",case.getValue("method"));put("path",case.getValue("path"));put("body",response.bodyAsText())}
            }
        } finally {restoreContractTables(db,seed)}
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let {
            val out=Path.of(it);require(out.isAbsolute);Files.createDirectories(out)
            Files.writeString(out.resolve("category-deletion-responses.json"),buildJsonObject {put("backend",if(db.sqlite) "sqlite" else "postgresql");put("responses",JsonArray(captured))}.toString())
        }
    }

}
