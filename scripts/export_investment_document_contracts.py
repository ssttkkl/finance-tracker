"""Export real investment application file parsing and source-payload contracts."""
from __future__ import annotations
import argparse
import base64
import json
from pathlib import Path
import random
import tempfile
from ft.application.investment_import import InvestmentImportService, _require_source_payload
from ft.importers import dfzq, ibkr, schwab, usmart_hk
from export_ibkr_contracts import encode, result


def export(output):
    root = Path(__file__).resolve().parents[1]
    if not output.is_absolute() or output.resolve().is_relative_to(root):
        raise ValueError("Use an absolute external output")
    service = InvestmentImportService(None)
    cases = []
    sources = [("dfzq", "dfzq/sample_statement.txt"), ("ibkr", "ibkr/transactions_1y_sample.csv"),
               ("schwab", "schwab/transaction_history_sample.csv"), ("usmart-hk", "usmart_hk/monthly_sample.txt"),
               ("usmart_hk", "usmart_hk/monthly_sample.txt")]
    with tempfile.TemporaryDirectory(prefix="ft-investment-document-") as temporary:
        for source, fixture in sources:
            original = (root / "tests/fixtures" / fixture).read_bytes()
            variants = [("original", original, Path(fixture).name)]
            if source in {"dfzq", "usmart-hk", "usmart_hk"}:
                variants += [("crlf", original.replace(b"\n", b"\r\n"), "statement.TEXT"),
                             ("cr", original.replace(b"\n", b"\r"), "statement.TXT"),
                             ("invalid_utf8", b"\xed\xa0\x80\xff\n" + original, "statement.txt")]
            for name, content, filename in variants:
                path = Path(temporary) / filename
                path.write_bytes(content)
                rows = service._parse_statement(source, path)
                module = {"dfzq": dfzq, "ibkr": ibkr, "schwab": schwab, "usmart-hk": usmart_hk, "usmart_hk": usmart_hk}[source]
                mapper = getattr(module, {"dfzq": "map_dfzq_to_investment_event", "ibkr": "map_ibkr_to_investment_event",
                                         "schwab": "map_schwab_to_investment_event", "usmart-hk": "map_usmart_hk_to_investment_event", "usmart_hk": "map_usmart_hk_to_investment_event"}[source])
                currency = "CNY" if source == "dfzq" else "USD"
                cases.append({"name": source + "_" + name, "source": source, "filename": filename,
                              "bytes": base64.b64encode(content).decode(), "rows": encode(rows), "currency": currency,
                              "mapped": [{"identity": module.construct_source_identity(row),
                                          "payload": encode(_require_source_payload(row, source=source)),
                                          "event": encode(mapper(row, "broker", currency))} for row in rows]})
    payloads = []
    for source, _ in sources:
        for payload in [None, {}, [], {"原始文本单元": []}, {"原始文本单元": ["raw"]},
                        {"原始文本单元": [1]}, {"原始文本单元": ["raw"], "amount": "1"},
                        {"original": "raw"}, {"_source_payload": {}}, {"original": float("nan")}]:
            payloads.append({"source": source, "payload": encode(payload),
                             "expected": result(lambda: _require_source_payload({"_source_payload": payload}, source=source))})
    randomizer = random.Random(4321)
    byte_cases = [bytes([i]) for i in range(256)] + [bytes.fromhex(h) for h in ["eda080", "edbfbf", "e08080", "f0808080", "f4908080", "f48fbfbf", "f09f", "e282", "e228a1"]]
    byte_cases += [randomizer.randbytes(randomizer.randrange(1, 14)) for _ in range(512)]
    data = {"cases": cases, "payloads": payloads,
            "utf8": [{"bytes": base64.b64encode(b).decode(), "text": b.decode("utf-8", errors="replace")} for b in byte_cases]}
    output.write_text(json.dumps(data, ensure_ascii=True, indent=2) + "\n")
    print(len(cases), "complete file cases;", len(payloads), "payload cases;", len(byte_cases), "UTF-8 boundaries")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    export(parser.parse_args().output)
