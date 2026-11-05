package com.finance.tracker.backend

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts

class PdfProcessTest {
    private fun pdf(password: String? = null, pages: Int = 1): ByteArray = PDDocument().use { doc ->
        repeat(pages) {
            val page = PDPage(); doc.addPage(page)
            PDPageContentStream(doc, page).use { stream ->
                stream.beginText(); stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                stream.newLineAtOffset(72f, 700f); stream.showText("Synthetic broker statement"); stream.newLineAtOffset(0f, -20f)
                stream.showText("USD 100.00"); stream.endText()
            }
        }
        if (password != null) doc.protect(StandardProtectionPolicy("owner-synthetic", password, AccessPermission()))
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }
    private fun client(mode: String, admission: PdfAdmission = PdfAdmission(2), timeout: Long = 3000) =
        PdfProcessClient(admission, PdfWorkerLaunch(mainClass = "com.finance.tracker.backend.PdfFaultWorker", arguments = listOf(mode)), timeoutMillis = timeout)

    @Test fun extractsTextAndClassifiesPasswordWithoutExternalTools() {
        val client = PdfProcessClient()
        val expected = "Synthetic broker statement\nUSD 100.00"
        assertEquals(expected, client.extractText(pdf()).trim())
        val encrypted = pdf("synthetic-only")
        assertTrue(assertFailsWith<StatementPdfPasswordFailure> { client.extractText(encrypted) }.required)
        assertFalse(assertFailsWith<StatementPdfPasswordFailure> { client.extractText(encrypted, "wrong") }.required)
        assertEquals(expected, client.extractText(encrypted, "synthetic-only").trim())
        assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure> { client.extractText("bad pdf".toByteArray()) }.code)
    }
    @Test fun actualWorkerIsFreshHasBoundedHeapAndNoInheritedSecrets() {
        val a = Json.parseToJsonElement(client("inspect").extractText(pdf(), "private-password")).jsonObject
        val b = Json.parseToJsonElement(client("inspect").extractText(pdf())).jsonObject
        assertNotEquals(ProcessHandle.current().pid(), a.getValue("pid").jsonPrimitive.long)
        assertNotEquals(a.getValue("pid"), b.getValue("pid"))
        assertTrue(a.getValue("heap").jsonPrimitive.long <= 256L * 1024 * 1024)
        assertEquals(JsonObject(emptyMap()), JsonObject(a.getValue("environment").jsonObject.filterKeys { it != "__CF_USER_TEXT_ENCODING" }))
        assertEquals("synthetic-env-only", System.getenv("FT_PDF_TEST_SECRET"))
        assertEquals("private-password", a.getValue("password").jsonPrimitive.content)
        assertEquals(JsonNull, b.getValue("password"))
        assertFalse(a.getValue("arguments").toString().contains("private-password"))
        assertTrue(a.getValue("arguments").toString().contains("-XX:-CreateCoredumpOnCrash"))
        assertFalse(java.nio.file.Files.exists(Path.of(a.getValue("directory").jsonPrimitive.content)))
        assertFalse(ProcessHandle.of(a.getValue("pid").jsonPrimitive.long).map { it.isAlive }.orElse(false))
    }
    @Test fun limitsRejectInputPagesCharactersAndText() {
        val client = PdfProcessClient(); val content = pdf(pages = 2)
        for (limits in listOf(PdfReadLimits(maxInputBytes = 1), PdfReadLimits(maxPages = 1), PdfReadLimits(maxChars = 1), PdfReadLimits(maxTextBytes = 1)))
            assertFailsWith<StatementDocumentFailure> { client.extractText(content, limits = limits) }
    }
    @Test fun timeoutCrashHeapFailureAndBadProtocolReleaseAdmission() {
        val admission = PdfAdmission(1)
        for (mode in listOf("hang", "crash", "oom", "oversized", "truncated", "trailing", "partial_then_hang")) {
            val start = System.nanoTime()
            val error = assertFailsWith<StatementDocumentFailure>(mode) { client(mode, admission, 1800).extractText(pdf()) }
            assertEquals(if (mode in setOf("hang", "partial_then_hang")) "statement_parser_timeout" else "statement_unreadable", error.code, mode)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000, mode)
            assertEquals("ok", client("ok", admission).extractText(pdf()), mode)
        }
    }
    @Test fun timeoutIncludesBlockedInputAndStartupFailureReleasesPermit() {
        val admission = PdfAdmission(1)
        val content = ByteArray(2 * 1024 * 1024)
        val start = System.nanoTime()
        assertEquals("statement_parser_timeout", assertFailsWith<StatementDocumentFailure> { client("no_read", admission, 700).extractText(content) }.code)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000)
        assertEquals("ok", client("ok", admission).extractText(pdf()))
        assertFailsWith<StatementDocumentFailure> { PdfProcessClient(admission, PdfWorkerLaunch(mainClass = "no.such.Worker"), 1000).extractText(pdf()) }
        assertEquals(0, admission.active)
        assertEquals("ok", client("ok", admission).extractText(pdf()))
    }
    @Test fun malformedSuccessAndOversizedTextAreRejectedSafely() {
        for (mode in listOf("bad_json", "no_text", "not_string", "extra_fields", "text_over_limit", "deep_json"))
            assertFailsWith<StatementDocumentFailure>(mode) { client(mode).extractText(pdf(), limits = PdfReadLimits(maxTextBytes = 4)) }
    }
    @Test fun badUnicodePasswordDoesNotAcquirePermit() {
        val admission = PdfAdmission(1)
        assertEquals("statement_input_invalid", assertFailsWith<StatementDocumentFailure> { client("ok", admission).extractText(pdf(), "\uD800") }.code)
        assertEquals(0, admission.active)
    }
    @Test fun concurrentAdmissionRejectsBeforeLaunchingAndRecovers() {
        val admission = PdfAdmission(1); val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit { assertFailsWith<StatementDocumentFailure> { client("hang", admission, 1600).extractText(pdf()) } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (admission.active == 0 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(1, admission.active)
            assertEquals("statement_parser_busy", assertFailsWith<StatementDocumentFailure> { client("ok", admission).extractText(pdf()) }.code)
            first.get(5, TimeUnit.SECONDS)
            assertEquals("ok", client("ok", admission).extractText(pdf()))
            assertEquals(0, admission.active)
        } finally { executor.shutdownNow() }
    }
    @Test fun icbcMalformedDocumentReturnsSafeError() {
        assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure> {
            client("bad_document").readIcbc(pdf(), null, null, IcbcPdfDocumentReader.IcbcPdfLimits())
        }.code)
    }
    @Test fun xlsWorkerProgramFaultKeepsSafeCauseFramesAndNeverBecomesAFileRejection() {
        val admission = PdfAdmission(1)
        val failure = assertFailsWith<CashStatementParseInternalFailure> { client("program_fault", admission).readXls(byteArrayOf(1)) }
        val remote = assertIs<CashStatementWorkerFailure>(failure.cause)
        val trace = remote.trace
        assertEquals(listOf("java.lang.AssertionError", "java.lang.IllegalArgumentException"),
            trace.getValue("causes").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertTrue(trace.getValue("causes").jsonArray.all { it.jsonObject.getValue("frames").jsonArray.isNotEmpty() })
        assertFalse(trace.toString().contains("private-body"))
        assertEquals(0, admission.active)
        for (mode in listOf("crash", "bad_json", "bad_document", "bad_fault", "password_required", "password_invalid")) {
            assertFailsWith<CashStatementParseInternalFailure>(mode) { client(mode, admission).readXls(byteArrayOf(1)) }
            assertEquals(0, admission.active)
        }
    }
    @Test fun headerRejectedBeforeAdmissionOrEncoding() {
        val admission = PdfAdmission(1); admission.acquire()
        try {
            assertEquals("statement_input_invalid", assertFailsWith<StatementDocumentFailure> { client("ok", admission).extractText(pdf(), "x".repeat(100000) + "\uD800") }.code)
        } finally { admission.release() }
        assertEquals(0, admission.active)
    }
    @Test fun interruptionTerminatesWorkerAndRestoresPermit() {
        val admission = PdfAdmission(1)
        val marker = java.nio.file.Files.createTempFile("ft-pdf-ready-", "")
        val observed = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                PdfProcessClient(admission, PdfWorkerLaunch(mainClass = "com.finance.tracker.backend.PdfFaultWorker", arguments = listOf("ready_hang", marker.toString())), 30000).extractText(pdf())
                observed.set(AssertionError("request unexpectedly succeeded"))
            } catch (error: Throwable) { observed.set(error) }
        }
        try {
            thread.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (java.nio.file.Files.size(marker) == 0L && System.nanoTime() < deadline) Thread.sleep(5)
            val pid = java.nio.file.Files.readString(marker).toLong()
            assertTrue(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            thread.interrupt(); thread.join(3000)
            assertFalse(thread.isAlive); assertIs<InterruptedException>(observed.get()); assertEquals(0, admission.active)
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertEquals("ok", client("ok", admission).extractText(pdf()))
        } finally { thread.interrupt(); thread.join(5000); java.nio.file.Files.deleteIfExists(marker) }
    }

}

object PdfFaultWorker {
    @JvmStatic fun main(args: Array<String>) {
        if (args.first() == "no_read") { Thread.sleep(30000); return }
        val input = DataInputStream(System.`in`)
        val header = Json.parseToJsonElement(String(input.readNBytes(input.readInt()), Charsets.UTF_8)).jsonObject
        input.readNBytes(input.readInt())
        val output = DataOutputStream(System.out)
        fun reply(text: String) { val bytes = buildJsonObject { put("text", text) }.toString().toByteArray(); output.writeInt(bytes.size); output.write(bytes); output.flush() }
        when (args.first()) {
            "password_required", "password_invalid" -> {
                val bytes = buildJsonObject { put("error", args.first()) }.toString().toByteArray()
                output.writeInt(bytes.size); output.write(bytes); output.flush()
            }
            "program_fault" -> {
                val value = statementWorkerResponse { throw AssertionError("private-body", IllegalArgumentException("private-body-cause")) }
                val bytes = value.toString().toByteArray()
                output.writeInt(bytes.size); output.write(bytes); output.flush()
            }
            "bad_fault" -> {
                val bytes = "{\"internal_failure\":{\"causes\":[],\"causes_truncated\":false}}".toByteArray()
                output.writeInt(bytes.size); output.write(bytes); output.flush()
            }
            "inspect" -> reply(buildJsonObject {
                put("pid", ProcessHandle.current().pid()); put("heap", Runtime.getRuntime().maxMemory())
                put("environment", JsonObject(System.getenv().mapValues { JsonPrimitive(it.value) }))
                put("password", header["password"] ?: JsonNull)
                put("arguments", ProcessHandle.current().info().commandLine().orElse(""))
                put("directory", System.getProperty("user.dir"))
            }.toString())
            "ready_hang" -> { java.nio.file.Files.writeString(Path.of(args[1]), ProcessHandle.current().pid().toString()); Thread.sleep(30000) }
            "bad_document" -> { val text = "{\"document\":{}}"; output.writeInt(text.length); output.write(text.toByteArray()); output.flush() }
            "ok" -> reply("ok")
            "text_over_limit" -> reply("too-long")
            "deep_json" -> {
                val text = "[".repeat(15000) + "0" + "]".repeat(15000)
                output.writeInt(text.length); output.write(text.toByteArray()); output.flush()
            }
            "bad_json", "no_text", "not_string", "extra_fields" -> {
                val text = when (args.first()) { "bad_json" -> "{"; "no_text" -> "{}"; "not_string" -> "{\"text\":42}"; else -> "{\"text\":\"ok\",\"extra\":true}" }
                output.writeInt(text.toByteArray().size); output.write(text.toByteArray()); output.flush()
            }
            "hang" -> Thread.sleep(30000)
            "crash" -> kotlin.system.exitProcess(17)
            "oom" -> { val held = mutableListOf<ByteArray>(); while (true) held += ByteArray(16 * 1024 * 1024) }
            "oversized" -> { output.writeInt(Int.MAX_VALUE); output.flush() }
            "truncated" -> { output.writeInt(50); output.write(1); output.flush() }
            "trailing" -> { reply("ok"); output.write(1); output.flush() }
            "partial_then_hang" -> { reply("ok"); Thread.sleep(30000) }
        }
    }
}
