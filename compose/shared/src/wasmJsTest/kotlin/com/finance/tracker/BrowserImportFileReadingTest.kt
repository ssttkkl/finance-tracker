@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker

import com.finance.tracker.data.readPlatformImportFileBounded
import com.finance.tracker.domain.FileReadLimitExceeded
import io.github.vinceglb.filekit.BrowserFile
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.WebFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class BrowserImportFileReadingTest {
    @Test fun realReaderPreservesBinaryBytesAtTheExactLimitAndRejectsLargerFiles() = runTest {
        withContext(Dispatchers.Default) {
            val file = PlatformFile(WebFile.FileWrapper(syntheticNonemptyBrowserFile()))
            val bytes = withTimeout(3000) { readPlatformImportFileBounded(file, 4) }
            assertContentEquals(byteArrayOf(0, 127, -128, -1), bytes)
            assertFailsWith<FileReadLimitExceeded> { readPlatformImportFileBounded(file, 3) }
        }
    }

    @Test fun readerErrorFinishesWithSafeFailureInsteadOfWaitingForever() = runTest {
        installImportReaderFailure()
        try {
            withContext(Dispatchers.Default) {
                val error = assertFailsWith<IllegalStateException> {
                    withTimeout(1000) { readPlatformImportFileBounded(syntheticFile(), 4) }
                }
                assertFalse(error is TimeoutCancellationException)
                assertEquals("file_read_failed", error.message)
            }
        } finally {
            restoreImportReader()
        }
    }

    @Test fun cancellationAbortsTheBrowserReadAndReleasesItsHandlers() = runTest {
        installPendingImportReader()
        try {
            withContext(Dispatchers.Default) {
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(30) { readPlatformImportFileBounded(syntheticFile(), 4) }
                }
                assertEquals(1, importReaderAbortCount())
                assertTrue(importReaderHandlersReleased())
            }
        } finally {
            restoreImportReader()
        }
    }

    private fun syntheticFile() = PlatformFile(WebFile.FileWrapper(syntheticBrowserFile()))
}

@JsFun("() => new File([], 'synthetic.csv')")
private external fun syntheticBrowserFile(): BrowserFile

@JsFun("() => new File([new Uint8Array([0,127,128,255])], 'synthetic.csv')")
private external fun syntheticNonemptyBrowserFile(): BrowserFile

@JsFun("""() => {
  globalThis.__ft_import_original_reader = globalThis.FileReader;
  globalThis.__ft_import_abort_count = 0;
  globalThis.FileReader = class {
    constructor() { this.readyState = 0; globalThis.__ft_import_last_reader = this; }
    readAsArrayBuffer() {
      this.readyState = 1;
      queueMicrotask(() => { this.readyState = 2; this.onerror?.({target:this}); });
    }
    abort() { this.readyState = 2; globalThis.__ft_import_abort_count++; this.onabort?.({target:this}); }
  };
}""")
private external fun installImportReaderFailure()

@JsFun("""() => {
  globalThis.__ft_import_original_reader = globalThis.FileReader;
  globalThis.__ft_import_abort_count = 0;
  globalThis.FileReader = class {
    constructor() { this.readyState = 0; globalThis.__ft_import_last_reader = this; }
    readAsArrayBuffer() { this.readyState = 1; }
    abort() { this.readyState = 2; globalThis.__ft_import_abort_count++; this.onabort?.({target:this}); }
  };
}""")
private external fun installPendingImportReader()

@JsFun("() => globalThis.__ft_import_abort_count")
private external fun importReaderAbortCount(): Int

@JsFun("""() => {
  const reader = globalThis.__ft_import_last_reader;
  return reader.onload == null && reader.onerror == null && reader.onabort == null;
}""")
private external fun importReaderHandlersReleased(): Boolean

@JsFun("""() => {
  globalThis.FileReader = globalThis.__ft_import_original_reader;
  delete globalThis.__ft_import_original_reader;
  delete globalThis.__ft_import_last_reader;
  delete globalThis.__ft_import_abort_count;
}""")
private external fun restoreImportReader()
