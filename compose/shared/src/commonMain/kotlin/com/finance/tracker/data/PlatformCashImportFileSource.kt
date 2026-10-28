package com.finance.tracker.data

import com.finance.tracker.domain.BoundedFileSource
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size

class PlatformCashImportFileSource(private val file: PlatformFile) : BoundedFileSource {
    override val name: String get() = file.name
    override val mediaType: String? get() = file.mimeType()?.toString()
    override val size: Long? get() = file.size().takeIf { it >= 0 }
    override val lastModifiedAtMillis: Long? get() = platformImportFileLastModifiedAtMillis(file)
    override suspend fun read(): ByteArray = file.readBytes()
    override suspend fun readBounded(maxBytes: Int): ByteArray = readPlatformImportFileBounded(file, maxBytes)
}
