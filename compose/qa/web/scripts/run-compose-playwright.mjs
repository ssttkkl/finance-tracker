import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { once } from "node:events";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const qaDirectory = path.resolve(scriptDirectory, "..");

async function reserveAvailablePort() {
  const server = createServer();
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const { port } = server.address();
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  return port;
}

const previewPort = process.env.FT_COMPOSE_PREVIEW_PORT ?? String(await reserveAvailablePort());
const runner = spawn(
  process.execPath,
  [
    path.join(qaDirectory, "node_modules/playwright/cli.js"),
    "test",
    "-c",
    path.join(qaDirectory, "playwright.config.ts"),
    ...process.argv.slice(2),
  ],
  {
    cwd: qaDirectory,
    env: { ...process.env, FT_COMPOSE_PREVIEW_PORT: previewPort },
    stdio: "inherit",
  },
);

process.once("SIGINT", () => runner.kill("SIGINT"));
process.once("SIGTERM", () => runner.kill("SIGTERM"));
runner.once("error", (error) => {
  console.error(`Unable to start Compose Playwright: ${error.message}`);
  process.exitCode = 1;
});
runner.once("exit", (code, signal) => {
  process.exitCode = code ?? (signal === "SIGINT" ? 130 : 1);
});
