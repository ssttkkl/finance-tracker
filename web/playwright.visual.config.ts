import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  testMatch: "cash-ledger.visual.e2e.ts",
  use: { baseURL: "http://127.0.0.1:5175", viewport: { width: 1440, height: 900 }, hasTouch: true },
  webServer: {
    command: "VITE_FT_API_ORIGIN=http://127.0.0.1:8765 npm run dev -- --port 5175",
    url: "http://127.0.0.1:5175",
    reuseExistingServer: false,
  },
});
