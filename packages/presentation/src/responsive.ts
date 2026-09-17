export type LayoutClass = "compact" | "regular" | "wide";

export const layoutBreakpoints = {
  regularMinWidth: 600,
  wideMinWidth: 1024,
  reference: {
    phone: { width: 390, height: 844 },
    tabletPortrait: { width: 768, height: 1024 },
    tabletLandscape: { width: 1024, height: 768 },
  },
} as const;

export function layoutClassForWidth(width: number): LayoutClass {
  if (!Number.isFinite(width) || width < layoutBreakpoints.regularMinWidth) return "compact";
  if (width < layoutBreakpoints.wideMinWidth) return "regular";
  return "wide";
}

export function isLargeLayout(layout: LayoutClass): boolean {
  return layout === "wide";
}
