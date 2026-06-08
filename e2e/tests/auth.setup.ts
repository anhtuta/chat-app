import path from "path";
import { test as setup, expect } from "@playwright/test";
import { SEED_USER } from "../helpers/test-users";
import { waitForWebSocketConnected } from "../helpers/chat";

const authFile = path.join(__dirname, "..", ".auth", "user.json");

setup("authenticate seed user", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Username").fill(SEED_USER.username);
  await page.getByLabel("Password").fill(SEED_USER.password);
  await page.getByRole("button", { name: "Login" }).click();

  await expect(page).toHaveURL(/\/group\/public/);
  await waitForWebSocketConnected(page);

  await page.context().storageState({ path: authFile });
});
