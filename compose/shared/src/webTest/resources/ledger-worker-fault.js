// Test-only Worker; the real database implementation and relative asset directory are unchanged.
importScripts("./worker.js");
const actualLedgerMessage = self.onmessage;
let holdNext = false;
self.onmessage = (event) => {
  if (event.data.qaCrash) throw new Error("Synthetic SDK worker failure");
  if (event.data.qaHoldNext) { holdNext = true; return; }
  if (holdNext && event.data.action === "exec") {
    holdNext = false; postMessage({ qaHeld: true }); return;
  }
  actualLedgerMessage(event);
};
