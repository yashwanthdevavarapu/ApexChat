import { expect, test } from "@playwright/test";

test.describe("HTTP security", () => {
  test("the REST API returns 401 instead of a login redirect", async ({ request }) => {
    const res = await request.get("/api/rooms", { maxRedirects: 0 });
    expect(res.status()).toBe(401);
  });

  test("pages are served with a strict Content-Security-Policy", async ({ request }) => {
    const res = await request.get("/login");
    const csp = res.headers()["content-security-policy"];
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(res.headers()["x-content-type-options"]).toBe("nosniff");
  });

  test("the WebSocket endpoint refuses anonymous handshakes", async ({ page }) => {
    await page.goto("/login");
    const outcome = await page.evaluate(
      () =>
        new Promise<string>((resolve) => {
          const ws = new WebSocket(`ws://${location.host}/ws`);
          ws.onopen = () => resolve("open");
          ws.onerror = () => resolve("error");
          setTimeout(() => resolve("timeout"), 5000);
        }),
    );
    expect(outcome).toBe("error");
  });

  test("health probes are public", async ({ request }) => {
    const res = await request.get("/actuator/health/readiness");
    expect(res.ok()).toBeTruthy();
    expect(await res.json()).toMatchObject({ status: "UP" });
  });
});
