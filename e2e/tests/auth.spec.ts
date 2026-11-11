import { expect, test } from "@playwright/test";
import { PASSWORD, login, register, uniqueUser, waitForLive } from "./helpers";

test.describe("Authentication", () => {
  test("anonymous visitors are redirected to the sign-in page", async ({ page }) => {
    await page.goto("/chat");
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
  });

  test("a new user can register, sign in and reach a live chat", async ({ page }) => {
    const name = uniqueUser("alice");
    await register(page, name);

    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByTestId("registered-banner")).toContainText(name);
    await expect(page.getByTestId("username")).toHaveValue(name);

    await login(page, name);
    await expect(page.getByTestId("current-user")).toHaveText(name);
    await expect(page.getByTestId("conversation-title")).toHaveText("# general");
    await waitForLive(page);
  });

  test("usernames are case-insensitive", async ({ page }) => {
    const name = uniqueUser("casey");
    await register(page, name);
    await login(page, name.toUpperCase());
    await expect(page.getByTestId("current-user")).toHaveText(name);
  });

  test("a wrong password is rejected", async ({ page }) => {
    const name = uniqueUser("bob");
    await register(page, name);
    await page.getByTestId("username").fill(name);
    await page.getByTestId("password").fill("not-the-password");
    await page.getByTestId("login-submit").click();
    await expect(page.getByTestId("login-error")).toBeVisible();
  });

  test("registration validates input and rejects duplicates", async ({ page }) => {
    await register(page, "x!", "short");
    await expect(page.getByTestId("username-error")).toContainText("letters, digits or underscores");
    await expect(page.getByText("Password must be 8-72 characters")).toBeVisible();

    await page.getByTestId("username").fill(uniqueUser("mismatch"));
    await page.getByTestId("password").fill(PASSWORD);
    await page.getByTestId("confirm-password").fill(PASSWORD + "x");
    await page.getByTestId("register-submit").click();
    await expect(page.getByText("Passwords do not match")).toBeVisible();

    const taken = uniqueUser("dupe");
    await register(page, taken);
    await register(page, taken);
    await expect(page.getByTestId("username-error")).toHaveText("That username is already taken");
  });

  test("signing out ends the session", async ({ page }) => {
    const name = uniqueUser("carol");
    await register(page, name);
    await login(page, name);

    await page.getByTestId("logout-button").click();
    await expect(page).toHaveURL(/\/login\?logout/);
    await page.goto("/chat");
    await expect(page).toHaveURL(/\/login$/);
  });
});
