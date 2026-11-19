import {
  assembleCashImportResult, calculatePreview, captureStatement, freezeCashImportConfirmation,
  inspectCashImportResult, inspectCashImportSubmission, openPreview, openStoredPreview,
  readCashLedgerPage, readPreviewSection,
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
    run: ({ batchId, key, receipt, input, items }) => assembleCashImportResult(batchId, key, receipt, input, items ?? null),
    failure: internalError,
  }],
  ["inspectArchive", { run: inspectCashImportResult, failure: internalError }],
  ["inspectSubmission", { run: inspectCashImportSubmission, failure: internalError }],
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
    self.postMessage({ requestId, result });
  }
};

function ledgerFailure(error) {
  // Keep the phase and safe exception category, never input data or exception messages.
  const kind = error instanceof RangeError ? "range" : error instanceof TypeError ? "type" : "runtime";
  return { payload: JSON.stringify({ code: "internal", phase: "ledger_query", kind }) };
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
