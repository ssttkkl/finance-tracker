import {
  assembleCashImportResult, assembleCashImportResultPages, calculatePreview, calculatePortfolioDisplay, captureStatement, captureRemoteStatement, freezeCashImportConfirmation,
  encodeCashImportBatch, encodeCashImportSubmission, inspectCashImportBatch, inspectCashImportResult,
  encodeInitialCashImportFiles, summarizeCapturedCashImportFiles, inspectCapturedCashImportFiles, cashImportArchivePage, inspectCashImportSubmission, openPreview, openStoredPreview, prepareCashImportSnapshot, rebaseCashImport,
  prepareCashImportAccounts, readCashLedgerPage, readPreviewSection, inspectCachedLedgerVersion,
} from "./FinanceTracker-cashImportWorker.mjs";

const internalError = { code: "internal_error" };
const previewError = { header: JSON.stringify(internalError) };

const operations = new Map([
  ["calculatePreview", { run: calculatePreview }],
  ["captureFile", { run: handleFile, failure: { error: "internal_error" } }],
  ["openPreview", { run: openPreview, failure: previewError }],
  ["openCachedPreview", {
    run: ({ preview, files, snapshot }) => openStoredPreview(preview, files, snapshot),
    failure: previewError,
  }],
  ["readPreview", {
    run: ({ query, section }) => ({ payload: readPreviewSection(query, readSection(section)) }),
    failure: { payload: JSON.stringify(internalError) },
  }],
  ["freezeConfirmation", {
    run: (section) => freezeCashImportConfirmation(readSection(section)),
    failure: internalError,
  }],
  ["assembleArchive", {
    run: ({ batchId, key, receipt, input, items, pages }) => pages == null
      ? assembleCashImportResult(batchId, key, receipt, input, items ?? null)
      : assembleCashImportResultPages(batchId, key, receipt, input, pages),
    failure: internalError,
  }],
  ["inspectArchive", { run: inspectCashImportResult, failure: internalError }],
  ["inspectSubmission", { run: inspectCashImportSubmission, failure: internalError }],
  ["inspectBatch", { run: inspectCashImportBatch, failure: internalError }],
  ["encodeBatch", {
    run: ({ files, review, submission, attempt }) => ({ body: encodeCashImportBatch(files, review ?? null, submission ?? null, attempt) }),
    failure: internalError,
  }],
  ["encodeSubmission", {
    run: ({ key, request, receipt, rejected, resultInput, transport }) =>
      ({ body: encodeCashImportSubmission(key, request, receipt ?? null, rejected, resultInput ?? null, transport ?? null) }),
    failure: internalError,
  }],
  ["rebaseImport", {
    run: ({ workspace, base, review, files, snapshot }) => rebaseCashImport(workspace, base, review, files, snapshot),
    failure: internalError,
  }],
  ["packJsonRequest", {
    run: async (payload) => {
      const body = typeof payload === "string" ? payload : payload?.body ?? "";
      const compress = typeof payload === "object" && payload?.compress === true;
      if (!compress || typeof CompressionStream === "undefined") {
        return { blob: new Blob([body], { type: "application/json" }), compressed: false };
      }
      const encoded = new Blob([body], { type: "application/json" });
      const compressed = await new Response(encoded.stream().pipeThrough(new CompressionStream("gzip"))).arrayBuffer();
      if (compressed.byteLength >= encoded.size) return { blob: encoded, compressed: false };
      return { blob: new Blob([compressed], { type: "application/json" }), compressed: true };
    },
    failure: internalError,
  }],
  ["investmentDisplay", { run: ({ portfolio, account, options }) => ({ body: calculatePortfolioDisplay(portfolio, account ?? null, options) }), failure: internalError }],
  ["encodeFiles", { run: (files) => ({ body: encodeInitialCashImportFiles(files) }), failure: internalError }],
  ["summarizeFiles", { run: (files) => ({ body: summarizeCapturedCashImportFiles(files) }), failure: internalError }],
  ["inspectFiles", { run: (files) => ({ body: inspectCapturedCashImportFiles(files) }), failure: internalError }],
  ["readArchivePage", { run: ({ body, kind, offset, limit }) => cashImportArchivePage(body, kind, offset, limit), failure: internalError }],
  ["captureRemoteFile", { run: ({ metadata, format, data, text }) => captureRemoteStatement(metadata, format, data, text), failure: internalError }],
  ["prepareImportAccounts", {
    run: ({ batch, workspace, files, snapshot, review }) => ({ body: prepareCashImportAccounts(batch, workspace, files, snapshot, review ?? null) }),
    failure: internalError,
  }],
  ["inspectSnapshot", { run: ({ workspace, text }) => ({ version: inspectCachedLedgerVersion(workspace, text) }), failure: internalError }],
  ["prepareSnapshot", { run: ({ workspace, text }) => prepareCashImportSnapshot(workspace, text), failure: internalError }],
  ["readLedgerPage", {
    run: ({ query, snapshot }) => ({ payload: readCashLedgerPage(query, snapshot ?? null) }),
    failure: ledgerFailure,
  }],
]);

self.onmessage = async ({ data }) => {
  const { requestId, operation, payload } = data ?? {};
  const handler = operations.get(operation);
  if (!Number.isSafeInteger(requestId) || requestId < 1 || !handler) {
    self.postMessage({ requestId });
    return;
  }
  try {
    const result = await handler.run(payload);
    const transfer = operation === "captureFile" && result.remoteBytes ? [result.remoteBytes] : [];
    self.postMessage({ requestId, result }, transfer);
  } catch (error) {
    const { failure } = handler;
    const result = typeof failure === "function" ? failure(error) : failure;
    const failed = result === internalError
      ? { ...internalError, phase: operation, kind: safeErrorKind(error) }
      : result;
    self.postMessage({ requestId, result: failed });
  }
};

function ledgerFailure(error) {
  // Keep the phase and safe exception category, never input data or exception messages.
  return { payload: JSON.stringify({ code: "internal", phase: "ledger_query", kind: safeErrorKind(error) }) };
}

function safeErrorKind(error) {
  return error instanceof RangeError ? "range" : error instanceof TypeError ? "type" : "runtime";
}

function readSection(section) {
  return new TextDecoder().decode(new Uint8Array(section).slice());
}

async function handleFile({ metadata, file, limit }) {
  if (file.size > limit) return { error: "import_file_too_large" };
  let bytes;
  try {
    bytes = await file.arrayBuffer();
  } catch {
    return { error: "import_file_read_failed" };
  }
  if (bytes.byteLength > limit) return { error: "import_file_too_large" };
  const result = JSON.parse(captureStatement(metadata, new Uint8Array(bytes)));
  return result.remote ? { remoteBytes: bytes } : result;
}

self.postMessage({ ready: true });
