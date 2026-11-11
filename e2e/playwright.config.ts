import { defineConfig, devices } from "@playwright/test";

/**
 * By default Playwright boots the packaged jar (`mvn package` first) on an isolated port with a
 * fresh in-memory database. Point BASE_URL at an existing deployment to test that instead.
 */
const PORT = Number(process.env.PORT ?? 8085);
const baseURL = process.env.BASE_URL ?? `http://localhost:${PORT}`;
const isCI = !!process.env.CI;

export default defineConfig({
  timeout: 60_000,
  expect: { timeout: 8_000 },
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 1 : 0,
  workers: isCI ? 2 : 4,
  reporter: isCI
    ? [["github"], ["list"], ["html", { open: "never" }]]
    : [["list"], ["html", { open: "never" }]],
  use: {
    baseURL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: process.env.PW_VIDEO ? "retain-on-failure" : "off",
  },
  projects: [
    {
      name: "e2e",
      testDir: "./tests",
      use: { ...devices["Desktop Chrome"] },
    },
    {
      name: "screenshots",
      testDir: "./screenshots",
      fullyParallel: false,
      use: {
        ...devices["Desktop Chrome"],
        viewport: { width: 1440, height: 900 },
        colorScheme: "dark",
      },
    },
  ],
  webServer: process.env.BASE_URL
    ? undefined
    : {
        command: `java -jar ../target/apexchat.jar --server.port=${PORT}`,
        url: `${baseURL}/actuator/health/readiness`,
        reuseExistingServer: !isCI,
        timeout: 120_000,
        stdout: "ignore",
        stderr: "pipe",
      },
});
