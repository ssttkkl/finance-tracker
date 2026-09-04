import { defineConfig } from "@playwright/test";

const previewWebPort = Number(process.env.FT_PREVIEW_WEB_PORT ?? "5173");
const previewWebUrl = `http://127.0.0.1:${previewWebPort}`;
const previewApiPort = Number(process.env.FT_PREVIEW_API_PORT ?? "8766");
const previewApiUrl = `http://127.0.0.1:${previewApiPort}`;

export default defineConfig({
  testDir: "./tests",
  testMatch: /(?:runtime-preview|workspace-entry)\.e2e\.ts/,
  use: { baseURL: previewWebUrl, viewport: { width: 1440, height: 900 } },
  webServer: [
    {
      command: `FT_PREVIEW_API_PORT=${previewApiPort} FT_PREVIEW_WEB_ORIGIN=${previewWebUrl} node tests/preview-api-server.mjs`,
      url: `${previewApiUrl}/health`,
      reuseExistingServer: false,
    },
    {
      command: `VITE_FT_API_ORIGIN=${previewApiUrl} npm run build && npm run start -- --port ${previewWebPort}`,
      url: previewWebUrl,
      reuseExistingServer: false,
    },
  ],
});
