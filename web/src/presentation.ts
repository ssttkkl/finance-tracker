import { useEffect, useState } from "react";
import {
  layoutClassForWidth,
  type LayoutClass,
} from "@finance-tracker/presentation";

function readPresentationLayout(): LayoutClass {
  return layoutClassForWidth(
    typeof window === "undefined" ? 0 : window.innerWidth,
  );
}

export function usePresentationLayout(): LayoutClass {
  const [layout, setLayout] = useState<LayoutClass>(readPresentationLayout);

  useEffect(() => {
    const updateLayout = () => setLayout(readPresentationLayout());

    updateLayout();
    window.addEventListener("resize", updateLayout);
    return () => window.removeEventListener("resize", updateLayout);
  }, []);

  return layout;
}
