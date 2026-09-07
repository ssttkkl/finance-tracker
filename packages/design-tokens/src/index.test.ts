import { describe, expect, it } from "vitest";
import { cssVariableMap, designTokens, nativeColors, nativeTypography } from "./index";

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
});
