package com.finance.tracker.backend

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
internal data class PdfReadLimits(val maxInputBytes: Int = 100 * 1024 * 1024, val maxPages: Int = 2000,
    val maxChars: Long = 5_000_000, val maxTextBytes: Int = 25 * 1024 * 1024) {
    init { require(maxInputBytes in 0..100 * 1024 * 1024 && maxPages in 0..2000 && maxChars in 0..5_000_000 && maxTextBytes in 0..25 * 1024 * 1024) }
}

@Serializable
internal data class PdfRequest(val operation: String, val password: String? = null, val source: String? = null,
    val limits: PdfReadLimits = PdfReadLimits(), val icbcLimits: IcbcPdfDocumentReader.IcbcPdfLimits = IcbcPdfDocumentReader.IcbcPdfLimits())

internal class PdfAdmission(private val capacity: Int) {
    init { require(capacity > 0) }
    private val semaphore = Semaphore(capacity)
    val active: Int get() = capacity - semaphore.availablePermits()
    fun acquire() { if (!semaphore.tryAcquire()) throw StatementDocumentFailure("statement_parser_busy") }
    fun release() = semaphore.release()
}

internal data class PdfWorkerLaunch(
    val mainClass: String = "com.finance.tracker.backend.PdfWorker",
    val arguments: List<String> = emptyList(),
    val classpath: String = System.getProperty("ft.pdf.worker.classpath") ?: System.getProperty("java.class.path"),
)

internal object PdfProcesses {
    val admission = PdfAdmission(2)
    val client = PdfProcessClient()
}

internal class PdfProcessClient(
    private val admission: PdfAdmission = PdfProcesses.admission,
    private val launch: PdfWorkerLaunch = PdfWorkerLaunch(),
    private val timeoutMillis: Long = 60000,
) {
    init { require(timeoutMillis > 0) }
    fun extractText(content: ByteArray, password: String? = null, limits: PdfReadLimits = PdfReadLimits()): String =
        call(PdfRequest("text", password, limits = limits), content).getValue("text").jsonPrimitive.content

    fun readIcbc(content: ByteArray, source: String?, password: String?, limits: IcbcPdfDocumentReader.IcbcPdfLimits): CashStatementDocument {
        val value = call(PdfRequest("icbc", password, source, icbcLimits = limits), content).getValue("document")
        return try { Json.decodeFromJsonElement<CashStatementDocument>(value) }
        catch (_: IllegalArgumentException) { throw StatementDocumentFailure("statement_unreadable") }
    }

    fun readXls(content: ByteArray): CashStatementDocument {
        val value = call(PdfRequest("xls", source = "ccb_debit"), content).getValue("document")
        return try { Json.decodeFromJsonElement<CashStatementDocument>(value) }
        catch (failure: IllegalArgumentException) { throw CashStatementParseInternalFailure(failure) }
    }

    private fun call(request: PdfRequest, content: ByteArray): JsonObject {
        if (content.isEmpty() || content.size > request.limits.maxInputBytes || request.operation == "icbc" && content.size > request.icbcLimits.maxInputBytes)
            throw StatementDocumentFailure("statement_input_invalid")
        if ((request.password?.length ?: 0) > PDF_HEADER_LIMIT || (request.source?.length ?: 0) > PDF_HEADER_LIMIT)
            throw StatementDocumentFailure("statement_input_invalid")
        admission.acquire()
        var header = ByteArray(0)
        var directory: Path? = null
        var process: Process? = null
        var declaredFileError = false
        fun processFailure(cause: Throwable): Nothing {
            if (request.operation == "xls") throw CashStatementParseInternalFailure(cause)
            throw StatementDocumentFailure("statement_unreadable").also { it.addSuppressed(cause) }
        }
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            request.password?.let {
                try { Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(it)) }
                catch (_: java.nio.charset.CharacterCodingException) { throw StatementDocumentFailure("statement_input_invalid") }
            }
            header = Json.encodeToString(request).toByteArray(Charsets.UTF_8)
            if (header.size > PDF_HEADER_LIMIT) throw StatementDocumentFailure("statement_input_invalid")
            directory = Files.createTempDirectory("ft-pdf-worker-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString()
            val classpath = launch.classpath.split(java.io.File.pathSeparator).joinToString(java.io.File.pathSeparator) { Path.of(it).toAbsolutePath().toString() }
            val command = listOf(javaExecutable, "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:MaxDirectMemorySize=32m", "-Xss512k",
                "-XX:ActiveProcessorCount=1", "-XX:+ExitOnOutOfMemoryError", "-XX:-HeapDumpOnOutOfMemoryError", "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=/dev/null",
                "-Djava.awt.headless=true", "-Duser.home=$directory", "-Djava.io.tmpdir=$directory", "-cp", classpath, launch.mainClass) + launch.arguments
            val builder = ProcessBuilder(command).directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment().clear()
            val running = builder.start(); process = running
            val writer = executor.submit {
                DataOutputStream(running.outputStream).use { output ->
                    output.writeInt(header.size); output.write(header); output.writeInt(content.size); output.write(content)
                }
            }
            val reader = executor.submit<ByteArray> {
                val input = DataInputStream(running.inputStream)
                val size = input.readInt()
                if (size !in 1..PDF_RESPONSE_LIMIT) throw StatementDocumentFailure("statement_unreadable")
                val result = input.readNBytes(size)
                if (result.size != size || input.read() != -1) throw StatementDocumentFailure("statement_unreadable")
                result
            }
            fun remaining() = maxOf(0L, deadline - System.nanoTime())
            val result = reader.get(remaining(), TimeUnit.NANOSECONDS)
            writer.get(remaining(), TimeUnit.NANOSECONDS)
            if (!running.waitFor(remaining(), TimeUnit.NANOSECONDS)) throw TimeoutException()
            if (running.exitValue() != 0) throw StatementDocumentFailure("statement_unreadable")
            val response = try {
                checkPdfJsonDepth(result)
                Json.parseToJsonElement(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(result)).toString()).jsonObject
            } catch (failure: IllegalArgumentException) { processFailure(failure) }
            if ("internal_failure" in response) {
                try {
                    require(response.keys == setOf("internal_failure"))
                    throw readStatementWorkerFailure(response.getValue("internal_failure"))
                } catch (failure: IllegalArgumentException) { processFailure(failure) }
            } else if ("error" in response) {
                if (response.keys != setOf("error") || (response["error"] as? JsonPrimitive)?.isString != true)
                    throw StatementDocumentFailure("statement_unreadable")
            } else if (request.operation == "text") {
                val text = response["text"] as? JsonPrimitive
                if (response.keys != setOf("text") || text?.isString != true || text.content.toByteArray(Charsets.UTF_8).size > request.limits.maxTextBytes)
                    throw StatementDocumentFailure("statement_unreadable")
            } else if (response.keys != setOf("document") || response["document"] !is JsonObject) {
                throw StatementDocumentFailure("statement_unreadable")
            }
            response["error"]?.jsonPrimitive?.content?.let { code ->
                if (request.operation == "xls" && code in setOf("password_required", "password_invalid"))
                    processFailure(IllegalStateException("unexpected password response from XLS worker"))
                declaredFileError = code in PDF_ERROR_CODES || code in setOf("password_required", "password_invalid")
                when (code) {
                    "password_required" -> throw StatementPdfPasswordFailure(true)
                    "password_invalid" -> throw StatementPdfPasswordFailure(false)
                    in PDF_ERROR_CODES -> throw StatementDocumentFailure(code)
                    else -> throw StatementDocumentFailure("statement_unreadable")
                }
            }
            return response
        } catch (failure: StatementDocumentFailure) {
            if (request.operation == "xls" && failure.code == "statement_unreadable" && !declaredFileError)
                throw CashStatementParseInternalFailure(failure)
            throw failure
        } catch (_: TimeoutException) { throw StatementDocumentFailure("statement_parser_timeout") } catch (failure: ExecutionException) {
            val cause = failure.cause
            processFailure(cause ?: failure)
        } catch (failure: IOException) { processFailure(failure) } finally {
            try {
                process?.let { running ->
                    running.destroyForcibly()
                    var interrupted = false
                    while (running.isAlive) {
                        try { running.waitFor() } catch (_: InterruptedException) { interrupted = true }
                    }
                    running.inputStream.close(); running.errorStream.close(); running.outputStream.close()
                    if (interrupted) Thread.currentThread().interrupt()
                }
            } finally {
                executor.shutdownNow()
                header.fill(0)
                try { directory?.let { dir -> Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } } }
                finally { admission.release() }
            }
        }
    }
}

internal const val PDF_HEADER_LIMIT = 64 * 1024
internal const val PDF_RESPONSE_LIMIT = 64 * 1024 * 1024
internal val PDF_ERROR_CODES = setOf("statement_input_invalid", "statement_unreadable", "statement_rows_too_large", "statement_header_invalid", "statement_header_not_found", "statement_source_unsupported")

private fun checkPdfJsonDepth(bytes: ByteArray) {
    var depth = 0; var quoted = false; var escaped = false
    for (byte in bytes) {
        val char = byte.toInt().toChar()
        if (quoted) {
            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> { depth++; if (depth > 32) throw StatementDocumentFailure("statement_unreadable") }
            '}', ']' -> depth--
        }
    }
}
