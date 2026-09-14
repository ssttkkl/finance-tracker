import { describe, expect, it } from "vitest";
import { componentTokens, cssVariableMap, designTokens, nativeColors, nativeTypography, responsiveTokens } from "./index";

describe("shared design tokens", () => {
  it("keeps the existing Cobalt typography and touch target vocabulary", () => {
    expect(designTokens.colors.accent).toBe("oklch(.48 .16 252)");
    expect(designTokens.typography.body).toBe("Noto Sans SC");
    expect(designTokens.typography.mono).toBe("IBM Plex Mono");
    expect(designTokens.touchTarget).toBe(44);
    expect(cssVariableMap["--space-4"]).toBe("16px");
    expect(nativeTypography.mono).toBe("monospace");
    expect(nativeColors.errorSurface).toBe("#FBEAE8");
  });

  it("keeps responsive and component geometry shared across renderers", () => {
    expect(responsiveTokens.regularMinWidth).toBe(600);
    expect(responsiveTokens.wideMinWidth).toBe(1024);
    expect(componentTokens.page.mobilePadding).toBe(16);
    expect(componentTokens.page.formMaxWidth).toBe(720);
    expect(componentTokens.control.minTouchTarget).toBe(44);
  });
});
