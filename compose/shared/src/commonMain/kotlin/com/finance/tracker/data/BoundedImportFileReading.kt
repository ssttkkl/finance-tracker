package com.finance.tracker.data

import com.finance.tracker.domain.FileReadLimitExceeded
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

internal expect suspend fun readPlatformImportFileBounded(file: PlatformFile, maxBytes: Int): ByteArray
internal expect fun platformImportFileLastModifiedAtMillis(file: PlatformFile): Long?

/** 只额外读一个探测字节，读取失败和超限也始终关闭来源。 */
internal fun readBoundedImportSource(source: RawSource, maxBytes: Int): ByteArray {
    return source.use { input ->
        require(maxBytes in 0 until Int.MAX_VALUE)
        val buffer = Buffer()
        var remaining = maxBytes.toLong() + 1
        while (remaining > 0) {
            val count = input.readAtMostTo(buffer, minOf(remaining, 64L * 1024))
            if (count == -1L) break
            check(count > 0)
            remaining -= count
        }
        if (buffer.size > maxBytes) throw FileReadLimitExceeded()
        buffer.readByteArray()
    }
}
