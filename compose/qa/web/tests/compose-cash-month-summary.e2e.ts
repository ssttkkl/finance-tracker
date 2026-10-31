import { expect, test } from "@playwright/test";
import { installComposeApiFixtures } from "./compose-fixtures";

for (const width of [390, 768, 1440]) {
  test(`month summary keeps each currency horizontal at ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const currencies = [
      { currency: "CNY", income: "12000", expense: "3511.5" },
      { currency: "HKD", income: "800", expense: "88" },
      { currency: "USD", income: "1500", expense: "47.99" },
    ];
    await installComposeApiFixtures(page, { cashSummaryCurrencies: currencies });
    await page.goto(`/w/${process.env.FT_COMPOSE_SUMMARY_DEMO ? 'demo-workspace' : 'workspace-1'}/`);
    const header = page.locator('[id="ledger.summary"]:visible').first();
    await expect(header).toBeVisible();
    const bounds = await header.boundingBox();
    const month = await header.getByText(/^2026年\d+月$/).boundingBox();
    if (!bounds || !month) throw new Error('Missing month header');
    let previousCurrencyBottom = -Infinity;
    for (const value of currencies) {
      const currency = await header.getByText(value.currency, { exact: true }).boundingBox();
      const income = await header.getByText(`收入 +${value.income}`, { exact: true }).boundingBox();
      const expense = await header.getByText(`支出 -${value.expense}`, { exact: true }).boundingBox();
      if (!currency || !income || !expense) throw new Error('Missing currency summary');
      const center = (box: { y: number; height: number }) => box.y + box.height / 2;
      expect(Math.abs(center(currency) - center(income))).toBeLessThan(3);
      expect(Math.abs(center(income) - center(expense))).toBeLessThan(3);
      expect(currency.x + currency.width).toBeLessThan(income.x);
      expect(income.x + income.width).toBeLessThan(expense.x);
      expect(Math.abs(expense.x + expense.width - bounds.x - bounds.width)).toBeLessThan(2);
      expect(currency.y).toBeGreaterThanOrEqual(previousCurrencyBottom);
      previousCurrencyBottom = currency.y + currency.height;
      if (width === 390) expect(currency.y).toBeGreaterThanOrEqual(month.y + month.height);
      else expect(month.x + month.width).toBeLessThan(currency.x);
    }
    expect(Math.abs(month.x - bounds.x)).toBeLessThan(2);
    if (width !== 390) {
      expect(month.y).toBeGreaterThan(bounds.y);
      expect(month.y + month.height).toBeLessThan(bounds.y + bounds.height);
    }
  });
}
