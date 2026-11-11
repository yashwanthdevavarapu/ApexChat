import { expect, test } from "@playwright/test";
import { createRoom, newUser } from "./helpers";

test.describe("Channels", () => {
  test("seeded channels are listed", async ({ browser }) => {
    const user = await newUser(browser, "rooms");
    for (const slug of ["general", "engineering", "random"]) {
      await expect(user.page.getByTestId(`room-${slug}`)).toBeVisible();
    }
    await user.context.close();
  });

  test("a new channel appears for everyone without a reload", async ({ browser }) => {
    const alice = await newUser(browser, "creator");
    const bob = await newUser(browser, "viewer");

    const slug = await createRoom(alice.page, "launch");

    await expect(bob.page.getByTestId(`room-${slug}`)).toBeVisible();
    await expect(alice.page).toHaveURL(new RegExp(`#room/${slug}$`));

    await alice.context.close();
    await bob.context.close();
  });

  test("duplicate channel names are rejected", async ({ browser }) => {
    const user = await newUser(browser, "dupe");
    const slug = await createRoom(user.page, "dupe");

    await user.page.getByTestId("new-room-button").click();
    await user.page.getByTestId("room-name").fill(slug.toUpperCase());
    await user.page.getByTestId("room-create").click();
    await expect(user.page.getByTestId("room-error")).toContainText("already exists");
    await user.context.close();
  });

  test("deep links open the requested channel", async ({ browser }) => {
    const user = await newUser(browser, "deeplink");
    await user.page.goto("/chat#room/engineering");
    await expect(user.page.getByTestId("conversation-title")).toHaveText("# engineering");
    await expect(user.page.getByTestId("conversation-subtitle")).toContainText("deploys");
    await user.context.close();
  });
});
