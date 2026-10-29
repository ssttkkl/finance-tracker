package com.finance.tracker.backend

import kotlinx.serialization.json.*
import kotlin.test.*

class PythonStringGoldenTest {
    @Test fun nonCredentialFieldsKeepTheExistingPythonTextConversion() {
        for (value in Json.parseToJsonElement(pythonStringGoldens).jsonArray) {
            val case = value.jsonObject
            val raw = case.getValue("json").jsonPrimitive.content
            assertEquals(case.getValue("expected").jsonPrimitive.content, pythonString(Json.parseToJsonElement(raw)), raw)
        }
    }
}
