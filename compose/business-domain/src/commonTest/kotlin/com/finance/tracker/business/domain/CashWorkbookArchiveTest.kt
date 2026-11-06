package com.finance.tracker.business.domain

import kotlin.io.encoding.Base64
import kotlin.test.*

class CashWorkbookArchiveTest {
    // Python zipfile-generated STORED, DEFLATED and streaming-descriptor archives.
    private val archives = listOf(
        "UEsDBBQAAAAAAAAAIVzCQSQ1AwAAAAMAAAAKAAAAdW51c2VkLnhtbGFiY1BLAwQUAAAAAAAAACFcHq9fKgwAAAAMAAAAGAAAAHhsL3dvcmtzaGVldHMvc2hlZXQxLnhtbDx3b3Jrc2hlZXQvPlBLAQIUAxQAAAAAAAAAIVzCQSQ1AwAAAAMAAAAKAAAAAAAAAAAAAACAAQAAAAB1bnVzZWQueG1sUEsBAhQDFAAAAAAAAAAhXB6vXyoMAAAADAAAABgAAAAAAAAAAAAAAIABKwAAAHhsL3dvcmtzaGVldHMvc2hlZXQxLnhtbFBLBQYAAAAAAgACAH4AAABtAAAAAAA=",
        "UEsDBBQAAAAIAAAAIVzCQSQ1BQAAAAMAAAAKAAAAdW51c2VkLnhtbEtMSgYAUEsDBBQAAAAIAAAAIVwer18qDgAAAAwAAAAYAAAAeGwvd29ya3NoZWV0cy9zaGVldDEueG1ssynPL8ouzkhNLdG3AwBQSwECFAMUAAAACAAAACFcwkEkNQUAAAADAAAACgAAAAAAAAAAAAAAgAEAAAAAdW51c2VkLnhtbFBLAQIUAxQAAAAIAAAAIVwer18qDgAAAAwAAAAYAAAAAAAAAAAAAACAAS0AAAB4bC93b3Jrc2hlZXRzL3NoZWV0MS54bWxQSwUGAAAAAAIAAgB+AAAAcQAAAAAA",
        "UEsDBBQACAAIAAAAIVwAAAAAAAAAAAAAAAAKAAAAdW51c2VkLnhtbEtMSgYAUEsHCMJBJDUFAAAAAwAAAFBLAwQUAAgACAAAACFcAAAAAAAAAAAAAAAAGAAAAHhsL3dvcmtzaGVldHMvc2hlZXQxLnhtbLMpzy/KLs5ITS3RtwMAUEsHCB6vXyoOAAAADAAAAFBLAQIUAxQACAAIAAAAIVzCQSQ1BQAAAAMAAAAKAAAAAAAAAAAAAACAAQAAAAB1bnVzZWQueG1sUEsBAhQDFAAIAAgAAAAhXB6vXyoOAAAADAAAABgAAAAAAAAAAAAAAIABPQAAAHhsL3dvcmtzaGVldHMvc2hlZXQxLnhtbFBLBQYAAAAAAgACAH4AAACRAAAAAAA="
    )

    @Test fun storedDeflatedAndDescriptorMembersReachTheWorksheetCallbackUnchanged() {
        for (encoded in archives) {
            val seen = mutableListOf<String>()
            preflightCashWorkbookArchive(Base64.decode(encoded)) { seen += it.decodeToString() }
            assertEquals(listOf("<worksheet/>"), seen)
        }
    }

    @Test fun aggregateBudgetIncludesUnretainedMembersAndStopsBeforePublishingTheWorksheet() {
        for (encoded in archives) {
            val seen = mutableListOf<String>()
            assertEquals("statement_rows_too_large", assertFailsWith<StatementDocumentFailure> {
                scanCashWorkbookArchive(Base64.decode(encoded), 14) { seen += it.decodeToString() }
            }.code)
            assertTrue(seen.isEmpty())
            scanCashWorkbookArchive(Base64.decode(encoded), 15) { seen += it.decodeToString() }
            assertEquals(listOf("<worksheet/>"), seen)
        }
    }

    @Test fun storedCrcCorruptionCannotBeSkippedForAnUnusedMember() {
        val bytes = Base64.decode(archives.first())
        val offset = 30 + "unused.xml".length
        bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
        assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure> {
            preflightCashWorkbookArchive(bytes) { fail("worksheet must not be reached") }
        }.code)
    }

    @Test fun absentTruncatedAndMismatchedCentralDirectoriesAreRejected() {
        for (encoded in archives) {
            val original = Base64.decode(encoded)
            val central = original.indices.first { i -> i + 3 < original.size &&
                original[i] == 0x50.toByte() && original[i+1] == 0x4b.toByte() &&
                original[i+2] == 1.toByte() && original[i+3] == 2.toByte() }
            val wrongName = original.copyOf().also { it[central + 46] = 'X'.code.toByte() }
            for (bytes in listOf(original.copyOf(central), original.copyOf(original.size - 1), wrongName)) {
                assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure> {
                    preflightCashWorkbookArchive(bytes) { }
                }.code)
            }
        }
    }

    @Test fun callbackFailureKeepsItsOriginalBusinessError() {
        assertEquals("statement_header_invalid", assertFailsWith<StatementDocumentFailure> {
            preflightCashWorkbookArchive(Base64.decode(archives.first())) {
                throw StatementDocumentFailure("statement_header_invalid")
            }
        }.code)
    }

    @Test fun xmlPreflightVisitsEveryMemberWithoutPathAssumptions() {
        for (encoded in archives) {
            val seen = mutableListOf<String>()
            preflightCashWorkbookXmlArchive(Base64.decode(encoded)) { seen += it.decodeToString() }
            assertEquals(listOf("abc", "<worksheet/>"), seen)
        }
    }
}
