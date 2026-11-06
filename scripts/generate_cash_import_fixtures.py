"""Rebuild import regression bytes from an already identity-anonymized source JSON.

This tool never reads original personal bills. Java 21 and the existing backend
installDist supply POI/SnakeYAML; no additional Python or JVM dependency is needed.
"""

import argparse
import csv
import hashlib
import io
import json
import math
import re
import subprocess
from pathlib import Path


def metadata(document, period):
    source = document["source"]
    if source == "ccb_debit":
        return ["中国建设银行个人活期账户全部交易明细",
                "卡号/账号：" + document["account"],
                "起始日期：" + period["start"].replace("-", ""),
                "结束日期：" + period["end"].replace("-", "")]
    return ["微信支付账单明细" if source == "wechat" else "支付宝交易记录明细查询",
            "微信昵称：[" + document["nickname"] + "]" if source == "wechat" else "支付宝账户：[" + document["account"] + "]",
            "起始时间：[" + period["start"] + " 00:00:00]",
            "终止时间：[" + period["end"] + " 23:59:59]",
            ("导出类型" if source == "wechat" else "导出交易类型") + "：[全部]",
            f'共{len(document["rows"])}笔记录']


def pdf_bytes(document, period):
    """Write a fresh table PDF, without copying metadata, streams or hidden text."""
    objects = []

    def reserve():
        objects.append(b"")
        return len(objects)

    def add(value):
        objects.append(value.encode("ascii") if isinstance(value, str) else value)
        return len(objects)

    def stream(content):
        return b"<< /Length " + str(len(content)).encode() + b" >>\nstream\n" + content + b"\nendstream"

    catalog, pages = reserve(), reserve()
    cmap = b"/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def /CMapName /FixtureUnicode def /CMapType 2 def 1 begincodespacerange <0000> <FFFF> endcodespacerange 1 beginbfrange <0000> <FFFF> <0000> endbfrange endcmap CMapName currentdict /CMap defineresource pop end end"
    unicode_map = add(stream(cmap))
    descriptor = add("<< /Type /FontDescriptor /FontName /STSong-Light /Flags 6 /FontBBox [0 -200 1000 1000] /ItalicAngle 0 /Ascent 880 /Descent -120 /CapHeight 880 /StemV 80 >>")
    descendant = add(f"<< /Type /Font /Subtype /CIDFontType0 /BaseFont /STSong-Light /CIDSystemInfo << /Registry (Adobe) /Ordering (GB1) /Supplement 4 >> /FontDescriptor {descriptor} 0 R /DW 1000 >>")
    font = add(f"<< /Type /Font /Subtype /Type0 /BaseFont /STSong-Light /Encoding /UniGB-UCS2-H /DescendantFonts [{descendant} 0 R] /ToUnicode {unicode_map} 0 R >>")
    headers = list(document["rows"][0])
    widths = ([82, 82, 24, 40, 44, 40, 44, 52, 62, 82, 64, 86]
              if document["source"] == "icbc_credit"
              else [82, 85, 30, 24, 36, 30, 60, 35, 72, 60, 72, 85, 49])
    widths = [value * 802 / sum(widths) for value in widths]
    xs = [20.0]
    for width in widths:
        xs.append(xs[-1] + width)
    children = []
    page_count = math.ceil(len(document["rows"]) / 26)
    for number in range(page_count):
        rows = document["rows"][number * 26:(number + 1) * 26]
        commands = ["q", "0 1 -1 0 595 0 cm", "0.3 w"]

        def text(value, x, y, size=3.2):
            encoded = value.encode("utf-16-be").hex()
            commands.append(f"BT /F1 {size} Tf 1 0 0 1 {x:.3f} {y:.3f} Tm <{encoded}> Tj ET")

        text("中国工商银行信用卡交易明细" if document["source"] == "icbc_credit" else "中国工商银行借记卡交易明细", 20, 565, 7)
        text(f'起止交易日期：{period["start"]}至{period["end"]}', 20, 550, 5)
        if document["account"]:
            text("卡号：" + document["account"], 20, 535, 5)
        text(f"共{page_count}页第{number + 1}页", 490, 550, 5)
        text(f"本页交易笔数：{len(rows)}", 630, 550, 5)
        text("交易类型：全部", 490, 535, 5)
        top, height = 515, 14
        for x in xs:
            commands.append(f"{x:.3f} {top} m {x:.3f} {top - (len(rows) + 1) * height} l S")
        for row_number in range(len(rows) + 2):
            y = top - row_number * height
            commands.append(f"{xs[0]} {y} m {xs[-1]} {y} l S")
        for row_number, values in enumerate([dict(zip(headers, headers)), *rows]):
            for column, key in enumerate(headers):
                value = values[key]
                max_chars = max(1, int((widths[column] - 4) / 3.2))
                lines = [part[index:index + max_chars] for part in value.split("\n") for index in range(0, len(part), max_chars)]
                for line_number, line in enumerate(lines):
                    text(line, xs[column] + 2, top - row_number * height - 5 - line_number * 3.8)
        commands.append("Q")
        content = add(stream("\n".join(commands).encode("ascii")))
        children.append(add(f"<< /Type /Page /Parent {pages} 0 R /MediaBox [0 0 595 842] /Rotate 90 /Resources << /Font << /F1 {font} 0 R >> >> /Contents {content} 0 R >>"))
    objects[catalog - 1] = f"<< /Type /Catalog /Pages {pages} 0 R >>".encode()
    objects[pages - 1] = f'<< /Type /Pages /Count {len(children)} /Kids [{" ".join(f"{child} 0 R" for child in children)}] >>'.encode()
    result = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for index, value in enumerate(objects, 1):
        offsets.append(len(result))
        result.extend(f"{index} 0 obj\n".encode() + value + b"\nendobj\n")
    xref = len(result)
    result.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for offset in offsets[1:]:
        result.extend(f"{offset:010d} 00000 n \n".encode())
    result.extend(f"trailer\n<< /Size {len(objects) + 1} /Root {catalog} 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return bytes(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--backend-install", type=Path, default=Path("compose/backend/build/install/backend"))
    options = parser.parse_args()
    source = json.loads(options.source.read_text())
    options.output.mkdir(parents=True, exist_ok=True)
    entries = []
    for document in source["documents"]:
        kind = document["format"].lower()
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", document["id"]) or kind not in {"pdf", "csv", "xls", "xlsx"}:
            raise ValueError("Unsupported fixture identifier or format")
        filename = document["id"] + "." + kind
        target = options.output / filename
        if kind == "pdf":
            target.write_bytes(pdf_bytes(document, source["period"]))
        else:
            content = {"headers": list(document["rows"][0]), "metadata": metadata(document, source["period"]), "rows": document["rows"]}
            if kind == "csv":
                output = io.StringIO(newline="")
                writer = csv.writer(output, lineterminator="\r\n")
                writer.writerows([[line] for line in content["metadata"]])
                writer.writerow(content["headers"])
                writer.writerows([row[key] for key in content["headers"]] for row in document["rows"])
                target.write_bytes(output.getvalue().encode(document.get("encoding", "utf-8-sig")))
            else:
                helper = Path(__file__).parent / "fixtures/CashFixtureWorkbook.java"
                result = subprocess.run(["java", "--class-path", str(options.backend_install / "lib/*"), str(helper), kind, str(target)],
                                        input=json.dumps(content, ensure_ascii=False).encode(), capture_output=True, timeout=60)
                if result.returncode:
                    raise RuntimeError("Fixture workbook generation failed: " + result.stderr.decode(errors="replace")[-1000:])
        entries.append({"file": filename, "source": document["source"], "rows": len(document["rows"]), "sha256": hashlib.sha256(target.read_bytes()).hexdigest()})
    manifest = {"period": source["period"], "privacy": source["privacy"], "files": entries}
    (options.output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"files": len(entries), "rows": sum(entry["rows"] for entry in entries)}))


if __name__ == "__main__":
    main()
