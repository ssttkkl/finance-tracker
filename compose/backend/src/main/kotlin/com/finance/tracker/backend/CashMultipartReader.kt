package com.finance.tracker.backend

import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.util.AttributeKey
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import kotlinx.coroutines.*
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.bytestring.ByteString
import kotlinx.io.readByteArray

internal val cashMultipartObserverKey = AttributeKey<(CashMultipartResources) -> Unit>("cashMultipartObserver")

internal class CashMultipartSink(private val limit: Int, private val rejectOverflow: Boolean, val file: Boolean = false) : RawSink {
    private val bytes = Buffer()
    private val pending = Buffer()
    var closed = false
        private set
    override fun write(source: Buffer, byteCount: Long) {
        check(!closed)
        if (rejectOverflow && byteCount > limit - bytes.size) throw CashImportProtocolError()
        val retained = minOf(byteCount, limit - bytes.size)
        bytes.write(source, retained)
        source.skip(byteCount - retained)
    }
    fun value(): ByteArray = bytes.readByteArray()
    override fun flush() = Unit
    override fun close() { bytes.clear(); pending.clear(); closed = true }
    val channel = object : ByteWriteChannel {
        override val autoFlush = true
        override val isClosedForWrite get() = closed
        override val closedCause: Throwable? = null
        @OptIn(InternalAPI::class)
        override val writeBuffer get() = pending
        override suspend fun flush() { write(pending, pending.size) }
        override suspend fun flushAndClose() { flush(); this@CashMultipartSink.close() }
        override fun cancel(cause: Throwable?) { pending.clear() }
    }
}

internal class CashMultipartResources(val input: ByteReadChannel, val forwarded: ByteChannel, val pump: Job) {
    val sinks = mutableListOf<CashMultipartSink>()
}

internal data class CashMultipartParts(val metadata: String, val files: Map<String, ByteArray>, val names: Map<String, String>)

internal class CashMultipartReader(private val maxRequestBytes: Long = CashImportProtocol.MAX_REQUEST_BYTES,
    private val maxFileBytes: Int = CashImportProtocol.MAX_FILE_BYTES,
    private val observe: (CashMultipartResources) -> Unit = {}) {
    private val crlf = ByteString("\r\n".encodeToByteArray())
    private val finish = ByteString("--".encodeToByteArray())
    private fun invalid(): Nothing = throw CashImportProtocolError()

    suspend fun read(input: ByteReadChannel, contentType: String, contentLength: String?): CashMultipartParts = supervisorScope {
        val forwarded = ByteChannel(autoFlush = true)
        var transportFailure: Throwable? = null
        val pump = launch(start = CoroutineStart.LAZY) {
            try {
                var total = 0L
                val chunk = ByteArray(8192)
                while (true) {
                    input.closedCause?.let { throw it }
                    val size = input.readAvailable(chunk)
                    if (size < 0) { input.closedCause?.let { throw it }; break }
                    total += size
                    if (total > maxRequestBytes) throw CashImportProtocolError("upload_too_large")
                    forwarded.writeFully(chunk, 0, size)
                }
                forwarded.flushAndClose()
            } catch (failure: Throwable) {
                transportFailure = originalTransport(failure)
                forwarded.close(transportFailure)
            }
        }
        val resources = CashMultipartResources(input, forwarded, pump)
        try {
            require(maxRequestBytes >= 0 && maxFileBytes >= 0 && maxFileBytes < Int.MAX_VALUE)
            observe(resources)
            val type = contentType(contentType, contentLength)
            val boundary = type.parameter("boundary") ?: invalid()
            val charset = try { Charset.forName(type.parameter("charset") ?: "UTF-8") }
                catch (_: IllegalArgumentException) { StandardCharsets.ISO_8859_1 }
            pump.start()
            val result = parse(forwarded, boundary, charset, resources)
            forwarded.discard()
            pump.join()
            transportFailure?.let { throw it }
            result
        } catch (failure: IOException) {
            throw transportFailure ?: CashImportProtocolError()
        } finally {
            withContext(NonCancellable) {
                input.cancel()
                forwarded.cancel()
                pump.cancelAndJoin()
                if (input.closedCause == null) input.discard()
                if (forwarded.closedCause == null) forwarded.discard()
            }
        }
    }

    private fun originalTransport(failure: Throwable): Throwable =
        if ((failure is ClosedReadChannelException || failure is ClosedByteChannelException) && failure.cause != null) originalTransport(failure.cause!!) else failure

    private fun contentType(contentType: String, contentLength: String?): ContentType {
        if (contentType.length > 1024 || !contentType.lowercase().startsWith("multipart/form-data;")) invalid()
        if (contentLength != null) {
            if (!Regex("[0-9]{1,20}").matches(contentLength)) invalid()
            if (contentLength.toBigInteger() > maxRequestBytes.toBigInteger()) throw CashImportProtocolError("upload_too_large")
        }
        val type = try { ContentType.parse(contentType) } catch (_: BadContentTypeFormatException) { invalid() }
        type.parameter("boundary")?.takeIf { it.length in 1..70 && it.all { c -> c.code in 32..126 } && !it.endsWith(' ') } ?: invalid()
        return type
    }

    private suspend fun collect(input: ByteReadChannel, delimiter: ByteString, limit: Int, rejectOverflow: Boolean,
        resources: CashMultipartResources, body: Boolean = false): ByteArray {
        val sink = CashMultipartSink(limit, rejectOverflow, file = body && !rejectOverflow)
        resources.sinks += sink
        return sink.use {
            val output = sink.channel
            try {
                while (true) {
                    input.readUntil(delimiter, output)
                    output.flush()
                    if (!body || input.peek(2) in listOf(crlf, finish)) break
                    output.writeFully(delimiter.toByteArray())
                }
                output.flush()
                sink.value()
            } finally { output.cancel(null) }
        }
    }

    private suspend fun parse(input: ByteReadChannel, boundary: String, charset: Charset, resources: CashMultipartResources): CashMultipartParts {
        val first = ByteString("--$boundary".encodeToByteArray())
        val delimiter = ByteString("\r\n--$boundary".encodeToByteArray())
        while (input.skipIfFound(ByteString(13)) || input.skipIfFound(ByteString(10))) { }
        if (!input.skipIfFound(first) || !input.skipIfFound(crlf)) invalid()
        var metadata: String? = null
        val files = linkedMapOf<String, ByteArray>()
        val names = linkedMapOf<String, String>()
        var fileCount = 0
        var textCount = 0
        while (true) {
            val headers = headers(input, charset, resources)
            val disposition = try { ContentDisposition.parse(headers.getAll(HttpHeaders.ContentDisposition)?.lastOrNull() ?: invalid()) }
                catch (_: BadContentTypeFormatException) { invalid() }
            if (!disposition.disposition.equals("form-data", ignoreCase = true)) invalid()
            val name = disposition.parameter("name") ?: invalid()
            val filename = disposition.parameter("filename")
            if (filename == null) {
                if (++textCount > 1 || name != "metadata") invalid()
                val bytes = collect(input, delimiter, CashImportProtocol.MAX_METADATA_BYTES, true, resources, true)
                metadata = decode(bytes, charset)
                if (metadata.encodeToByteArray().size > CashImportProtocol.MAX_METADATA_BYTES) invalid()
            } else {
                if (++fileCount > 20 || !name.startsWith("file:") || name in files) invalid()
                files[name] = collect(input, delimiter, maxFileBytes + 1, false, resources, true)
                names[name] = filename.ifEmpty { "账单" }
            }
            if (input.skipIfFound(finish)) break
            if (!input.skipIfFound(crlf)) invalid()
        }
        return CashMultipartParts(metadata ?: invalid(), files, names)
    }

    private fun decode(bytes: ByteArray, charset: Charset): String = try {
        charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { String(bytes, StandardCharsets.ISO_8859_1) }

    private suspend fun headers(input: ByteReadChannel, charset: Charset, resources: CashMultipartResources): Headers {
        val result = HeadersBuilder()
        var size = 0
        var count = 0
        while (true) {
            val line = String(collect(input, crlf, 4224, true, resources), StandardCharsets.ISO_8859_1)
            if (line.isEmpty()) return result.build()
            if (++count > 8) invalid()
            val colon = line.indexOf(':')
            if (colon <= 0) invalid()
            val name = line.substring(0, colon)
            val value = line.substring(colon + 1).trimStart(' ')
            size += name.length + value.length + 4
            if (size > 16384) invalid()
            try { result.append(name, decode(value.toByteArray(StandardCharsets.ISO_8859_1), charset)) }
                catch (_: IllegalArgumentException) { invalid() }
        }
    }
}
