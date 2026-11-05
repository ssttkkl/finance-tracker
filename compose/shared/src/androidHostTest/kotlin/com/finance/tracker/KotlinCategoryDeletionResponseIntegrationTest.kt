package com.finance.tracker

import com.finance.tracker.data.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlinx.serialization.decodeFromString
import org.junit.Assume.assumeTrue

class KotlinCategoryDeletionResponseIntegrationTest {
    @Test fun actualKtorImpactAndDeleteUseTheExistingComposeConfirmationProtocol() = runTest {
        val root=System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")
        assumeTrue("Requires actual captured anonymous Ktor responses; skip is not acceptance",root!=null)
        val captured=Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(root),"category-deletion-responses.json"))).jsonObject
        assertTrue(captured.getValue("backend").jsonPrimitive.content in setOf("sqlite","postgresql"))
        val responses=captured.getValue("responses").jsonArray.map {it.jsonObject}
        val deletion=responses.single {it.getValue("method").jsonPrimitive.content=="DELETE"}
        val expectedResult=Json.decodeFromString<CashCategoryDeleteResultDto>(deletion.getValue("body").jsonPrimitive.content)
        val impact=responses.first {it.getValue("path").jsonPrimitive.content=="/api/v1/cash-categories/${expectedResult.categoryId}/deletion-impact"}
        val expectedImpact=Json.decodeFromString<CashCategoryDeleteImpactDto>(impact.getValue("body").jsonPrimitive.content)
        var count=0
        val engine=MockEngine {request ->
            count++
            val body=if(request.method==HttpMethod.Get) {
                assertEquals(impact.getValue("path").jsonPrimitive.content,request.url.encodedPath);impact.getValue("body").jsonPrimitive.content
            } else {
                assertEquals(HttpMethod.Delete,request.method);assertEquals(deletion.getValue("path").jsonPrimitive.content,request.url.encodedPath)
                assertEquals(buildJsonObject {put("expected_revision",expectedImpact.revision);put("expected_category_revision",expectedImpact.categoryRevision);put("expected_usage_count",expectedImpact.directUsageCount);put("confirmed",true)},Json.parseToJsonElement((request.body as TextContent).text))
                deletion.getValue("body").jsonPrimitive.content
            }
            respond(body,headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        val client=FinanceApiClient({"https://api.example.invalid"},CategoryTokenStore(),engine)
        try {
            val actual=client.fetchCashCategoryDeletionImpact(expectedResult.categoryId);assertEquals(expectedImpact,actual)
            assertEquals(1,actual.directUsageCount);assertEquals(0,actual.childCount)
            assertEquals(expectedResult,client.deleteCashCategory(actual.categoryId,actual));assertEquals(2,count)
            assertEquals(actual.revision+1,expectedResult.revision);assertEquals(actual.directUsageCount,expectedResult.clearedTransactionCount)
        } finally {client.close()}
    }
}
