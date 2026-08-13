import { defineConfig } from "@playwright/test";

const e2eWebPort = Number(process.env.FT_E2E_WEB_PORT ?? "5174");
const e2eWebUrl = `http://127.0.0.1:${e2eWebPort}`;

export default defineConfig({
  testDir: "./tests",
  testMatch: /(?:cash-ledger|cash-category-management)\.e2e\.ts/,
  use: { baseURL: e2eWebUrl, viewport: { width: 1440, height: 900 }, hasTouch: true },
  webServer: {
    command: `VITE_FT_API_ORIGIN=http://127.0.0.1:8765 npm run dev -- --port ${e2eWebPort}`,
    url: e2eWebUrl,
    reuseExistingServer: false,
  },
});
