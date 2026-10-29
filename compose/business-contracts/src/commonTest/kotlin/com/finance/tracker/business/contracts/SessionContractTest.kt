package com.finance.tracker.business.contracts

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionContractTest {
    @Test fun sessionPreservesExplicitNullAndEmptyWorkspaceList() {
        val json = Json { encodeDefaults = true; explicitNulls = true }
        val wire = """{"user":{"email":"example@example.invalid"},"active_workspace_id":null,"workspaces":[]}"""
        assertEquals(wire, json.encodeToString(SessionDto(UserDto("example@example.invalid"))))
        assertEquals(SessionDto(UserDto("example@example.invalid")), json.decodeFromString<SessionDto>(wire))
    }

    @Test fun roleWireValuesAndWriteEligibilityStayCompatible() {
        assertFalse(Json.decodeFromString<Role>("\"viewer\"").canWrite)
        assertTrue(Json.decodeFromString<Role>("\"editor\"").canWrite)
        assertEquals("\"admin\"", Json.encodeToString(Role.ADMIN))
    }
}
