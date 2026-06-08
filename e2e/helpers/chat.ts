import { expect, type Page } from "@playwright/test";

export async function waitForWebSocketConnected(page: Page) {
  await expect(page.getByText("🟢 Connected")).toBeVisible({
    timeout: 15_000,
  });
}

export async function sendChatMessage(page: Page, content: string) {
  const input = page.getByPlaceholder("Type a message...");
  await input.fill(content);
  await page.getByRole("button", { name: "Send" }).click();
}

export async function expectMessageVisible(page: Page, content: string) {
  await expect(page.getByText(content, { exact: true })).toBeVisible({
    timeout: 10_000,
  });
}
