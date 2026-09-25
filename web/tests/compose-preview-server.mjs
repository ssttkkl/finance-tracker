import { createServer, request as requestHttp } from "node:http";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const port = Number(process.env.FT_COMPOSE_PREVIEW_PORT ?? "5186");
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const composeRoot = path.join(repoRoot, "compose");
const bundleRoot = path.join(composeRoot, "webApp/build/dist/wasmJs/productionExecutable");
const composeResourceRoot = path.join(composeRoot, "webApp/build/processedResources/wasmJs/main");
const resourceRoot = path.join(composeRoot, "webApp/src/webMain/resources");
const configuredApiOrigin = process.env.FT_API_ORIGIN ?? "";
const apiProxyOrigin = new URL(process.env.FT_API_PROXY_ORIGIN ?? "http://127.0.0.1:8000");
const mimeTypes = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".svg": "image/svg+xml",
  ".txt": "text/plain; charset=utf-8",
  ".ttf": "font/ttf",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".wasm": "application/wasm",
};

if (apiProxyOrigin.protocol !== "http:" || apiProxyOrigin.pathname !== "/" || apiProxyOrigin.search || apiProxyOrigin.hash) {
  throw new Error("FT_API_PROXY_ORIGIN must be an http origin without a path, query, or fragment");
}

if (!existsSync(path.join(bundleRoot, "webApp.js"))) {
  throw new Error(`Compose Wasm production bundle not found: ${bundleRoot}`);
}

createServer((request, response) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    response.writeHead(405).end("Method not allowed");
    return;
  }
  const sendBody = request.method === "GET";

  const requestUrl = new URL(request.url ?? "/", "http://127.0.0.1");
  const pathname = requestUrl.pathname;
  if (pathname === "/health") {
    response.writeHead(200, { "content-type": "text/plain; charset=utf-8", "content-length": 2 }).end(sendBody ? "ok" : undefined);
    return;
  }

  if (pathname === "/config.js") {
    const body = Buffer.from(`window.FT_API_ORIGIN = window.FT_API_ORIGIN || ${JSON.stringify(configuredApiOrigin)};`);
    response.writeHead(200, {
      "content-type": "text/javascript; charset=utf-8",
      "content-length": body.byteLength,
      "cache-control": "no-store",
    }).end(sendBody ? body : undefined);
    return;
  }

  if (pathname === "/api" || pathname.startsWith("/api/")) {
    const upstreamUrl = new URL(`${pathname}${requestUrl.search}`, apiProxyOrigin);
    const headers = { ...request.headers };
    delete headers.host;
    delete headers.connection;
    const upstream = requestHttp(upstreamUrl, { method: request.method, headers }, (upstreamResponse) => {
      const responseHeaders = { ...upstreamResponse.headers };
      delete responseHeaders.connection;
      response.writeHead(upstreamResponse.statusCode ?? 502, responseHeaders);
      upstreamResponse.pipe(response);
    });
    upstream.on("error", () => {
      if (!response.headersSent) {
        response.writeHead(502, { "content-type": "application/json; charset=utf-8" });
      }
      response.end(JSON.stringify({ error: { code: "local_api_unavailable" } }));
    });
    request.pipe(upstream);
    return;
  }

  let relativePath;
  try {
    relativePath = decodeURIComponent(pathname).replace(/^\/+/, "");
  } catch {
    response.writeHead(400).end("Invalid URL path");
    return;
  }

  const candidate = relativePath && path.basename(relativePath).includes(".")
    ? [bundleRoot, composeResourceRoot, resourceRoot].map((root) => path.join(root, relativePath)).find(
      (file) => [bundleRoot, composeResourceRoot, resourceRoot].some((root) => file.startsWith(`${root}${path.sep}`)) && existsSync(file),
    )
    : path.join(resourceRoot, "index.html");
  const filePath = candidate && existsSync(candidate) ? candidate : path.join(resourceRoot, "index.html");
  const body = readFileSync(filePath);
  response.writeHead(200, {
    "content-type": mimeTypes[path.extname(filePath)] ?? "application/octet-stream",
    "content-length": body.byteLength,
  }).end(sendBody ? body : undefined);
}).listen(port, "127.0.0.1", () => {
  console.log(`Compose Web demo: http://127.0.0.1:${port}/`);
  console.log(`Local API proxy: ${process.env.FT_API_ORIGIN || "same-origin"} → ${apiProxyOrigin.origin}`);
});
