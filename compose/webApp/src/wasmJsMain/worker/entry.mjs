import { calculatePreview, captureStatement, openPreview, openStoredPreview, readPreviewSection, readCashLedgerPage } from "./FinanceTracker-cashImportWorker.mjs";
import { assembleCashImportResult, freezeCashImportConfirmation, inspectCashImportResult, inspectCashImportSubmission } from "./FinanceTracker-cashImportWorker.mjs";

self.onmessage = async ({ data }) => {
  try {
    if (typeof data === "string") {
      self.postMessage({ result: calculatePreview(data) });
    } else if (data.preview) {
      self.postMessage({ result: openStoredPreview(data.preview, data.files, data.snapshot) });
    } else if (data.openPreview) {
      self.postMessage({ result: openPreview(data.openPreview) });
    } else if (data.ledger) {
      self.postMessage({ result: { payload: readCashLedgerPage(data.ledger, data.snapshot ?? null) } });
    } else if (data.confirmation) {
      self.postMessage({ result: freezeCashImportConfirmation(readSection(data.confirmation)) });
    } else if (data.archive) {
      const { batchId, key, receipt, input, items } = data.archive;
      self.postMessage({ result: assembleCashImportResult(batchId, key, receipt, input, items ?? null) });
    } else if (data.resultBody) {
      self.postMessage({ result: inspectCashImportResult(data.resultBody) });
    } else if (data.submissionBody) {
      self.postMessage({ result: inspectCashImportSubmission(data.submissionBody) });
    } else if (data.query) {
      const payload = readPreviewSection(data.query, readSection(data.section));
      self.postMessage({ result: { payload } });
    } else {
      await handleFile(data);
    }
  } catch (error) {
    if (typeof data === "string") self.postMessage({ failed: true });
    else if (data.openPreview || data.preview) self.postMessage({ result: { header: '{"code":"internal_error"}' } });
    else if (data.ledger) {
      // Keep the phase and safe exception category, never input data or exception messages.
      const kind = error instanceof RangeError ? "range" : error instanceof TypeError ? "type" : "runtime";
      self.postMessage({ result: { payload: JSON.stringify({ code: "internal", phase: "ledger_query", kind }) } });
    }
    else if (data.confirmation) self.postMessage({ result: { code: "internal_error" } });
    else if (data.archive || data.resultBody || data.submissionBody) self.postMessage({ result: { code: "internal_error" } });
    else if (data.query) self.postMessage({ result: { payload: '{"code":"internal_error"}' } });
    else self.postMessage({ result: { error: "internal_error" } });
  }
};

function readSection(section) {
  return new TextDecoder().decode(new Uint8Array(section).slice());
}

async function handleFile({ metadata, file, limit }) {
  if (file.size > limit) {
    self.postMessage({ result: { error: "import_file_too_large" } });
    return;
  }
  let bytes;
  try {
    bytes = await file.arrayBuffer();
  } catch {
    self.postMessage({ result: { error: "import_file_read_failed" } });
    return;
  }
  if (bytes.byteLength > limit) {
    self.postMessage({ result: { error: "import_file_too_large" } });
    return;
  }
  const result = JSON.parse(captureStatement(metadata, new Uint8Array(bytes)));
  if (result.remote) self.postMessage({ result: { remoteBytes: bytes } }, [bytes]);
  else self.postMessage({ result });
}

self.postMessage({ ready: true });
