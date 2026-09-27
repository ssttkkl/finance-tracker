package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionMapperTest {
    @Test
    fun mapsTransportSessionAndRolesToPureDomainModels() {
        val dto = SessionDto(
            user = UserDto("owner@example.com"),
            activeWorkspaceId = "workspace-1",
            workspaces = listOf(
                WorkspaceDto("workspace-1", "Home", Role.ADMIN),
                WorkspaceDto("workspace-2", "Travel", Role.VIEWER),
            ),
        )

        assertEquals(
            Session(
                user = User("owner@example.com"),
                activeWorkspaceId = "workspace-1",
                workspaces = listOf(
                    Workspace("workspace-1", "Home", WorkspaceRole.ADMIN),
                    Workspace("workspace-2", "Travel", WorkspaceRole.VIEWER),
                ),
            ),
            dto.toDomain(),
        )
    }

    @Test
    fun mapsDomainRolesBackToTransportEnumWithoutLocalizedLabels() {
        assertEquals(Role.ADMIN, WorkspaceRole.ADMIN.toData())
        assertEquals(Role.EDITOR, WorkspaceRole.EDITOR.toData())
        assertEquals(Role.VIEWER, WorkspaceRole.VIEWER.toData())
    }

    @Test
    fun mapsApiFailuresWithoutCarryingImportTokensOrRawResponseText() {
        val failure = ApiFailure("invalid_credentials", 401, importToken = "sensitive-import-token")
        val mapped = failure.toDomainFailure()

        assertEquals("invalid_credentials", mapped.code)
        assertEquals(401, mapped.status)
        assertEquals(FailureCategory.RECOVERABLE, mapped.category)
        assertEquals(null, mapped.importToken)
        assertEquals("invalid_credentials", mapped.message)
    }

    @Test
    fun carriesOnlyImportResumeTokensAcrossTheDataBoundary() {
        val mapped = ApiFailure("import_password_required", 400, importToken = "resume-token").toDomainFailure()
        assertEquals("resume-token", mapped.importToken)
    }

    @Test
    fun classifiesNetworkAndUnexpectedFailuresWithoutCopyingExceptionMessages() {
        val network = FakeSocketTimeoutException("person@example.com timeout bearer=secret").toDomainFailure()
        val unexpected = IllegalStateException("statement amount=123.45 private body").toDomainFailure()

        assertEquals("network_unavailable", network.code)
        assertEquals(FailureCategory.RECOVERABLE, network.category)
        assertEquals("network_unavailable", network.message)
        assertEquals("unknown_error", unexpected.code)
        assertEquals(FailureCategory.UNKNOWN, unexpected.category)
        assertEquals("unknown_error", unexpected.message)
    }

    @Test
    fun keepsServerStatusFailuresRecoverableAndMapsInvalidOriginToUnknown() {
        val serverFailure = ApiFailure("api_request_failed", 503).toDomainFailure()
        val invalidOrigin = ApiFailure("api_origin_invalid", 0).toDomainFailure()

        assertEquals(FailureCategory.RECOVERABLE, serverFailure.category)
        assertEquals(503, serverFailure.status)
        assertEquals("api_request_failed", serverFailure.code)
        assertEquals(FailureCategory.UNKNOWN, invalidOrigin.category)
        assertEquals("api_origin_invalid", invalidOrigin.code)
    }

    private class FakeSocketTimeoutException(message: String) : Exception(message)
}
