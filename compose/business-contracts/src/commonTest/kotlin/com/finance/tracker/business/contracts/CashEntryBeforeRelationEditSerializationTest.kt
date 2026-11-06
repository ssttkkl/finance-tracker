package com.finance.tracker.business.contracts

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*

class CashEntryBeforeRelationEditSerializationTest {
    @Test fun theIndependentPatchRetainsClearsAndARealOldEntryDependency() {
        val old = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("entry-old"), "9007199254740993")
        val patch = buildJsonObject { put("note", "C"); put("category_id", JsonNull) }
        val edit = CashEntryBeforeRelationEditDto(old, patch)
        val encoded = Json.encodeToString(CashEntryBeforeRelationEditDto.serializer(), edit)
        assertEquals(edit, Json.decodeFromString<CashEntryBeforeRelationEditDto>(encoded))
        assertEquals(JsonNull, Json.parseToJsonElement(encoded).jsonObject.getValue("patch").jsonObject["category_id"])
        assertFalse("counterparty" in edit.patch)
        val absent = CashEntryBeforeRelationEditDto(null, buildJsonObject { put("note", "") })
        assertEquals(absent, Json.decodeFromString<CashEntryBeforeRelationEditDto>(Json.encodeToString(CashEntryBeforeRelationEditDto.serializer(), absent)))
    }

    @Test fun absenceOfTheOldEntryIsAnExplicitObservationRatherThanAnOmittedDependency() {
        for (text in listOf("""{"patch":{"note":"C"}}""", """{"entry":null}""",
            """{"entry":null,"patch":null}""", """{"entry":null,"patch":[],"extra":true}""")) {
            assertFailsWith<SerializationException> { Json.decodeFromString<CashEntryBeforeRelationEditDto>(text) }
        }
    }
}
