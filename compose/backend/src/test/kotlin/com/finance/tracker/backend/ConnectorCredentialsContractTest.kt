package com.finance.tracker.backend

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*
import kotlinx.serialization.json.*

class ConnectorCredentialsContractTest {
    @Test fun actualPythonCredentialResultsAndFileEffectsMatch() {
        val cases = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CREDENTIALS_CONTRACT"))).readText()).jsonArray
        assertEquals(181, cases.size)
        val differences = mutableListOf<String>()
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject; val directory = Files.createTempDirectory("ft-credentials-contract-")
            try {
                case["text"]?.takeUnless { it == JsonNull }?.let { directory.resolve("credentials.yaml").writeText(it.jsonPrimitive.content) }
                val loader = ConnectorCredentials(mapOf("FT_CREDENTIALS_DIR" to directory.toString()))
                val actual = try {
                    val provider = case.getValue("provider").jsonPrimitive.content
                    val value = if (provider == "polymarket") loader.polymarket() else loader.exchange(provider)
                    buildJsonObject { put("value", JsonObject(value.mapValues { JsonPrimitive(it.value) })) }
                } catch (failure: IllegalArgumentException) { buildJsonObject { put("error", failure.message!!.replace(directory.toString(), "<DIR>")) } }
                if (case.getValue("expected") != actual) differences += "$index ${case["text"]}: expected=${case["expected"]}, actual=$actual"
                assertEquals(case["gitignore"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content, directory.resolve(".gitignore").takeIf(Files::exists)?.readText(), "$index gitignore")
            } finally { directory.toFile().deleteRecursively() }
        }
        assertEquals(emptyList(), differences)
    }
}
