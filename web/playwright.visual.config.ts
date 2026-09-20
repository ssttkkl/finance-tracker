import { defineConfig } from "@playwright/test";

const snapshotPathTemplate = process.env.CI
  ? "{snapshotDir}/{testFilePath}-snapshots/ci/{arg}{-projectName}{-platform}{ext}"
  : "{snapshotDir}/{testFilePath}-snapshots/{arg}{-projectName}{-platform}{ext}";

export default defineConfig({
  testDir: "./tests",
  testMatch: "cash-ledger.visual.e2e.ts",
  snapshotPathTemplate,
  use: {
    baseURL: "http://127.0.0.1:5175",
    viewport: { width: 1440, height: 900 },
    hasTouch: true,
    storageState: {
      origins: [{ origin: "http://127.0.0.1:5175", localStorage: [{ name: "finance-tracker:session-token", value: "visual-token" }] }],
    },
  },
  webServer: {
    command:
      "VITE_FT_API_ORIGIN=http://127.0.0.1:8765 npm run dev -- --port 5175",
    url: "http://127.0.0.1:5175",
    reuseExistingServer: false,
  },
});
