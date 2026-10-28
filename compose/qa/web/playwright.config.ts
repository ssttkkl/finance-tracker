import { defineConfig } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";

const composePort = Number(process.env.FT_COMPOSE_PREVIEW_PORT ?? "5186");
const composeUrl = `http://127.0.0.1:${composePort}`;
const chromePath = process.env.PLAYWRIGHT_CHROME_PATH;
const qaDirectory = path.dirname(fileURLToPath(import.meta.url));
const startScript = path.join(qaDirectory, "scripts/start-compose-demo.mjs");

export default defineConfig({
  testDir: "./tests",
  testMatch: "**/compose-*.e2e.ts",
  workers: 1,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: composeUrl,
    viewport: { width: 390, height: 844 },
    locale: "zh-CN",
    colorScheme: process.env.FT_COMPOSE_COLOR_SCHEME === "dark" ? "dark" : "light",
    launchOptions: chromePath ? { executablePath: chromePath } : {},
    trace: "retain-on-failure",
  },
  webServer: {
    command: `"${process.execPath}" "${startScript}"`,
    url: `${composeUrl}/health`,
    env: { ...process.env, FT_COMPOSE_PREVIEW_PORT: String(composePort) },
    // Always run the build freshness gate. Reusing a manually started server can
    // make QA pass against a bundle from an earlier checkout state.
    reuseExistingServer: false,
    // A fresh optimized Wasm build can exceed three minutes on local QA machines.
    timeout: 360_000,
  },
  reporter: "list",
});
