import { describe, expect, it } from "vitest";
import {
  copy,
  firstPhaseJourneys,
  getScreenContract,
  layoutClassForWidth,
  platformDifferences,
  semanticIds,
  screenContracts,
} from "./index";

describe("cross-platform presentation contract", () => {
  it("uses the Web product copy for the first Native rewrite", () => {
    expect(copy.ledger.title).toBe("收支账本");
    expect(copy.ledger.create).toBe("新建流水");
    expect(copy.import.title).toBe("导入账单");
    expect(copy.auth.loginTitle).toBe("登录到你的账本");
    expect(copy.auth.loading).toBe("加载中...");
    expect(copy.workspace.title).toBe("选择工作区");
  });

  it("keeps the Native-only API origin debug controls addressable", () => {
    expect(copy.auth.apiOrigin).toBe("后端地址");
    expect(copy.auth.apiOriginReset).toBe("恢复默认");
    expect(semanticIds.authApiOrigin).toBe("auth.api-origin");
    expect(semanticIds.authApiOriginReset).toBe("auth.api-origin-reset");
    expect(platformDifferences.debugApiOriginOverride.web).toContain("不展示");
  });

  it("keeps responsive boundaries independent from device models", () => {
    expect(layoutClassForWidth(0)).toBe("compact");
    expect(layoutClassForWidth(599.99)).toBe("compact");
    expect(layoutClassForWidth(600)).toBe("regular");
    expect(layoutClassForWidth(1023.99)).toBe("regular");
    expect(layoutClassForWidth(1024)).toBe("wide");
  });

  it("declares complete first-phase screen contracts", () => {
    const requiredStates = ["loading", "normal", "empty", "error", "disabled", "success"] as const;
    for (const contract of Object.values(screenContracts)) {
      expect(contract.regions.length).toBeGreaterThan(0);
      expect(contract.actions.length).toBeGreaterThan(0);
      for (const state of requiredStates) expect(contract.states).toContain(state);
      expect(getScreenContract(contract.id)).toBe(contract);
    }
  });

  it("keeps semantic ids stable and journeys addressable", () => {
    const ids = Object.values(semanticIds);
    expect(new Set(ids).size).toBe(ids.length);
    for (const journey of Object.values(firstPhaseJourneys)) {
      for (const step of journey.steps) {
        if (step.target) expect(ids).toContain(step.target);
      }
    }
  });

  it("requires an invariant for every platform surface difference", () => {
    for (const difference of Object.values(platformDifferences)) {
      expect(difference.web).not.toBe("");
      expect(difference.native).not.toBe("");
      expect(difference.invariant.length).toBeGreaterThan(0);
    }
  });
});
