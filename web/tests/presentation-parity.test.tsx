import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { usePresentationLayout } from "../src/presentation";

function LayoutProbe() {
  const layout = usePresentationLayout();

  return <output data-testid="presentation-layout">{layout}</output>;
}

describe("Web presentation layout contract", () => {
  const originalInnerWidth = window.innerWidth;
  const setWindowWidth = (width: number) => {
    Object.defineProperty(window, "innerWidth", {
      configurable: true,
      value: width,
    });
  };

  afterEach(() => {
    Object.defineProperty(window, "innerWidth", {
      configurable: true,
      value: originalInnerWidth,
    });
  });

  it("publishes the same compact, regular, and wide boundaries as Native", () => {
    setWindowWidth(390);
    render(<LayoutProbe />);
    expect(screen.getByTestId("presentation-layout")).toHaveTextContent(
      "compact",
    );

    act(() => {
      setWindowWidth(768);
      window.dispatchEvent(new Event("resize"));
    });
    expect(screen.getByTestId("presentation-layout")).toHaveTextContent(
      "regular",
    );

    act(() => {
      setWindowWidth(1200);
      window.dispatchEvent(new Event("resize"));
    });
    expect(screen.getByTestId("presentation-layout")).toHaveTextContent(
      "wide",
    );
  });
});
