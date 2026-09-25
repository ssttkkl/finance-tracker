import { defineConfig } from "@playwright/test";

const composePort = Number(process.env.FT_COMPOSE_PREVIEW_PORT ?? "5186");
const composeUrl = `http://127.0.0.1:${composePort}`;
const chromePath = process.env.PLAYWRIGHT_CHROME_PATH;

export default defineConfig({
  testDir: "./tests",
  testMatch: "**/compose-*.e2e.ts",
  workers: 1,
  use: {
    baseURL: composeUrl,
    viewport: { width: 390, height: 844 },
    locale: "zh-CN",
    colorScheme: process.env.FT_COMPOSE_COLOR_SCHEME === "dark" ? "dark" : "light",
    launchOptions: chromePath ? { executablePath: chromePath } : {},
    trace: "retain-on-failure",
  },
  webServer: {
    command: "cd ../compose && ./gradlew :webApp:wasmJsBrowserDistribution --no-configuration-cache && cd ../web && node tests/compose-preview-server.mjs",
    url: `${composeUrl}/health`,
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
  },
  reporter: "list",
});
