import { test, expect } from "@playwright/test";
import { SEED_USER } from "../helpers/test-users";
import { waitForWebSocketConnected } from "../helpers/chat";

test.describe("Login page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/login");
  });

  test("renders login form", async ({ page }) => {
    await expect(page.getByText("💬 Chat App")).toBeVisible();
    await expect(page.getByText("Login to continue")).toBeVisible();
    await expect(page.getByLabel("Username")).toBeVisible();
    await expect(page.getByLabel("Password")).toBeVisible();
    await expect(page.getByRole("button", { name: "Login" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Register" })).toBeVisible();
  });

  test("shows error for invalid credentials", async ({ page }) => {
    await page.getByLabel("Username").fill("invalid-user");
    await page.getByLabel("Password").fill("wrong-password");
    await page.getByRole("button", { name: "Login" }).click();

    await expect(page.getByRole("alert")).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
  });

  test("logs in with seed account and lands on public chat", async ({ page }) => {
    await page.getByLabel("Username").fill(SEED_USER.username);
    await page.getByLabel("Password").fill(SEED_USER.password);
    await page.getByRole("button", { name: "Login" }).click();

    await expect(page).toHaveURL(/\/group\/public/);
    await waitForWebSocketConnected(page);
    await expect(page.getByRole("heading", { name: "Public Chat" })).toBeVisible();
  });
});
