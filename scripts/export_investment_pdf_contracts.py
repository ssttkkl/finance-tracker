"""Export anonymous PDFBox migration inputs with original broker parser expectations."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from pypdf import PdfReader, PdfWriter
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont

from ft.importers import dfzq, usmart_hk
from ft.importers.pdf_tools import extract_pdf_text
from export_ibkr_contracts import encode


def export(directory: Path):
    root = Path(__file__).resolve().parents[1]
    if not directory.is_absolute() or directory.resolve().is_relative_to(root):
        raise ValueError("Use a new absolute external directory")
    directory.mkdir()
    pdfmetrics.registerFont(UnicodeCIDFont("STSong-Light"))
    usmart = "\n".join([
        "结单日期 2026-06", "交易明细", "ABC", "美股 买入 2 USD 5 10 2026-06-02",
        "交易金额 10.00", "变动金额合计 -10.50", "持仓明细", "ABC USD 2", "资金出入",
        "入金 USD 20 2026-06-02", "证券提存", "期末账户结余 0.00 9.50 --",
    ])
    samples = [
        ("dfzq", (root / "tests/fixtures/dfzq/sample_statement.txt").read_text()),
        ("usmart_hk", usmart),
    ]
    cases = []
    for broker, text in samples:
        plain = directory / f"{broker}.pdf"
        document = canvas.Canvas(str(plain), pagesize=(1000, 1800), invariant=1)
        y = 1750
        for line in text.splitlines():
            if y < 30:
                document.showPage()
                y = 1750
            document.setFont("STSong-Light", 10)
            document.drawString(30, y, line)
            y -= 14
        document.save()
        original = extract_pdf_text(plain)
        module = dfzq if broker == "dfzq" else usmart_hk
        rows = dfzq.parse_dfzq_text(original.splitlines(keepends=True)) if broker == "dfzq" else usmart_hk.parse_usmart_hk_text(original)
        assert rows
        account, currency = ("匿名东方证券", "CNY") if broker == "dfzq" else ("匿名盈立证券", "USD")
        mapped = []
        for row in rows:
            event = dfzq.map_dfzq_to_investment_event(row, account, currency) if broker == "dfzq" else usmart_hk.map_usmart_hk_to_investment_event(row, account, currency)
            mapped.append({"row": encode(row), "identity": module.construct_source_identity(row), "event": encode(event)})
        encrypted = PdfWriter()
        encrypted.append(PdfReader(plain))
        encrypted.encrypt("synthetic-only")
        encrypted_path = directory / f"{broker}-encrypted.pdf"
        with encrypted_path.open("wb") as stream:
            encrypted.write(stream)
        for path, password in [(plain, None), (encrypted_path, "synthetic-only")]:
            cases.append({"broker": broker, "file": path.name, "password": password, "account": account, "currency": currency,
                          "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "source_text": original, "expected": mapped})
    (directory / "contract.json").write_text(json.dumps({"cases": cases}, ensure_ascii=False, indent=2) + "\n")
    print(f"Exported {len(cases)} anonymous PDF contracts")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    export(parser.parse_args().directory)
