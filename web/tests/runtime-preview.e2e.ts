import { expect, test } from "@playwright/test";

test("生产预览读取自包含 API 的账户和收支投影", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByText("前端 API 地址无效。请设置 VITE_FT_API_ORIGIN 后重启。")).toHaveCount(0);
  await expect(page.getByRole("option", { name: "预览账户" })).toHaveCount(1);
  await expect(page.getByText("自包含预览投影")).toBeVisible();
  await page.getByRole("button", { name: "查看自包含预览投影的证据详情" }).click();
  await expect(page.getByRole("dialog", { name: "证据详情" })).toContainText("投影结果");
  await expect(page.getByText("无法读取证据详情。")).toHaveCount(0);
});
