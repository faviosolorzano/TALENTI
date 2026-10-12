import { defineConfig } from '@playwright/test'

/**
 * End-to-end tests run against the REAL image (nginx with the real CSP), not against the dev
 * server:
 *   docker build -t talenti-frontend-d1 .
 *   docker run -d --name talenti-frontend-d1 -p 3001:8080 --read-only --tmpfs /tmp \
 *     --cap-drop ALL --security-opt no-new-privileges talenti-frontend-d1
 *   npm run test:e2e
 * Set E2E_BASE_URL to use another address. The tests use the Google Chrome installed on the
 * machine (`channel: 'chrome'`), so no browsers are downloaded.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3001',
    channel: 'chrome',
  },
  projects: [{ name: 'chrome' }],
})
