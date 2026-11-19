import { calculatePreview } from "./FinanceTracker-cashImportWorker.mjs";

self.onmessage = ({ data }) => {
  try {
    self.postMessage({ result: calculatePreview(data) });
  } catch {
    self.postMessage({ failed: true });
  }
};

self.postMessage({ ready: true });
