"""Generate the 19-file synthetic cash-import workload without source statements."""

import argparse
from collections import Counter
from datetime import datetime, timedelta
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


PDF_PASSWORD = "synthetic-fixture-only-613"
PERIOD = {"start": "2026-02-01", "end": "2026-04-15"}
BASE_DATE = datetime(2026, 2, 1)
ROOT = Path(__file__).resolve().parents[4]


def date(index):
    return BASE_DATE + timedelta(days=index // 480, minutes=index % 480)


def amount(index):
    return f"{4 + index // 100}.{index % 100:02d}"


def bank_row(index):
    merchant = f"合成商户{index}"
    return {
        "序号": str(index + 1),
        "摘要": "消费",
        "币别": "美元" if index < 5 else "人民币",
        "钞汇": "钞",
        "交易日期": date(index).strftime("%Y%m%d"),
        "交易金额": "-" + amount(index),
        "账户余额": "987654.32",
        "交易地点/附言": "支付宝-" + merchant,
        "对方账号与户名": "9000000000000/" + merchant,
    }


def alipay_row(index):
    return {
        "交易时间": date(index).strftime("%Y-%m-%d %H:%M:%S"),
        "交易分类": "日用百货",
        "交易对方": f"合成商户{index}",
        "对方账号": "",
        "商品说明": f"合成商品{index}",
        "收/支": "支出",
        "金额": amount(index),
        "收/付款方式": "建设银行储蓄卡(1234)",
        "交易状态": "交易成功",
        "交易订单号": f"202600000000{index:013}",
        "商家订单号": f"synt-alipay-{index}",
        "备注": "",
        "": "",
    }


def wechat_row(index):
    return {
        "交易时间": date(index).strftime("%Y-%m-%d %H:%M:%S"),
        "交易类型": "商户消费",
        "交易对方": f"合成商户{index}",
        "商品": f"合成商品{index}",
        "收/支": "支出",
        "金额(元)": amount(index),
        "支付方式": "建设银行储蓄卡(1234)",
        "当前状态": "支付成功",
        "交易单号": f"100000000000{index:015}",
        "商户单号": f"synt-wechat-{index}",
        "备注": "/",
    }


def icbc_row(document, index, card):
    time = f"2026-02-10 03:{document}{index}:00"
    merchant = f"合成银行商户{document}{index}"
    if document < 2:
        return {
            "交易日期": time,
            "账号": card,
            "储种": "活期",
            "序号": str(index + 1),
            "币种": "人民币",
            "钞汇": "钞",
            "摘要": "无卡支付",
            "地区": "合成地区",
            "收入/支出金额": "-999.01",
            "余额": "5000.00",
            "对方户名": merchant,
            "对方账号": "9000001234",
            "渠道": "其他",
        }
    # This native credit-card format has no transaction ID column.
    return {
        "入账日期": time,
        "交易卡号": card,
        "收\n支": "支",
        "交易币种": "CNY",
        "交易金额": "999.01",
        "入账币种": "CNY",
        "入账金额": "999.01",
        "账户余额": "8000.00",
        "对方户名": merchant,
        "对方账号": "",
        "摘要": "消费",
        "交易场所": "合成地区",
    }


def overlap(start, count, length, overlap_length, floor=0):
    return [*range(start, min(count, start + length)),
            *range(max(floor, start - overlap_length), start)]


def statement(name, source, file_format, account, rows):
    return {
        "id": name,
        "source": source,
        "format": file_format,
        "account": account,
        "nickname": "合成昵称",
        "rows": rows,
    }


def documents():
    for number in range(2):
        rows = [bank_row(i) for i in range(number * 1500, (number + 1) * 1500)]
        yield statement(f"bank-{number}", "ccb_debit", "xls", "900000000000001234", rows)
    for number in range(7):
        rows = [alipay_row(i) for i in overlap(number * 900, 6300, 900, 190)]
        yield statement(f"alipay-{number}", "alipay", "csv" if number < 6 else "xlsx",
                        "synthetic@example.test", rows)
    for number in range(5):
        rows = [wechat_row(i) for i in overlap(6500 + number * 685, 9914, 685, 140, 6500)]
        yield statement(f"wechat-{number}", "wechat", "xlsx", "synthetic", rows)
    for number in range(5):
        card = f"90000000000000{number:02d}"
        encrypted = number in (1, 3, 4)
        rows = [icbc_row(number, i, card) for i in range(2)]
        yield statement(f"{'secure' if encrypted else 'open'}-{number}",
                        "icbc_debit" if number < 2 else "icbc_credit", "pdf", card, rows)


def normalize_workbook_metadata(path):
    """Fix POI's generated ZIP and core-document timestamps."""
    output = path.with_suffix(".fixed.xlsx")
    with ZipFile(path) as original, ZipFile(output, "w") as normalized:
        for name in original.namelist():
            data = original.read(name)
            if name == "docProps/core.xml":
                data = re.sub(
                    rb"(\x3cdcterms:(?:created|modified)[^>]*>)[^<]+",
                    rb"\g<1>2026-02-01T00:00:00Z", data,
                )
            member = ZipInfo(name, date_time=(2026, 2, 1, 0, 0, 0))
            member.create_system = 0
            normalized.writestr(member, data, compress_type=ZIP_DEFLATED)
    output.replace(path)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def generate(output, backend_install):
    output.mkdir(parents=True, exist_ok=False)
    source_path = output / "source.json"
    source = {"privacy": "fully synthetic", "period": PERIOD, "documents": list(documents())}
    source_path.write_text(json.dumps(source, ensure_ascii=False), encoding="utf-8")
    subprocess.run([
        sys.executable, str(ROOT / "scripts/generate_cash_import_fixtures.py"),
        "--source", str(source_path), "--output", str(output),
        "--backend-install", str(backend_install),
    ], check=True)
    manifest_path = output / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    for entry in manifest["files"]:
        path = output / entry["file"]
        encrypted = path.name.startswith("secure-")
        if encrypted:
            target = path.with_suffix(".encrypted.pdf")
            # Fixed ID and IV make these public test bytes reproducible.
            subprocess.run([
                "qpdf", "--static-id", "--static-aes-iv", "--encrypt", PDF_PASSWORD,
                "synthetic-fixture-owner-827", "128", "--use-aes=y", "--", str(path), str(target),
            ], check=True)
            target.replace(path)
        if path.suffix == ".xlsx":
            normalize_workbook_metadata(path)
        entry.update(format=path.suffix[1:], password_required=encrypted, sha256=sha256(path))
    manifest["source_sha256"] = sha256(source_path)
    manifest["rows"] = sum(entry["rows"] for entry in manifest["files"])
    manifest["formats"] = dict(sorted(Counter(entry["format"] for entry in manifest["files"]).items()))
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--backend-install", required=True, type=Path)
    arguments = parser.parse_args()
    generate(arguments.output.resolve(), arguments.backend_install.resolve())
