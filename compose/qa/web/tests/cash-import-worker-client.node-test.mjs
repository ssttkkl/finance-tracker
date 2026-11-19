import assert from "node:assert/strict";
import { test } from "node:test";

test("compute requests correlate replies and keep pooled and ledger workers isolated", async () => {
  const workers = [];
  class WorkerStub {
    constructor() {
      workers.push(this);
      this.messages = [];
    }
    ready() {
      this.onmessage({ data: { ready: true } });
    }
    postMessage(message) {
      this.messages.push(message);
      this.message = message;
    }
    complete(result = this.message.payload, requestId = this.message.requestId) {
      if (!this.terminated) this.onmessage({ data: { requestId, result } });
    }
    terminate() {
      this.terminated = true;
    }
  }

  const originalWorker = globalThis.Worker;
  globalThis.Worker = WorkerStub;
  try {
    const { startPreview, startFile, openCachedPreview, createCashLedgerClient, freezeConfirmation, inspectBatch, rebaseImport } =
      await import("../../../webApp/src/wasmJsMain/worker/client.mjs");
    const selected = new Blob(["synthetic statement"]);
    const capture = startFile("file metadata", selected, 100);
    workers[0].ready();
    assert.deepEqual(workers[0].message, { requestId: 1, operation: "captureFile",
      payload: { metadata: "file metadata", file: selected, limit: 100 } });
    workers[0].ready();
    assert.equal(workers[0].messages.length, 1);
    workers[0].complete({ captured: "prepared" });
    assert.deepEqual(await capture.result, { captured: "prepared" });

    const first = startPreview("first");
    const finishedPreview = workers[0].message.requestId;
    workers[0].complete();
    assert.equal(await first.result, "first");

    const second = startPreview("second");
    first.cancel();
    workers[0].complete("late", finishedPreview);
    workers[0].complete();
    assert.equal(await second.result, "second");

    const cancelled = startPreview("cancelled");
    cancelled.cancel();
    await assert.rejects(cancelled.result, /cash_import_worker_failed/);
    assert.equal(workers[0].terminated, true);

    const retry = startPreview("retry");
    assert.equal(workers[1].message, undefined);
    workers[1].ready();
    workers[1].complete();
    assert.equal(await retry.result, "retry");

    const files = ['{"fileRef":"one"}'];
    const snapshot = '{"ledgerSeq":"9007199254740993"}';
    const batch = inspectBatch('{"files":[]}');
    assert.equal(workers[1].message.operation, "inspectBatch");
    workers[1].complete({ files });
    assert.deepEqual(await batch.result, { files });

    const rebase = rebaseImport("workspace", "9007199254740993", "review body", files, snapshot);
    assert.equal(workers[1].message.operation, "rebaseImport");
    assert.deepEqual(workers[1].message.payload, {
      workspace: "workspace", base: "9007199254740993", review: "review body", files, snapshot,
    });
    workers[1].complete({ request: "frozen replacement" });
    assert.deepEqual(await rebase.result, { request: "frozen replacement" });

    const preview = openCachedPreview('{"planId":"p"}', files, snapshot);
    assert.equal(workers[1].message.operation, "openCachedPreview");
    assert.deepEqual(workers[1].message.payload, { preview: '{"planId":"p"}', files, snapshot });
    workers[1].complete({ header: "{}" });
    assert.deepEqual(await preview.result, { header: "{}" });

    const material = new SharedArrayBuffer(8);
    const confirmation = freezeConfirmation(material);
    assert.equal(workers[1].message.operation, "freezeConfirmation");
    assert.equal(workers[1].message.payload, material);
    workers[1].complete({ body: "{}" });
    assert.deepEqual(await confirmation.result, { body: "{}" });

    const ledger = createCashLedgerClient();
    const firstPage = ledger.page("first", snapshot);
    workers[2].ready();
    assert.equal(workers[2].message.operation, "readLedgerPage");
    assert.deepEqual(workers[2].message.payload, { query: "first", snapshot });
    workers[2].complete({ payload: "{}" });
    await firstPage.result;
    const nextPage = ledger.page("next");
    assert.equal(workers[2].message.payload.snapshot, undefined);
    workers[2].complete({ payload: "{}" });
    await nextPage.result;
    const pendingPage = ledger.page("close");
    const independent = startPreview("independent");
    const replacementLedger = createCashLedgerClient();
    const replacementPage = replacementLedger.page("another workspace", "{}");
    ledger.close();
    await assert.rejects(pendingPage.result, /cash_import_worker_failed:closed/);
    assert.equal(workers[2].terminated, true);
    workers[2].onmessage({ data: { requestId: workers[2].message.requestId, result: { payload: "retired" } } });
    workers[3].ready();
    workers[3].complete({ payload: "current" });
    assert.deepEqual(await replacementPage.result, { payload: "current" });
    replacementLedger.close();

    workers[1].complete();
    assert.equal(await independent.result, "independent");

    const badReply = startPreview("bad reply");
    workers[1].complete("unexpected", workers[1].message.requestId + 1);
    await assert.rejects(badReply.result, /cash_import_worker_failed:response_contract/);
  } finally {
    globalThis.Worker = originalWorker;
  }
});
