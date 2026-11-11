import { expect, test } from "@playwright/test";
import { newUser, waitForLive } from "./helpers";

test.describe("Presence", () => {
  test("users appear online when they connect and disappear when they leave", async ({ browser }) => {
    const alice = await newUser(browser, "pres_a");
    const bob = await newUser(browser, "pres_b");

    const bobEntry = alice.page.getByTestId(`person-${bob.name}`);
    await expect(bobEntry).toBeVisible();
    await expect(bobEntry).toHaveAttribute("data-online", "true");
    // Bob gets the existing roster via the /app/presence snapshot, not just future events
    await expect(bob.page.getByTestId(`person-${alice.name}`)).toBeVisible();

    await bob.context.close();
    await expect(bobEntry).toHaveCount(0);
    await alice.context.close();
  });

  test("a user with several tabs stays online until the last tab closes", async ({ browser }) => {
    const watcher = await newUser(browser, "watch");
    const multi = await newUser(browser, "tabs");
    const secondTab = await multi.context.newPage(); // same cookies -> same user, new session
    await secondTab.goto("/chat");
    await waitForLive(secondTab);

    const entry = watcher.page.getByTestId(`person-${multi.name}`);
    await expect(entry).toHaveAttribute("data-online", "true");

    await multi.page.close();
    // Give the server time to process the disconnect, then confirm the user is still online
    await watcher.page.waitForTimeout(1000);
    await expect(entry).toHaveAttribute("data-online", "true");

    await secondTab.close();
    await expect(entry).toHaveCount(0);

    await multi.context.close();
    await watcher.context.close();
  });

  test("the sign-out button takes the user offline for everyone else", async ({ browser }) => {
    const alice = await newUser(browser, "stay");
    const bob = await newUser(browser, "leave");
    const entry = alice.page.getByTestId(`person-${bob.name}`);
    await expect(entry).toBeVisible();

    await bob.page.getByTestId("logout-button").click();
    await expect(entry).toHaveCount(0);

    await alice.context.close();
    await bob.context.close();
  });
});
