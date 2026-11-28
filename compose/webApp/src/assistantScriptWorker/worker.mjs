import { newQuickJSWASMModuleFromVariant } from 'quickjs-emscripten-core';
import RELEASE_SYNC from '@jitl/quickjs-wasmfile-release-sync';

const pending = new Map();
let started = false;

self.onmessage = ({ data }) => {
  if (data.kind === 'hostResult') {
    pending.get(data.id)?.(data.body);
  } else if (data.kind === 'run' && !started) {
    started = true;
    run(data);
  }
};

async function run({ program, timeoutMs, heapBytes }) {
  let context;
  let deadline = Infinity;
  const calls = [];
  try {
    const module = await newQuickJSWASMModuleFromVariant(RELEASE_SYNC);
    context = module.newContext();
    deadline = performance.now() + timeoutMs;
    context.runtime.setMemoryLimit(heapBytes);
    context.runtime.setInterruptHandler(() => performance.now() >= deadline);
    let published;
    const encoder = new TextEncoder();
    const invoke = context.newFunction('__ledgerInvoke', (arg) => {
      const body = context.getString(arg);
      if (pending.size !== 0 || body.length > 7 * 1024 * 1024 || encoder.encode(body).length > 7 * 1024 * 1024) {
        throw new Error('invalid_bridge_request');
      }
      const id = calls.length + 1;
      const promise = context.newPromise();
      calls.push(promise);
      pending.set(id, (body) => {
        pending.delete(id);
        context.newString(body).consume(value => promise.resolve(value));
      });
      self.postMessage({ kind: 'hostRequest', id, body });
      return promise.handle;
    });
    invoke.consume(fn => context.setProp(context.global, '__ledgerInvoke', fn));
    const publish = context.newFunction('__ledgerPublish', (arg) => {
      const body = context.getString(arg);
      if (body.length > 4106 || encoder.encode(body).length > 4106) throw new Error('result_too_large');
      published = body;
    });
    publish.consume(fn => context.setProp(context.global, '__ledgerPublish', fn));

    const evaluation = context.evalCode(program);
    if (evaluation.error) {
      evaluation.error.dispose();
      throw new Error();
    }
    evaluation.value.dispose();
    while (published === undefined && performance.now() < deadline) {
      const jobs = context.runtime.executePendingJobs();
      if (jobs.error) {
        jobs.error.dispose();
        throw new Error();
      }
      if (published === undefined) await new Promise(resolve => setTimeout(resolve, 5));
    }
    if (published === undefined) throw new Error();
    self.postMessage({ kind: 'result', body: published });
  } catch {
    self.postMessage({ kind: performance.now() >= deadline ? 'timeout' : 'failed' });
  } finally {
    pending.clear();
    for (const call of calls) call.dispose();
    context?.dispose();
  }
}
