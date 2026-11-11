import { Browser, BrowserContext, Page, expect } from "@playwright/test";

export const PASSWORD = "correct-horse-42";

/** A username unique across parallel workers and repeated runs (max 20 chars). */
export function uniqueUser(prefix = "u"): string {
  const stamp = Date.now().toString(36).slice(-6);
  const rand = Math.random().toString(36).slice(2, 6);
  return `${prefix.slice(0, 8)}_${stamp}${rand}`;
}

export async function register(page: Page, username: string, password = PASSWORD) {
  await page.goto("/register");
  await page.getByTestId("username").fill(username);
  await page.getByTestId("password").fill(password);
  await page.getByTestId("confirm-password").fill(password);
  await page.getByTestId("register-submit").click();
}

export async function login(page: Page, username: string, password = PASSWORD) {
  if (!page.url().includes("/login")) await page.goto("/login");
  await page.getByTestId("username").fill(username);
  await page.getByTestId("password").fill(password);
  await page.getByTestId("login-submit").click();
  await expect(page).toHaveURL(/\/chat/);
  await waitForLive(page);
}

/** Registers a brand-new account (or signs in if it already exists) and waits for the socket. */
export async function signUp(page: Page, username: string) {
  await register(page, username);
  if (await page.getByTestId("username-error").isVisible()) {
    await page.goto("/login");
  } else {
    await expect(page.getByTestId("registered-banner")).toBeVisible();
  }
  await login(page, username);
}

/** Opens an isolated browser session (own cookies = own STOMP session) for a new user. */
export async function newUser(
  browser: Browser,
  prefix: string,
): Promise<{ context: BrowserContext; page: Page; name: string }> {
  const context = await browser.newContext();
  const page = await context.newPage();
  const name = uniqueUser(prefix);
  await signUp(page, name);
  return { context, page, name };
}

export async function waitForLive(page: Page) {
  await expect(page.getByTestId("connection-status")).toHaveAttribute("data-state", "online");
}

export async function openRoom(page: Page, slug: string) {
  await page.getByTestId(`room-${slug}`).click();
  await expect(page.getByTestId("conversation-title")).toHaveText(`# ${slug}`);
}

export async function openDirect(page: Page, username: string) {
  await page.getByTestId(`person-${username}`).click();
  await expect(page.getByTestId("conversation-title")).toHaveText(username);
}

export async function send(page: Page, text: string) {
  const input = page.getByTestId("message-input");
  await expect(input).toBeEnabled();
  await input.fill(text);
  await input.press("Enter");
  await expect(input).toHaveValue("");
}

export function messageText(page: Page, text: string) {
  return page.getByTestId("message-text").filter({ hasText: text });
}

/** Creates a uniquely named channel through the UI and returns its slug. */
export async function createRoom(page: Page, prefix = "room"): Promise<string> {
  const slug = `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
  await page.getByTestId("new-room-button").click();
  await page.getByTestId("room-name").fill(slug);
  await page.getByTestId("room-create").click();
  await expect(page.getByTestId("conversation-title")).toHaveText(`# ${slug}`);
  return slug;
}
