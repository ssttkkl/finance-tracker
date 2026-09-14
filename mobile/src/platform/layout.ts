import { isLargeLayout, type LayoutClass } from "@finance-tracker/presentation";

export function navigationModeForLayout(layout: LayoutClass): "compact-menu" | "wide-rail" {
  return isLargeLayout(layout) ? "wide-rail" : "compact-menu";
}
