package com.finance.tracker.backend

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*
import kotlinx.serialization.json.*

class InvestmentPdfContractTest {
    @Test fun actualPdfBytesProduceOriginalBrokerRowsIdentitiesAndEvents() {
        val path = Path.of(requireNotNull(System.getenv("FT_KOTLIN_PDF_TOOL_CONTRACT")))
        val cases = Json.parseToJsonElement(Files.readString(path)).jsonObject.getValue("cases").jsonArray
        assertEquals(4, cases.size)
        for (raw in cases) {
            val c = raw.jsonObject; val name = c.getValue("file").jsonPrimitive.content
            val content = Files.readAllBytes(path.parent.resolve(name))
            assertEquals(c.getValue("sha256").jsonPrimitive.content, MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) })
            val text = PdfProcessClient().extractText(content, c["password"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content)
            val account = c.getValue("account").jsonPrimitive.content; val currency = c.getValue("currency").jsonPrimitive.content
            val actual = if (c.getValue("broker").jsonPrimitive.content == "dfzq") {
                JsonArray(DfzqImporter.parse(text.lines()).map { row -> buildJsonObject {
                    put("row", JsonObject(row)); put("identity", DfzqImporter.identity(row)); put("event", DfzqImporter.event(row, account, currency))
                } })
            } else {
                JsonArray(UsmartImporter.parse(text).map { row -> buildJsonObject {
                    put("row", JsonObject(row)); put("identity", UsmartImporter.identity(row)); put("event", UsmartImporter.event(row, account, currency))
                } })
            }
            assertEquals(c.getValue("expected"), actual, name)
        }
    }
}
