import assert from "node:assert/strict";
import { test } from "node:test";

test("cancelling a completed preview cannot terminate the next job", async () => {
  const workers = [];
  class WorkerStub {
    constructor() {
      workers.push(this);
    }
    ready() {
      this.onmessage({ data: { ready: true } });
    }
    postMessage(payload) {
      this.payload = payload;
    }
    complete(result = this.payload) {
      if (!this.terminated) this.onmessage({ data: { result } });
    }
    terminate() {
      this.terminated = true;
    }
  }

  const originalWorker = globalThis.Worker;
  globalThis.Worker = WorkerStub;
  try {
    const { startPreview, startFile, openCachedPreview, createCashLedgerClient, freezeConfirmation } =
      await import("../../../webApp/src/wasmJsMain/worker/client.mjs");
    const selected = new Blob(["synthetic statement"]);
    const capture = startFile("file metadata", selected, 100);
    workers[0].ready();
    assert.deepEqual(workers[0].payload, { metadata: "file metadata", file: selected, limit: 100 });
    workers[0].complete({ captured: "prepared" });
    assert.deepEqual(await capture.result, { captured: "prepared" });

    const first = startPreview("first");
    workers[0].complete();
    assert.equal(await first.result, "first");

    const second = startPreview("second");
    first.cancel();
    workers[0].complete();
    assert.equal(await second.result, "second");

    const cancelled = startPreview("cancelled");
    cancelled.cancel();
    await assert.rejects(cancelled.result, /cash_import_worker_failed/);
    assert.equal(workers[0].terminated, true);

    const retry = startPreview("retry");
    assert.equal(workers[1].payload, undefined);
    workers[1].ready();
    workers[1].complete();
    assert.equal(await retry.result, "retry");

    const files = ['{"fileRef":"one"}'];
    const snapshot = '{"ledgerSeq":"9007199254740993"}';
    const preview = openCachedPreview('{"planId":"p"}', files, snapshot);
    assert.deepEqual(workers[1].payload, { preview: '{"planId":"p"}', files, snapshot });
    workers[1].complete({ header: "{}" });
    assert.deepEqual(await preview.result, { header: "{}" });

    const material = new SharedArrayBuffer(8);
    const confirmation = freezeConfirmation(material);
    assert.equal(workers[1].payload.confirmation, material);
    workers[1].complete({ body: "{}" });
    assert.deepEqual(await confirmation.result, { body: "{}" });

    const ledger = createCashLedgerClient();
    const firstPage = ledger.page("first", snapshot);
    workers[2].ready();
    assert.deepEqual(workers[2].payload, { ledger: "first", snapshot });
    workers[2].complete({ payload: "{}" });
    await firstPage.result;
    const nextPage = ledger.page("next");
    assert.equal(workers[2].payload.snapshot, undefined);
    workers[2].complete({ payload: "{}" });
    await nextPage.result;
    ledger.close();
    assert.equal(workers[2].terminated, true);
  } finally {
    globalThis.Worker = originalWorker;
  }
});
