import { test, expect, type Locator, type Page } from "@playwright/test";
import { installComposeApiFixtures, captureBrowserDiagnostics } from "./compose-fixtures";

async function press(page: Page, target: string | Locator) {
  if (typeof target !== "string") {
    await page.mouse.move(1100, 720);
    for (let attempt = 0; attempt < 24; attempt++) {
      const box = await target.boundingBox({ timeout: 200 }).catch(() => null);
      if (box && box.width > 0 && box.height > 0 && box.y > 64 && box.y + box.height < 880) {
        await page.waitForTimeout(200);
        const settled = await target.boundingBox();
        if (!settled || Math.abs(settled.y - box.y) > 1 || Math.abs(settled.x - box.x) > 1) continue;
        await page.mouse.click(settled.x + settled.width / 2, settled.y + settled.height / 2);
        await page.waitForTimeout(350);
        return;
      }
      await page.mouse.wheel(0, box && box.y > 0 && box.y < 64 ? -320 : 320);
      await page.waitForTimeout(150);
    }
    throw new Error("Missing visible control");
  }
  await page.mouse.move(1340, 650); await page.mouse.wheel(0, -5000); await page.waitForTimeout(200);
  const locator = page.locator(`[id="${target}"]:visible`).last();
  for (let attempt = 0; attempt < 24; attempt++) {
    const box = await locator.boundingBox({ timeout: 200 }).catch(() => null);
    if (box && box.y > 64 && box.y + box.height < 880) {
      await page.waitForTimeout(200);
      const settled = await locator.boundingBox();
      if (!settled || Math.abs(settled.y - box.y) > 1 || Math.abs(settled.x - box.x) > 1) continue;
      await page.mouse.click(settled.x + settled.width / 2, settled.y + settled.height / 2);
      await page.waitForTimeout(350); return;
    }
    await page.mouse.move(1340, 650); await page.mouse.wheel(0, box && box.y < 64 ? -320 : 320); await page.waitForTimeout(150);
  }
  throw new Error(`Missing visible ${target}`);
}

for (const scenario of ["accept", "reject", "missing", "stale", "unknown", "joint", "p2p", "remove", "remove-clean", "multi-component", "unrelated-conflict", "transfer-subtype", "remove-toggle", "reject-cancel"] as const) test(`v6 relation flow: ${scenario}`, async ({ page }, info) => {
  const removing = scenario.startsWith("remove");
  const kind = scenario === "p2p" ? "refund_offset" : "payment_mirror";
  const subtype = scenario === "p2p" ? "p2p_return" : "";
  await page.setViewportSize({ width: 1440, height: 900 });
  const diagnostics = captureBrowserDiagnostics(page);
  await installComposeApiFixtures(page);
  const contextId = "a".repeat(43);
  const planId = `mp_${contextId}.${"b".repeat(16)}`;
  const fields = (id: string) => ({ amount: "-100.00", currency: "CNY", account_id: "1", occurred_at: "2026-10-24T04:00:00Z",
    counterparty: id === "11" ? "本笔" : `候选成员${id}`, note: "当前人工备注", source_payload: { 原始单号: "fixture-" + id }, components: (scenario === "multi-component" ? [1,2] : [1]).map(n => ({ component_id: id + "0" + n, amount: scenario === "multi-component" ? "-50.00" : "-100.00", currency: "CNY", account_id: String(n), account_name: n === 1 ? "银行卡" : "零钱", label: n === 1 ? "银行卡部分" : "零钱部分" })) });
  let reads = 0;
  await page.route(/\/api\/v1\/cash-projections(?:\?|$)/, route => route.fulfill({ json: {
    items: [{ projection_id: "relation-11", transaction_id: "11", record_id: "11", ...fields("11"), economic_type: "expense",
      state: "posted", confirmation_status: "pending", statistics_eligible: true, attention_count: 1 }],
    start_index: 0, total_count: 1, items_before: 0, items_after: 0, month_positions: [],
    next_cursor: null, projection_version: 9, pending_item_count: 1, page_size: 20, filters: {}, filter_options: {}, monthly_summaries: [],
  } }));
  let accepted = false;
  const projectionRef = (id: string) => removing ? { transaction_id: id, projection_id: "relation-11", projection_version: 9, attention_count: 0 } : null;
  await page.route(/\/api\/v1\/cash-projections\/relation-11$/, route => route.fulfill({ json: { projection_version: 9, members: ["11", "21"].map(id => ({ transaction_id: id, row_revision: 3, confirmation_status: "confirmed" })) } }));
  await page.route(/\/api\/v1\/evidence\/cash-projections\/relation-11$/, route => route.fulfill({ json: { projection_version: 9, projection: { projection_id: "relation-11", transaction_id: "11", record_id: "11", ...fields("11"), economic_type: "expense", member_count: 2, projection_version: 9 }, members: ["11", "21"].map(id => ({ id, row_revision: 3, ...fields(id), account_name: "日常账户", components: [] })) } }));
  await page.route(/\/api\/v1\/cash-records\/11\/association-targets(?:\?.*)?$/, route => route.fulfill({ json: { items: [{ transaction_id: "21", state: "posted", row_revision: 3, record_origin: "import", confirmation_status: "confirmed", fields: fields("21"), editable_fields: [], issues: [], relations: [] }], next_cursor: null } }));
  await page.route(/\/api\/v1\/cash-records\/(11|21|22)$/, route => {
    const id = new URL(route.request().url()).pathname.split("/").at(-1)!;
    if (id === "11") reads++;
    return route.fulfill({ json: { transaction_id: id, state: "posted", row_revision: 3, record_origin: "import",
      projection_ref: projectionRef(id), confirmation_status: id === "11" && !removing ? "pending" : "confirmed", fields: fields(id), editable_fields: [], issues: [],
      relations: id === "11" || removing ? [{ relation_id: "7", kind, subtype, state: accepted || removing ? "confirmed" : "draft", active: true, row_revision: 1 }] : [] } });
  });
  const previews: Record<string, unknown>[] = [];
  await page.route(/\/api\/v1\/cash-mutations\/plans$/, async route => {
    const input = route.request().postDataJSON(); previews.push(input);
    if (scenario === "stale" && input.relation_choices?.length) return route.fulfill({ status: 409, json: { error: { code: "record_changed", message: "changed" } } });
    await route.fulfill({ json: { plan_id: planId, edit_context_id: contextId, plan_revision: previews.length,
      expires_at: "2099-01-01T00:00:00Z", summary: {}, item_counts: {}, can_commit: true,
      items: [{ preview_item_id: "one", confirmation_status: "pending", anticipated_state: "posted", display: { ...fields("11"), accounts: ["日常账户"] },
        statistics_eligible: true, member_refs: [{ transaction_id: "11" }], attention_reasons: [], detail_available: true }],
      pages: { items: null }, additional_effects: scenario === "missing" && input.relation_choices?.length ? [{ kind: "relation_changed", reference: "missing" }] : [], invalidated_choices: [] } });
  });
  await page.route(/\/api\/v1\/cash-mutations\/plans\/[^/]+\/items\/one$/, route => {
    const submitted = previews.at(-1)?.relation_choices as Array<{ suggestion_id: string; action: string; candidate_id?: string }> | undefined;
    const accepted = submitted?.find(choice => choice.suggestion_id === "group" && choice.action === "accept");
    return route.fulfill({ json: {
      preview_item_id: "one", members: [{ transaction_id: "11", planned_action: "unchanged", fields: fields("11"), editable_fields: [], allowed_actions: [], row_revision: 3 }],
      field_diffs: [], issues: [], relation_effects: [], relation_choices: [{ suggestion_id: "group", kind, subtype, relation_ids: removing ? ["7"] : [],
        relations: scenario === "joint" ? [{ kind, subtype, member_refs: [{ transaction_id: "11" }, { transaction_id: "21" }] }, { kind: "refund_offset", subtype: "", member_refs: [{ transaction_id: "21" }, { transaction_id: "22" }] }] : [{ kind, subtype, member_refs: [{ transaction_id: "11" }, { transaction_id: "21" }] }],
        split_field_diffs: (scenario === "remove" || scenario === "remove-toggle") ? [{ field: "note", current: "当前人工备注", restored: "原始备注" }] : [],
        action: accepted ? "accept" : "defer", candidate_id: accepted?.candidate_id ?? null,
        member_refs: [{ transaction_id: "11" }, { transaction_id: "21" }], candidates: [{ candidate_id: "candidate", member_refs: [{ transaction_id: "21" }, { transaction_id: "22" }] }],
        allowed_actions: removing ? ["remove"] : ["accept", "defer", "ignore"], common_fields: {}, common_field_conflicts: {} }, ...(scenario === "unrelated-conflict" ? [{suggestion_id:"unrelated",kind:"refund_offset",subtype:"",action:"defer",member_refs:[{transaction_id:"11"},{transaction_id:"22"}],candidates:[],allowed_actions:["ignore"],common_field_conflicts:{note:["unrelated one","unrelated two"]}}] : [])],
    } });
  });
  let commits = 0;
  await page.route(/\/api\/v1\/cash-mutations\/plans\/[^/]+\/commit$/, async route => {
    commits++;
    if (scenario === "unknown") return route.abort("failed");
    accepted = true;
    await route.fulfill({ json: { receipt_id: "receipt-relation", plan_id: planId, operation_kind: "maintain_relations",
      committed: true, created_at: "2026-10-24T04:00:00Z", status: "completed", summary: {}, retained_transaction_id: "11" } });
  });
  const resolutions: any[] = [];
  await page.route(/\/api\/v1\/cash-mutations\/resolve$/, route => { resolutions.push(route.request().postDataJSON()); return route.fulfill({ json: { status: "pending", receipt: null } }); });
  await page.goto("/w/workspace-1/");
  await press(page, "ledger.open-record");
  const suggestion = page.locator('[id="cash.current.suggestion-group"]:visible').last();
  if (scenario === "transfer-subtype") await press(page, "cash.current.add-association");
  else if (removing) await press(page, "cash.current.remove-21");
  else {
    await expect(suggestion).toBeVisible();
    await press(page, suggestion.getByText(scenario.startsWith("reject") ? "拒绝建议" : scenario === "joint" ? "核对建议" : "接受建议", { exact: true }));
  }
  const dialog = page.locator('[id="cash.association.dialog"]:visible').last();
  const continueButton = dialog.locator('[id="cash.association.continue"]');
  if (scenario === "transfer-subtype") {
    await press(page, "cash.association.kind-transfer_pair");
    await expect(dialog).toContainText("候选成员21");
    const target = await page.locator('[id="ledger.record-radio-21"]:visible').last().boundingBox();
    const confirm = await continueButton.boundingBox();
    expect(target).not.toBeNull(); expect(confirm).not.toBeNull();
    await press(page, "cash.association.subtype");
    const option = await page.locator('[id="choice-option-credit_repayment"]:visible').last().boundingBox();
    expect(option).not.toBeNull();
    await page.mouse.click(option!.x + 50, option!.y + option!.height / 2);
    await page.waitForTimeout(800);
    await page.screenshot({path:info.outputPath("selected-transfer-subtype.png")});
    // The dismissed Compose popup leaves stale semantic nodes. Use the actual
    // row/footer geometry observed before opening it, without another Escape.
    await page.mouse.click(target!.x + target!.width / 2, target!.y + target!.height / 2);
    await page.waitForTimeout(350);
    await page.mouse.click(confirm!.x + confirm!.width / 2, confirm!.y + confirm!.height / 2);
    await expect.poll(() => (previews.at(-1) as any)?.relation_additions?.[0]?.subtype).toBe("credit_repayment");
    expect((previews.at(-1) as any).relation_additions[0].kind).toBe("transfer_pair");expect(commits).toBe(0);return;
  }
  if (scenario === "reject-cancel") {
    await expect.poll(() => (previews.at(-1) as any)?.relation_choices?.[0]?.action).toBe("ignore");
    await press(page, dialog.getByText("取消",{exact:true}));
    expect(commits).toBe(0);return;
  }
  if (scenario === "multi-component") {
    await press(page, "cash.association.choose-other");
    await expect(dialog).toContainText("候选成员21");
    await press(page, "ledger.record-radio-21");
    await expect(dialog).toContainText("当前流水"); await expect(dialog).toContainText("对侧流水");
    await expect(dialog).toContainText("银行卡部分"); await expect(dialog).toContainText("零钱部分");
    await press(page, "cash.association.part-11-1101"); await press(page, "cash.association.part-21-2102");
    await press(page, continueButton);
    await expect(continueButton).toContainText("确认保存");
    const addition = (previews.at(-1) as any).relation_additions[0];
    expect(addition).toMatchObject({anchor_component_id:"1101",target_component_id:"2102"});
    await page.screenshot({path:info.outputPath("named-components.png")});return;
  }
  if (scenario === "joint" || scenario === "p2p") {
    if (scenario === "joint") await expect(dialog).toContainText("只能整体接受或整体拒绝");
    await press(page, "cash.association.choose-other");
    await expect(dialog.locator('[id="cash.association.locked-type"]')).toBeVisible();
    await expect(dialog.locator('[id="cash.association.relation-types"]')).toHaveCount(0);
    await expect(dialog).toContainText("候选成员21");
    await press(page, "ledger.record-radio-21");
    const before = previews.length;
    await press(page, continueButton);
    if (scenario === "joint") {
      await expect(dialog).toContainText("暂不能提交替换");
      expect(previews).toHaveLength(before); expect(commits).toBe(0);
    } else {
      await expect.poll(() => previews.length).toBe(before + 1);
      expect((previews.at(-1) as any).relation_additions[0]).toMatchObject({ kind: "refund_offset", subtype: "p2p_return", target_transaction_id: "21" });
      expect(commits).toBe(0);
    }
    await page.screenshot({ path: info.outputPath(scenario + ".png") }); return;
  }
  if (scenario === "remove" || scenario === "remove-toggle") {
    await expect(dialog).toContainText("当前人工备注"); await expect(dialog).toContainText("原始备注");
    const before = previews.length;
    await press(page, continueButton); expect(previews).toHaveLength(before);
    await press(page, "cash.association.inherit-keep");
    if (scenario === "remove-toggle") {
      await expect.poll(() => (previews.at(-1) as any)?.relation_choices?.[0]?.inherit_common_fields).toBe(true);
      await press(page, "cash.association.inherit-restore");
      await expect.poll(() => (previews.at(-1) as any)?.relation_choices?.[0]?.inherit_common_fields).toBe(false);
      expect(commits).toBe(0);
    }
  } else if (scenario === "remove-clean") await expect(dialog.locator('[id="cash.association.inherit-keep"]')).toHaveCount(0);
  else if (scenario !== "reject") {
    await expect(dialog).toContainText("候选成员21");
    await expect(dialog).toContainText("候选成员22");
    await expect(dialog.locator('[id="cash.association.single-selected"]')).toHaveCount(1);
    await expect(dialog.locator('[id^="cash.association.option-"]')).toHaveCount(0);
  }
  await page.screenshot({ path: info.outputPath("confirmation.png") });
  await press(page, continueButton);
  if (removing || scenario === "reject") {
    await expect.poll(() => commits).toBe(1);
    expect(previews.at(-1)?.relation_choices).toEqual([{suggestion_id:"group",action:removing?"remove":"ignore",...(scenario==="remove"?{inherit_common_fields:true}:scenario==="remove-toggle"?{inherit_common_fields:false}:{})}]);
    return;
  }
  if (scenario === "stale") {
    await expect(dialog.locator('[id="cash.association.view-stale"]')).toBeVisible();
    const count = previews.length; await page.waitForTimeout(500); expect(previews).toHaveLength(count); expect(commits).toBe(0); return;
  }
  await expect(continueButton).toContainText("确认保存");
  expect(commits).toBe(0);
  await page.screenshot({ path: info.outputPath("preview.png") });
  await press(page, continueButton);
  if (scenario === "missing") {
    await expect(dialog).toContainText("无法读取"); expect(commits).toBe(0); return;
  }
  if (scenario === "unknown") {
    await expect(dialog.locator('[id="cash.association.view-unknown"]')).toBeVisible();
    await press(page, continueButton);
    await expect.poll(() => resolutions.length).toBe(1);
    expect(resolutions[0].plan_id).toBe(planId); expect(resolutions[0].idempotency_key).toBeTruthy(); expect(commits).toBe(1); return;
  }
  await expect.poll(() => commits).toBe(1);
  const body = previews.at(-1) as any;
  expect(body.relation_choices).toEqual([{ suggestion_id: "group", action: removing ? "remove" : scenario === "reject" ? "ignore" : "accept", ...(removing ? scenario === "remove" ? { inherit_common_fields: true } : {} : scenario === "reject" ? {} : { candidate_id: "candidate" }) }]);
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]); expect(diagnostics.failedRequests).toEqual([]); expect(diagnostics.staticHttpErrors).toEqual([]);
});
