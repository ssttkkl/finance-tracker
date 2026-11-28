import { build } from 'esbuild';
import { copyFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const output = process.argv[2];
await build({
  entryPoints: ['worker.mjs'],
  outfile: join(output, 'worker.mjs'),
  bundle: true,
  minify: true,
  platform: 'browser',
  format: 'esm',
  logLevel: 'warning',
});
await copyFile(
  fileURLToPath(import.meta.resolve('@jitl/quickjs-wasmfile-release-sync/wasm')),
  join(output, 'emscripten-module.wasm'),
);
