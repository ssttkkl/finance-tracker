import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  testMatch: "runtime-preview.e2e.ts",
  use: { baseURL: "http://127.0.0.1:5173", viewport: { width: 1440, height: 900 } },
  webServer: [
    {
      command: "FT_PREVIEW_API_PORT=8766 node tests/preview-api-server.mjs",
      url: "http://127.0.0.1:8766/health",
      reuseExistingServer: false,
    },
    {
      command: "VITE_FT_API_ORIGIN=http://127.0.0.1:8766 npm run build && npm run start",
      url: "http://127.0.0.1:5173",
      reuseExistingServer: false,
    },
  ],
});
