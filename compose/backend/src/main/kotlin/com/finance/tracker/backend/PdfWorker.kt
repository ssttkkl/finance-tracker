package com.finance.tracker.backend

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.io.Writer
import kotlinx.serialization.json.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

internal object PdfWorker {
    @JvmStatic fun main(args: Array<String>) {
        val output = DataOutputStream(System.out)
        System.setOut(PrintStream(OutputStream.nullOutputStream()))
        val input = DataInputStream(System.`in`)
        val size = input.readInt()
        require(size in 1..PDF_HEADER_LIMIT)
        val raw = input.readNBytes(size); require(raw.size == size)
        val request = try { Json.decodeFromString<PdfRequest>(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString()) } finally { raw.fill(0) }
        val count = input.readInt(); require(count in 1..request.limits.maxInputBytes)
        val content = input.readNBytes(count); require(content.size == count && input.read() == -1)
        val response = try {
            when (request.operation) {
                "text" -> buildJsonObject { put("text", text(content, request)) }
                "icbc" -> buildJsonObject { put("document", Json.encodeToJsonElement(IcbcPdfDocumentReader.decodeInWorker(content, request.source, request.password, request.icbcLimits))) }
                else -> throw IllegalArgumentException("unsupported PDF operation")
            }
        } catch (failure: StatementPdfPasswordFailure) {
            buildJsonObject { put("error", if (failure.required) "password_required" else "password_invalid") }
        } catch (failure: StatementDocumentFailure) {
            buildJsonObject { put("error", if (failure.code in PDF_ERROR_CODES) failure.code else "statement_unreadable") }
        } catch (_: IOException) {
            buildJsonObject { put("error", "statement_unreadable") }
        } finally { content.fill(0) }
        val result = response.toString().toByteArray(Charsets.UTF_8)
        require(result.size in 1..PDF_RESPONSE_LIMIT)
        output.writeInt(result.size); output.write(result); output.flush()
    }

    private fun text(content: ByteArray, request: PdfRequest): String {
        val document = try { Loader.loadPDF(content, request.password ?: "") } catch (_: InvalidPasswordException) {
            throw StatementPdfPasswordFailure(request.password == null)
        }
        document.use {
            if (document.numberOfPages > request.limits.maxPages) throw StatementDocumentFailure("statement_rows_too_large")
            var characters = 0L
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    characters += text.unicode.length
                    if (characters > request.limits.maxChars) throw StatementDocumentFailure("statement_rows_too_large")
                    super.processTextPosition(text)
                }
            }
            stripper.lineSeparator = "\n"; stripper.pageEnd = "\n"; stripper.sortByPosition = false
            val writer = BoundedPdfWriter(request.limits.maxTextBytes)
            stripper.writeText(document, writer)
            return writer.result()
        }
    }
}

private class BoundedPdfWriter(private val maxBytes: Int) : Writer() {
    private val value = StringBuilder()
    private var bytes = 0L
    private var high = false
    override fun write(buffer: CharArray, offset: Int, length: Int) {
        for (index in offset until offset + length) {
            val char = buffer[index]
            bytes += when {
                char.isLowSurrogate() && high -> 1
                char.isHighSurrogate() -> 3
                char.code < 128 -> 1
                char.code < 2048 -> 2
                else -> 3
            }
            high = char.isHighSurrogate()
            if (bytes > maxBytes) throw StatementDocumentFailure("statement_rows_too_large")
        }
        value.append(buffer, offset, length)
    }
    fun result() = value.toString()
    override fun flush() = Unit
    override fun close() = Unit
}
