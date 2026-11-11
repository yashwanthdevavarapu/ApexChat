import { expect, test } from "@playwright/test";
import { createRoom, messageText, newUser, openRoom, send } from "./helpers";

test.describe("Channel messaging", () => {
  test("a message is pushed to every member of the channel in real time", async ({ browser }) => {
    const alice = await newUser(browser, "alice");
    const bob = await newUser(browser, "bob");
    const slug = await createRoom(alice.page, "sync");
    await expect(bob.page.getByTestId(`room-${slug}`)).toBeVisible();
    await openRoom(bob.page, slug);

    await send(alice.page, "Hello from Alice 👋");

    // No reload: Bob receives the frame over the open WebSocket
    await expect(messageText(bob.page, "Hello from Alice 👋")).toBeVisible();
    await expect(messageText(alice.page, "Hello from Alice 👋")).toBeVisible();
    await expect(
      bob.page.getByTestId("message").filter({ hasText: "Hello from Alice" }),
    ).toHaveAttribute("data-sender", alice.name);

    await send(bob.page, "Hi Alice!");
    await expect(messageText(alice.page, "Hi Alice!")).toBeVisible();

    await alice.context.close();
    await bob.context.close();
  });

  test("history is persisted and reloaded in order", async ({ browser }) => {
    const user = await newUser(browser, "hist");
    const slug = await createRoom(user.page, "history");
    for (const text of ["first", "second", "third"]) await send(user.page, text);
    await expect(messageText(user.page, "third")).toBeVisible();

    await user.page.reload();
    await expect(user.page.getByTestId("conversation-title")).toHaveText(`# ${slug}`);
    await expect(user.page.getByTestId("message-text")).toHaveText(["first", "second", "third"]);

    await user.context.close();
  });

  test("other users see a typing indicator", async ({ browser }) => {
    const alice = await newUser(browser, "typer");
    const bob = await newUser(browser, "watcher");
    const slug = await createRoom(alice.page, "typing");
    await openRoom(bob.page, slug);

    await alice.page.getByTestId("message-input").pressSequentially("thinking", { delay: 30 });
    await expect(bob.page.getByTestId("typing-indicator")).toHaveText(`${alice.name} is typing…`);

    await alice.page.getByTestId("message-input").press("Enter");
    await expect(bob.page.getByTestId("typing-indicator")).toHaveText("");

    await alice.context.close();
    await bob.context.close();
  });

  test("unread badges count messages in channels you are not viewing", async ({ browser }) => {
    const alice = await newUser(browser, "loud");
    const bob = await newUser(browser, "quiet");
    const slug = await createRoom(alice.page, "badges");
    await openRoom(bob.page, "random");

    await send(alice.page, "one");
    await send(alice.page, "two");

    const roomButton = bob.page.getByTestId(`room-${slug}`);
    await expect(roomButton.locator(".badge")).toHaveText("2");
    await expect(bob.page).toHaveTitle(/^\(2\) ApexChat/);

    await roomButton.click();
    await expect(roomButton.locator(".badge")).toHaveCount(0);
    await expect(messageText(bob.page, "two")).toBeVisible();

    await alice.context.close();
    await bob.context.close();
  });

  test("message content is rendered as text, never as HTML", async ({ browser }) => {
    const user = await newUser(browser, "xss");
    await createRoom(user.page, "xss");
    const payload = `<img src=x onerror="document.body.dataset.pwned='1'">`;

    await send(user.page, payload);

    await expect(messageText(user.page, payload)).toBeVisible();
    await expect(user.page.locator("#message-list img")).toHaveCount(0);
    expect(await user.page.evaluate(() => document.body.dataset.pwned)).toBeUndefined();
    await user.context.close();
  });

  test("links become safe anchors and multi-line text is preserved", async ({ browser }) => {
    const user = await newUser(browser, "links");
    await createRoom(user.page, "links");
    const input = user.page.getByTestId("message-input");
    await input.fill("line one");
    await input.press("Shift+Enter");
    await input.pressSequentially("docs: https://spring.io/guides");
    await input.press("Enter");

    const link = user.page.getByRole("link", { name: "https://spring.io/guides" });
    await expect(link).toHaveAttribute("rel", "noopener noreferrer");
    await expect(link).toHaveAttribute("target", "_blank");
    await expect(messageText(user.page, "line one")).toContainText("line one\ndocs:");
    await user.context.close();
  });
});
