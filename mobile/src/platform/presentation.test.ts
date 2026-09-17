import { describe, expect, it } from "vitest";
import { layoutClassForWidth, semanticIds, screenContracts } from "@finance-tracker/presentation";
import { navigationModeForLayout } from "./layout";

describe("Native presentation contract", () => {
  it("switches phone, tablet and large-window layouts by logical width", () => {
    expect(layoutClassForWidth(390)).toBe("compact");
    expect(layoutClassForWidth(768)).toBe("regular");
    expect(layoutClassForWidth(1024)).toBe("wide");
    expect(layoutClassForWidth(1366)).toBe("wide");
  });

  it("keeps every Native screen region and primary action addressable", () => {
    const ids = new Set(Object.values(semanticIds));
    for (const contract of Object.values(screenContracts)) {
      expect(contract.regions.length).toBeGreaterThan(0);
      for (const action of contract.actions) expect(ids.has(action)).toBe(true);
    }
  });

  it("keeps Shell navigation regions aligned with the responsive contract", () => {
    expect(navigationModeForLayout("compact")).toBe("compact-menu");
    expect(navigationModeForLayout("regular")).toBe("compact-menu");
    expect(navigationModeForLayout("wide")).toBe("wide-rail");
    expect(screenContracts.ledger.regions).toEqual(expect.arrayContaining(["header", "filters", "transaction-list", "primary-actions"]));
  });
});
