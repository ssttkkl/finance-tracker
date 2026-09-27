import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { once } from "node:events";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const previewScript = path.join(repoRoot, "web/tests/compose-preview-server.mjs");

async function reservePort() {
  const server = createServer();
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const { port } = server.address();
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  return port;
}

async function waitForHealth(origin, child) {
  const deadline = Date.now() + 8_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`Compose preview exited early (${child.exitCode})`);
    try {
      const response = await fetch(`${origin}/health`);
      if (response.ok) return;
    } catch {
      await delay(50);
    }
  }
  throw new Error("Compose preview did not become healthy within 8 seconds");
}

test("Compose preview serves deep links from the origin root and proxies registration POST", async () => {
  let upstreamRequest;
  const apiServer = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    upstreamRequest = {
      method: request.method,
      url: request.url,
      contentType: request.headers["content-type"],
      body: Buffer.concat(chunks).toString("utf8"),
    };
    response.writeHead(422, { "content-type": "application/json; charset=utf-8" });
    response.end('{"error":{"code":"invalid_registration"}}');
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  const apiPort = apiServer.address().port;
  const previewPort = await reservePort();
  const previewProcess = spawn(process.execPath, [previewScript], {
    cwd: repoRoot,
    env: {
      ...process.env,
      FT_API_ORIGIN: "",
      FT_API_PROXY_ORIGIN: `http://127.0.0.1:${apiPort}`,
      FT_COMPOSE_PREVIEW_PORT: String(previewPort),
    },
    stdio: "ignore",
  });
  const previewOrigin = `http://127.0.0.1:${previewPort}`;

  try {
    await waitForHealth(previewOrigin, previewProcess);
    const configResponse = await fetch(`${previewOrigin}/config.js`);
    assert.equal(configResponse.status, 200);
    assert.match(await configResponse.text(), /window\.FT_API_ORIGIN = window\.FT_API_ORIGIN \|\| "" \|\| window\.location\.origin;/);

    const deepLinkResponse = await fetch(`${previewOrigin}/w/workspace-1/cash-import`);
    assert.equal(deepLinkResponse.status, 200);
    assert.match(await deepLinkResponse.text(), /<base href="\/">/);

    const payload = JSON.stringify({ email: "test@example.invalid", password: "not-a-real-password" });
    const response = await fetch(`${previewOrigin}/api/v1/auth/register?source=compose-demo`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: payload,
    });

    assert.equal(response.status, 422);
    assert.equal(response.headers.get("content-type"), "application/json; charset=utf-8");
    assert.equal(await response.text(), '{"error":{"code":"invalid_registration"}}');
    assert.deepEqual(upstreamRequest, {
      method: "POST",
      url: "/api/v1/auth/register?source=compose-demo",
      contentType: "application/json",
      body: payload,
    });
  } finally {
    previewProcess.kill("SIGTERM");
    await Promise.race([once(previewProcess, "exit"), delay(2_000)]);
    await new Promise((resolve, reject) => apiServer.close((error) => error ? reject(error) : resolve()));
  }
});
