import { defineConfig } from '@playwright/test';

/**
 * Phase 13E browser tests.
 *
 * The backend is started with the `e2e` profile and the TEST classpath, which
 * gives it an in-memory H2 database and the Playwright seed data. The real
 * MySQL database is never used. Both servers are started/reused by Playwright.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 40_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'off',
    screenshot: 'only-on-failure',
  },
  webServer: [
    {
      command: 'npx vite --port 5173 --strictPort',
      url: 'http://localhost:5173',
      reuseExistingServer: true,
      timeout: 120_000,
    },
    {
      command: 'node e2e/start-backend.mjs',
      url: 'http://localhost:8080/actuator/health',
      reuseExistingServer: true,
      timeout: 300_000,
    },
  ],
});
