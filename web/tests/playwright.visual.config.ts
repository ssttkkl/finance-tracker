import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  testMatch: "cash-ledger.visual.e2e.ts",
  use: { baseURL: "http://127.0.0.1:5174", viewport: { width: 1440, height: 900 }, hasTouch: true },
  webServer: {
    command: "VITE_FT_API_ORIGIN=http://127.0.0.1:8765 npm run dev -- --port 5174",
    url: "http://127.0.0.1:5174",
    reuseExistingServer: false,
  },
});
