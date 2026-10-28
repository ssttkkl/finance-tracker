import { defineConfig } from '@playwright/test';
import path from 'node:path';
export default defineConfig({
  testDir: path.resolve('compose/qa/web/tests'),
  testMatch: 'compose-cash-detail.e2e.ts',
  workers: 1,
  timeout: 90000,
  expect: { timeout: 15000 },
  use: { baseURL: 'http://127.0.0.1:5240', viewport: { width: 390, height: 844 }, locale: 'zh-CN', colorScheme: 'light' },
  reporter: 'list',
});
