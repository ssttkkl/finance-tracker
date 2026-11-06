// Test-only Worker; the real database implementation and relative asset directory are unchanged.
importScripts("./worker.js");
const actualLedgerMessage = self.onmessage;
let holdNext = false;
let rejectCloseAck = false;
let closeId;
const actualLedgerPost = self.postMessage.bind(self);
self.postMessage = (data, ...options) => {
  if (rejectCloseAck && data.id === closeId) {
    rejectCloseAck = false;
    actualLedgerPost({id: data.id, error: {name: 'Error', message: 'Synthetic final close acknowledgement failure'}});
  } else actualLedgerPost(data, ...options);
};
self.onmessage = (event) => {
  if (event.data.qaFailCloseAckOnce) { rejectCloseAck = true; return; }
  if (rejectCloseAck && event.data.action === 'close') closeId = event.data.id;
  if (event.data.qaCrash) throw new Error("Synthetic SDK worker failure");
  if (event.data.qaHoldNext) { holdNext = true; return; }
  if (holdNext && event.data.action === "exec") {
    holdNext = false; postMessage({ qaHeld: true }); return;
  }
  actualLedgerMessage(event);
};
