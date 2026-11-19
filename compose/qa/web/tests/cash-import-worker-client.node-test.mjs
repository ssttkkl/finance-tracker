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
    const { startPreview } = await import("../../../webApp/src/wasmJsMain/worker/client.mjs");
    const first = startPreview("first");
    assert.equal(workers[0].payload, undefined);
    workers[0].ready();
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
  } finally {
    globalThis.Worker = originalWorker;
  }
});
