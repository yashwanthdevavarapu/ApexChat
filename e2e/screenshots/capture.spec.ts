/**
 * Generates the images in docs/screenshots/ from a live server.
 *
 *   cd e2e && npm run screenshots
 *
 * Four real users chat through the real UI, so every pixel (presence dots, unread badges,
 * typing indicator) is produced by the actual STOMP traffic rather than mocked.
 */
import { Browser, BrowserContext, Page, expect, test } from "@playwright/test";
import path from "node:path";
import { messageText, openDirect, openRoom, send, signUp, waitForLive } from "../tests/helpers";

const OUT = path.resolve(__dirname, "..", "..", "docs", "screenshots");
const shot = (page: Page, name: string) => page.screenshot({ path: path.join(OUT, name) });

type Person = { name: string; context: BrowserContext; page: Page };

async function person(browser: Browser, name: string, viewport = { width: 1440, height: 900 }): Promise<Person> {
  const context = await browser.newContext({ viewport, colorScheme: "dark", reducedMotion: "reduce" });
  const page = await context.newPage();
  await signUp(page, name);
  return { name, context, page };
}

test.describe.configure({ mode: "serial" });
test.setTimeout(180_000);

test("capture README screenshots", async ({ browser }) => {
  // --- Auth pages ---------------------------------------------------------
  const anon = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: "dark" });
  const anonPage = await anon.newPage();
  await anonPage.goto("/login");
  await shot(anonPage, "01-login.png");
  await anonPage.goto("/register");
  await anonPage.getByTestId("username").fill("maya");
  await shot(anonPage, "02-register.png");
  await anon.close();

  // --- Cast ---------------------------------------------------------------
  const maya = await person(browser, "maya");
  const dev = await person(browser, "dev");
  const priya = await person(browser, "priya");
  const sam = await person(browser, "sam");
  const everyone = [maya, dev, priya, sam];
  for (const p of everyone) await openRoom(p.page, "engineering");

  const script: [Person, string][] = [
    [dev, "Morning! Deploy #482 just went green on staging 🚀"],
    [priya, "Nice. Did the WebSocket heartbeat fix make it in?"],
    [dev, "Yep, server and client both heartbeat every 10s now, so idle proxies stop dropping connections."],
    [maya, "Ran the load test overnight: 2k concurrent sessions, p95 delivery under 15 ms."],
    [maya, "Full report: https://example.com/reports/load-test-482"],
    [sam, "That's huge. Promoting to prod after standup?"],
    [priya, "+1, I'll keep an eye on the dashboards 📈"],
  ];
  for (const [who, text] of script) {
    await send(who.page, text);
    await expect(messageText(maya.page, text)).toBeVisible();
  }

  // Activity elsewhere so Maya's sidebar shows unread badges
  await openRoom(sam.page, "random");
  await send(sam.page, "Who's up for ramen at 12:30? 🍜");
  await openDirect(sam.page, "maya");
  await send(sam.page, "Could you review my presence PR before 2pm?");
  await expect(maya.page.getByTestId("person-sam").locator(".badge")).toHaveText("1");

  // Priya starts typing -> indicator appears for Maya (it idles out after 2.5s, so be quick)
  await priya.page.getByTestId("message-input").pressSequentially("Let's ship it", { delay: 20 });
  await expect(maya.page.getByTestId("typing-indicator")).toContainText("priya is typing");
  await shot(maya.page, "03-chat-channel.png");
  await priya.page.getByTestId("message-input").fill("");

  // --- Direct message -----------------------------------------------------
  await openDirect(maya.page, "sam");
  await send(maya.page, "Sure! Looking at it now 👀");
  await openDirect(sam.page, "maya");
  await send(sam.page, "Thanks! The multi-tab presence logic is in PresenceService.");
  await expect(messageText(maya.page, "multi-tab presence")).toBeVisible();
  await shot(maya.page, "04-direct-message.png");

  // --- Create channel dialog ---------------------------------------------
  await openRoom(maya.page, "engineering");
  await maya.page.getByTestId("new-room-button").click();
  await maya.page.getByTestId("room-name").fill("release-train");
  await maya.page.getByTestId("room-description").fill("Coordinating the weekly production release");
  await shot(maya.page, "05-create-channel.png");
  await maya.page.getByTestId("room-create").click();
  await expect(dev.page.getByTestId("room-release-train")).toBeVisible();

  // --- Real-time, side by side -------------------------------------------
  await openRoom(maya.page, "release-train");
  await openRoom(dev.page, "release-train");
  await send(dev.page, "Release checklist is pinned. Go/no-go at 3pm.");
  await expect(messageText(maya.page, "Go/no-go")).toBeVisible();
  await send(maya.page, "Go from QA ✅");
  await expect(messageText(dev.page, "Go from QA")).toBeVisible();
  for (const p of [maya, dev]) await p.page.setViewportSize({ width: 1000, height: 600 });
  const left = (await maya.page.screenshot()).toString("base64");
  const right = (await dev.page.screenshot()).toString("base64");
  const composer = await browser.newPage({ viewport: { width: 2040, height: 700 } });
  await composer.setContent(`
    <body style="margin:0;background:#0b0d18;font:600 15px system-ui;color:#c7cbe0;display:flex;gap:20px;padding:20px;box-sizing:border-box">
      ${[["maya's browser", left], ["dev's browser", right]]
        .map(([label, img]) => `<figure style="margin:0;flex:1"><figcaption style="margin-bottom:10px">● ${label}</figcaption>
          <img style="width:100%;border-radius:10px;border:1px solid #262b40" src="data:image/png;base64,${img}"></figure>`)
        .join("")}
    </body>`);
  await composer.screenshot({ path: path.join(OUT, "06-realtime-side-by-side.png"), fullPage: true });
  await composer.close();

  // --- Mobile + light mode -----------------------------------------------
  const mobile = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
    colorScheme: "dark",
  });
  const phone = await mobile.newPage();
  await phone.goto("/login");
  await phone.getByTestId("username").fill("priya");
  await phone.getByTestId("password").fill("correct-horse-42");
  await phone.getByTestId("login-submit").click();
  await waitForLive(phone);
  await phone.goto("/chat#room/engineering");
  await expect(messageText(phone, "dashboards")).toBeVisible();
  await shot(phone, "07-mobile.png");
  await mobile.close();

  const light = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: "light" });
  const lightPage = await light.newPage();
  await lightPage.goto("/login");
  await lightPage.getByTestId("username").fill("dev");
  await lightPage.getByTestId("password").fill("correct-horse-42");
  await lightPage.getByTestId("login-submit").click();
  await waitForLive(lightPage);
  await lightPage.goto("/chat#room/engineering");
  await expect(messageText(lightPage, "dashboards")).toBeVisible();
  await shot(lightPage, "08-light-mode.png");
  await light.close();

  for (const p of everyone) await p.context.close();
});
