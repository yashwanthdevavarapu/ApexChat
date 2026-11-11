import { expect, test } from "@playwright/test";
import { messageText, newUser, openDirect, send } from "./helpers";

test.describe("Direct messages", () => {
  test("are delivered only to the two participants", async ({ browser }) => {
    const alice = await newUser(browser, "dm_a");
    const bob = await newUser(browser, "dm_b");
    const eve = await newUser(browser, "dm_eve");

    await openDirect(alice.page, bob.name);
    await send(alice.page, "psst, this is private");

    // Bob is in #general, so he sees an unread badge on Alice instead of the message
    const aliceEntry = bob.page.getByTestId(`person-${alice.name}`);
    await expect(aliceEntry.locator(".badge")).toHaveText("1");

    await openDirect(bob.page, alice.name);
    await expect(messageText(bob.page, "psst, this is private")).toBeVisible();
    await send(bob.page, "got it");
    await expect(messageText(alice.page, "got it")).toBeVisible();

    // Eve never receives it, live or via history
    await expect(eve.page.getByTestId(`person-${alice.name}`).locator(".badge")).toHaveCount(0);
    const res = await eve.page.request.get(`/api/dm/${alice.name}/messages`);
    expect(res.ok()).toBeTruthy();
    expect(await res.json()).toEqual([]);

    for (const u of [alice, bob, eve]) await u.context.close();
  });

  test("conversation history survives a reload", async ({ browser }) => {
    const alice = await newUser(browser, "dmh_a");
    const bob = await newUser(browser, "dmh_b");
    await openDirect(alice.page, bob.name);
    await send(alice.page, "remember me");

    await alice.page.reload();
    await expect(alice.page.getByTestId("conversation-title")).toHaveText(bob.name);
    await expect(messageText(alice.page, "remember me")).toBeVisible();

    await alice.context.close();
    await bob.context.close();
  });

  test("sending a message to a user who does not exist shows an error", async ({ browser }) => {
    const alice = await newUser(browser, "ghost");
    await alice.page.goto("/chat#dm/nobody_here_123");
    await expect(alice.page.getByTestId("toast")).toContainText("does not exist");
    await alice.context.close();
  });
});
