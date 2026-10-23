package com.finance.tracker.data

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

internal actual suspend fun readPlatformImportFileBounded(file: PlatformFile, maxBytes: Int): ByteArray =
    withContext(Dispatchers.IO) { readBoundedImportSource(file.source(), maxBytes) }
