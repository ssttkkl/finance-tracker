import { describe, expect, it } from "vitest";
import { detectPdfPasswordRequirement } from "../src/import/pdfPassword";

describe("detectPdfPasswordRequirement", () => {
  it("识别 PDF 中的标准加密条目", async () => {
    const file = new File(["%PDF-1.7\ntrailer\n<< /Encrypt 8 0 R >>"], "locked.pdf", {
      type: "application/pdf",
    });

    await expect(detectPdfPasswordRequirement(file)).resolves.toBe(true);
  });

  it("把没有加密条目的 PDF 交给服务端扫描", async () => {
    const file = new File(["%PDF-1.7\ntrailer\n<< /Root 1 0 R >>"], "plain.pdf", {
      type: "application/pdf",
    });

    await expect(detectPdfPasswordRequirement(file)).resolves.toBe(false);
  });

  it("无法读取或识别的 PDF 返回未知而不阻断导入", async () => {
    const file = new File(["not a pdf"], "unknown.pdf", { type: "application/pdf" });

    await expect(detectPdfPasswordRequirement(file)).resolves.toBe(null);
  });
});
