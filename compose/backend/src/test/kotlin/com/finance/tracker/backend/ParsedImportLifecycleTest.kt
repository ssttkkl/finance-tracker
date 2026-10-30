package com.finance.tracker.backend

import io.ktor.server.testing.testApplication
import kotlin.test.*

class ParsedImportLifecycleTest {
    @Test fun runtimeStartsRequiredCleanupAndStopsOnShutdown() {
        val runtime = ParsedImportSettings.load(mapOf("FT_IMPORT_STAGING_BACKEND" to "memory", "FT_IMPORT_STAGING_ALLOW_MEMORY" to "1")).build()
        testApplication {
            application { installParsedImportRuntime(runtime) }
            startApplication()
            assertTrue(runtime.cleanup.running); assertEquals(true, runtime.cleanup.healthy)
        }
        assertFalse(runtime.cleanup.running)
    }

    @Test fun runtimeRefusesUnavailableCleanupAndClosesResource() {
        var closed = 0
        val cleanup = ParsedImportCleanup({ _, _, _ -> error("provider secret") })
        val runtime = ParsedImportRuntime(InMemoryParsedImportStore(), cleanup, AutoCloseable { closed++; error("close provider secret") })
        val error = assertFails {
            testApplication {
                application { installParsedImportRuntime(runtime) }
                startApplication()
                fail("startup must refuse unavailable cleanup")
            }
        }
        val failures = generateSequence(error) { it.cause }.toList()
        assertTrue(failures.any { it is ParsedImportStorageUnavailable })
        assertTrue(failures.none { it.message.orEmpty().contains("provider secret") })
        runtime.close(); runtime.close()
        assertFalse(cleanup.running); assertEquals(1, closed)
    }
}
