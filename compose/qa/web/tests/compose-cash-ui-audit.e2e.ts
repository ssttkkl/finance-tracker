import { expect, test, type Page } from "@playwright/test";
import { installComposeApiFixtures } from "./compose-fixtures";

async function click(page: Page, id: string) {
  const target = page.locator(`[id="${id}"]:visible`).last();
  await expect(target).toBeVisible();
  const box = await target.boundingBox();
  if (!box) throw new Error(`Missing ${id}`);
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await page.waitForTimeout(400);
}

for (const width of [390, 1440]) {
  test(`audit filters apply once and discard local reset at ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const api = await installComposeApiFixtures(page);
    await page.goto('/w/workspace-1/');
    const moreBox = await page.locator('[id="ledger.more-filters"]:visible').last().boundingBox();
    if (!moreBox) throw new Error('Missing filter opener');
    const reopen = async () => {
      await page.mouse.click(moreBox.x + moreBox.width / 2, moreBox.y + moreBox.height / 2);
      await page.waitForTimeout(400);
    };
    await click(page, 'ledger.more-filters');
    const calls = () => api.calls.filter(c => c.path.startsWith('/api/v1/cash-projections?'));
    const before = calls().length;
    const field = page.locator('[id="ledger.filter.amount-minimum"]:visible').last();
    const bounds = await field.boundingBox();
    if (!bounds) throw new Error('Missing minimum amount');
    // Compose Web exposes the compact sheet field at its unscrolled semantics position.
    // The actual visible field is at this point in the fixed 390 × 900 viewport.
    if (width === 390) await page.mouse.click(190, 625);
    else await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    await page.keyboard.type('25', {delay:100});
    await expect.poll(() => field.textContent()).toBe('25');
    await page.waitForTimeout(600);
    expect(calls().length).toBe(before);
    await click(page, 'ledger.filter.apply');
    await expect.poll(() => calls().some(c => c.path.includes('amount_min=25'))).toBe(true);
    await reopen();
    await click(page, 'ledger.filter.reset');
    const afterApply = calls().length;
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);
    expect(calls().length).toBe(afterApply);
    await reopen();
    await expect.poll(() => page.locator('[id="ledger.filter.amount-minimum"]:visible').last().textContent()).toBe('25');
    await click(page, 'ledger.filter.reset');
    await click(page, 'ledger.filter.apply');
    await expect.poll(() => calls().length).toBeGreaterThan(afterApply);
    expect(calls().at(-1)?.path).not.toContain('amount_min=');
  });
}

test('compact ledger keeps every quick type visible without a menu', async ({page}) => {
  await page.setViewportSize({width:390,height:900});
  await installComposeApiFixtures(page, {cashPaginatedPageCount:1});
  await page.goto('/w/workspace-1/');
  for (const type of ['all', 'expense', 'income', 'internal_transfer']) {
    await expect(page.locator(`[id="ledger.filter.quick-type-${type}"]:visible`)).toBeVisible();
  }
  await expect(page.locator('[id="ledger.filter.types"]:visible')).toHaveCount(0);
  await page.setViewportSize({width:1440,height:900});
});

test('audit desktop row opens from amount and checkbox keeps detail closed', async ({page}) => {
  await page.setViewportSize({width:1440,height:900});
  await installComposeApiFixtures(page);
  await page.goto('/w/workspace-1/');
  await click(page, 'ledger.record-checkbox-projection-1');
  await expect(page.locator('[id="cash.current.detail"]:visible')).toHaveCount(0);
  const row=page.locator('[id="ledger.record-projection-1"]:visible').last();
  await expect(row).toBeVisible();
  const box=await row.boundingBox();
  if(!box) throw new Error('Missing row');
  await page.mouse.click(box.x+box.width*0.85, box.y+box.height/2);
  await expect(page.locator('[id="cash.current.detail"]:visible')).toHaveCount(1);
});

test('ledger scrollbar follows small drags within one row', async ({page}) => {
  await page.setViewportSize({width:1440,height:900});
  await installComposeApiFixtures(page, {cashPaginatedPageCount:1});
  await page.goto('/w/workspace-1/');
  const thumb=page.locator('[id="ledger.scrollbar.thumb"]:visible').last();
  const rail=page.locator('[id="ledger.scrollbar"]:visible').last();
  const row=page.locator('[id="ledger.record-cash-scroll-1-5"]:visible').last();
  await expect(thumb).toBeVisible();
  await expect(row).toBeVisible();
  const box=await thumb.boundingBox();
  const railBox=await rail.boundingBox();
  if (!box || !railBox) throw new Error('Missing scrollbar');
  const rowY=async () => (await row.boundingBox())?.y ?? NaN;
  const before=await rowY();
  const x=railBox.x+railBox.width-2, y=railBox.y+box.height/2;
  await page.mouse.move(x,y);
  await page.mouse.down();
  await page.mouse.move(x,y+4,{steps:4});
  await expect.poll(rowY).toBeLessThan(before-2);
  const afterFour=await rowY();
  await page.mouse.move(x,y+8,{steps:4});
  await expect.poll(rowY).toBeLessThan(afterFour-2);
  await page.mouse.up();
});
