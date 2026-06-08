import { test, expect } from "@playwright/test";
import { SEED_GROUP_NAME } from "../helpers/test-users";
import {
  expectMessageVisible,
  sendChatMessage,
  waitForWebSocketConnected,
} from "../helpers/chat";

test.describe("Chat page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/group/public");
    await waitForWebSocketConnected(page);
  });

  test("shows sidebar with public chat and seeded groups", async ({ page }) => {
    await expect(page.getByRole("heading", { name: /💬 Chats/ })).toBeVisible();
    await expect(page.getByRole("button", { name: "Public Chat Everyone" })).toBeVisible();
    await expect(page.getByText("Everyone")).toBeVisible();
    await expect(page.getByRole("button", { name: "New Group" })).toBeVisible();

    await expect(page.getByText(SEED_GROUP_NAME, { exact: true })).toBeVisible({
      timeout: 15_000,
    });
  });

  test("shows chat area with composer on public chat", async ({ page }) => {
    await expect(page.getByText("Public Chat").first()).toBeVisible();
    await expect(page.getByPlaceholder("Type a message...")).toBeVisible();
    await expect(page.getByRole("button", { name: "Send" })).toBeVisible();
  });

  test("sends a message in public chat", async ({ page }) => {
    const message = `e2e-public-${Date.now()}`;

    await sendChatMessage(page, message);
    await expectMessageVisible(page, message);
  });

  test("switchToChat via sidebar navigates to a group and sends a message", async ({ page }) => {
    await page.getByText(SEED_GROUP_NAME, { exact: true }).click();

    await expect(page).toHaveURL(/\/group\/1/);
    await expect(page.getByText(SEED_GROUP_NAME).first()).toBeVisible();

    const message = `e2e-group-${Date.now()}`;
    await sendChatMessage(page, message);
    await expectMessageVisible(page, message);
  });

  test("switchToChat via URL param loads the correct group", async ({ page }) => {
    await page.goto("/group/2");
    await waitForWebSocketConnected(page);

    await expect(page).toHaveURL(/\/group\/2/);
    await expect(page.getByText("Group 2").first()).toBeVisible();
  });

  test("switch back to public chat from sidebar", async ({ page }) => {
    await page.getByText(SEED_GROUP_NAME, { exact: true }).click();
    await expect(page).toHaveURL(/\/group\/1/);

    await page.getByText("Public Chat", { exact: true }).click();
    await expect(page).toHaveURL(/\/group\/public/);
    await expect(page.getByText("Public Chat").first()).toBeVisible();
  });
});
