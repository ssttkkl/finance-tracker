import { calculatePreview, captureStatement, openPreview, readPreviewSection } from "./FinanceTracker-cashImportWorker.mjs";

self.onmessage = async ({ data }) => {
  try {
    if (typeof data === "string") {
      self.postMessage({ result: calculatePreview(data) });
    } else if (data.openPreview) {
      self.postMessage({ result: openPreview(data.openPreview) });
    } else if (data.query) {
      const payload = readPreviewSection(data.query, new TextDecoder().decode(new Uint8Array(data.section).slice()));
      self.postMessage({ result: { payload } });
    } else {
      await handleFile(data);
    }
  } catch {
    if (typeof data === "string") self.postMessage({ failed: true });
    else if (data.openPreview) self.postMessage({ result: { header: '{"code":"internal_error"}' } });
    else if (data.query) self.postMessage({ result: { payload: '{"code":"internal_error"}' } });
    else self.postMessage({ result: { error: "internal_error" } });
  }
};

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
