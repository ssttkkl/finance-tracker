@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import com.finance.tracker.domain.FileReadLimitExceeded
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.WebFile
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.w3c.files.FileReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.unsafeCast

internal actual suspend fun readPlatformImportFileBounded(file: PlatformFile, maxBytes: Int): ByteArray {
    require(maxBytes in 0 until Int.MAX_VALUE)
    // 浏览器 File 的字节及大小不可变；未知大小不能回退到无界读取。
    val size = file.size()
    check(size >= 0)
    if (size > maxBytes) throw FileReadLimitExceeded()
    val browser = file.webFile as? WebFile.FileWrapper ?: error("file_read_failed")
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val reader = FileReader()
            fun release() {
                reader.onload = null
                reader.onerror = null
                reader.onabort = null
            }
            fun fail() {
                release()
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("file_read_failed"))
            }
            reader.onload = {
                try {
                    val buffer = reader.result?.unsafeCast<ArrayBuffer>() ?: error("file_read_failed")
                    val bytes = Uint8Array(buffer)
                    if (bytes.length > maxBytes) throw FileReadLimitExceeded()
                    val content = ByteArray(bytes.length) { index -> bytes[index] }
                    release()
                    continuation.resume(content)
                } catch (failure: Exception) {
                    release()
                    if (continuation.isActive) continuation.resumeWithException(
                        if (failure is FileReadLimitExceeded) failure else IllegalStateException("file_read_failed"))
                }
            }
            reader.onerror = { fail() }
            reader.onabort = { fail() }
            continuation.invokeOnCancellation {
                release()
                runCatching { reader.abort() }
            }
            if (continuation.isActive) {
                try {
                    reader.readAsArrayBuffer(browser.file)
                } catch (_: Exception) {
                    fail()
                }
            }
        }
    }
}
