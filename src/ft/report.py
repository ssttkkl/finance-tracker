"""报告 — 多币种分组展示，checkin 余额重置"""
from pathlib import Path
import csv
import re
from collections import defaultdict
from .accounts import load_accounts
from . import models


def _read_records(records_dir=None, month=None) -> list[dict]:
    """Read all records from records/{type}/*.csv, optionally filtered by month."""
    if records_dir is None:
        records_dir = models.RECORDS_DIR
    records_dir = Path(records_dir)

    all_records = []
    if not records_dir.exists():
        return all_records

    for type_dir in sorted(records_dir.iterdir()):
        if not type_dir.is_dir():
            continue
        for csv_file in sorted(type_dir.glob("*.csv")):
            if month and not csv_file.stem.startswith(month):
                continue
            with open(csv_file, encoding="utf-8") as f:
                reader = csv.DictReader(f)
                for row in reader:
                    all_records.append(row)
    return all_records


def _compute_acct_balances_with_checkin(accounts, records_dict):
    """Compute per-account balance with checkin reset.
    
    accounts: list of account dicts from accounts.yaml
    records_dict: dict of {account_name: [sorted records]}
    
    Returns: dict {currency: {account_name: balance}}
    """
    acct_map = {(a["name"], a["currency"]): a for a in accounts}
    result = defaultdict(dict)

    for acct_key, records in records_dict.items():
        if isinstance(acct_key, tuple) and len(acct_key) == 2:
            acct_name, currency = acct_key
        else:
            acct_name = acct_key
            currency = records[0].get("currency", "CNY") if records else "CNY"
        records.sort(key=lambda r: r["date"])

        acct = acct_map.get((acct_name, currency))
        if not acct:
            continue
        if not acct.get("active", True):
            continue

        # Find last checkin
        last_checkin_idx = -1
        for i, row in enumerate(records):
            if row.get("category", "") == "checkin":
                last_checkin_idx = i

        if last_checkin_idx >= 0:
            # Parse snapshot balance from description
            desc = records[last_checkin_idx].get("description", "")
            m = re.search(r'[\d,]+\.?\d*', desc.replace(",", ""))
            balance = float(m.group()) if m else 0.0
            start_idx = last_checkin_idx + 1
        else:
            balance = 0.0
            start_idx = 0

        for row in records[start_idx:]:
            cat = row.get("category", "")
            if cat == "checkin":
                continue
            try:
                balance += float(row["amount"])
            except (ValueError, KeyError):
                pass

        result[currency][acct_name] = round(balance, 2)

    return dict(result)


def report_networth(records_dir=None, month=None):
    """资产负债总览 — 从 unified snapshot 读取"""
    from .snapshot import load_snapshot
    from .accounts import load_accounts as load_acct_yaml

    snap = load_snapshot()
    acct_meta = {(a["name"], a["currency"]): a for a in load_acct_yaml()}
    name_counts = defaultdict(int)
    for name, _cur in acct_meta:
        name_counts[name] += 1

    ACCOUNT_ICONS = {"cash": "💰", "loan": "💳", "lend": "📤", "security": "📈"}
    ACCOUNT_LABELS = {"cash": "现金", "loan": "贷款", "lend": "借款", "security": "证券"}
    sym_map = models.CURRENCY_SYMBOLS

    print("  🏦 资产负债总览")
    print("  " + "=" * 46)

    # Build result from snapshot
    result = {}
    result_meta = {}

    for typ in ("cash", "loan", "lend"):
        for acct_name, balance_bucket in snap["accounts"].get(typ, {}).items():
            if isinstance(balance_bucket, dict):
                balance_items = balance_bucket.items()
            else:
                balance_items = [("CNY", balance_bucket)]
            for cur, balance in balance_items:
                if abs(balance) < 0.005:
                    continue
                if cur not in result:
                    result[cur] = {}
                    result_meta[cur] = {}
                display_name = acct_name
                if name_counts.get(acct_name, 0) > 1 or (acct_name, cur) not in acct_meta:
                    display_name = f"{acct_name} [{cur}]"
                result[cur][display_name] = balance
                result_meta[cur][display_name] = acct_meta.get((acct_name, cur), {})

    for acct_name, acct_data in snap["accounts"].get("security", {}).items():
        currency = acct_data.get("currency", "CNY") or "CNY"
        cash_bal = acct_data.get("cash", 0.0)
        total_value = cash_bal
        for tkr, pos in acct_data.get("positions", {}).items():
            total_value += pos["shares"] * pos["avg_cost"]
        total_value = round(total_value, 2)
        if currency not in result:
            result[currency] = {}
            result_meta[currency] = {}
        result[currency][acct_name] = total_value
        result_meta[currency][acct_name] = {"type": "security"}

    # Print
    for cur in sorted(result.keys()):
        sym = sym_map.get(cur, "")
        print(f"\n  [{cur}]")
        cur_total = sum(result[cur].values())
        for acct_name, bal in result[cur].items():
            meta = result_meta.get(cur, {}).get(acct_name, {})
            typ = meta.get("type", "")
            icon = ACCOUNT_ICONS.get(typ, " ")
            label = ACCOUNT_LABELS.get(typ, "")
            print(f"    {icon} {acct_name[:24]:<24s} ({label})  {sym}{bal:>+8.2f}")
        print(f"    {'─' * 36}")
        print(f"    {'合计':<16s} {sym}{cur_total:>+10.2f}")

    return result


def report_expense(records_dir=None, month=None):
    """消费分析 — 按币种分组"""
    accounts = load_accounts()
    all_records = _read_records(records_dir, month)

    by_acct = defaultdict(list)
    for row in all_records:
        acct_name = row.get("account_name", "").strip()
        currency = row.get("currency", "").strip() or "CNY"
        if acct_name:
            by_acct[(acct_name, currency)].append(row)

    acct_map = {(a["name"], a["currency"]): a for a in accounts}
    sym_map = models.CURRENCY_SYMBOLS

    result = {}
    for (acct_name, currency), records in by_acct.items():
        records.sort(key=lambda r: r["date"])
        acct = acct_map.get((acct_name, currency))
        if not acct:
            continue

        # Find last checkin
        last_checkin_idx = -1
        for i, r in enumerate(records):
            if r.get("category") == "checkin":
                last_checkin_idx = i
        start_idx = last_checkin_idx + 1

        cur = acct["currency"]
        if cur not in result:
            result[cur] = {"total": 0.0}

        for r in records[start_idx:]:
            if r.get("category") != "expense":
                continue
            try:
                result[cur]["total"] += abs(float(r["amount"]))
            except (ValueError, KeyError):
                pass

    for cur in result:
        result[cur]["total"] = round(result[cur]["total"], 2)

    for cur in sorted(result.keys()):
        if result[cur]["total"] == 0:
            continue
        sym = sym_map.get(cur, "")
        print(f"\n  📊 消费分析 [{cur}] {month or ''}")
        print(f"    总支出: {sym}{result[cur]['total']:.2f}")

    return result


def report_income(records_dir=None, month=None):
    """收入分析"""
    all_records = _read_records(records_dir, month)
    totals = defaultdict(float)
    for r in all_records:
        if r.get("category") == "income":
            try:
                totals[r.get("currency", "CNY")] += float(r["amount"])
            except (ValueError, KeyError):
                pass

    for cur in sorted(totals.keys()):
        totals[cur] = round(totals[cur], 2)
        sym = models.CURRENCY_SYMBOLS.get(cur, "")
        print(f"\n  📥 收入来源 [{cur}]")
        print(f"    总额 {sym}{totals[cur]:.2f}")

    return dict(totals)


def report_flow(records_dir=None, month=None):
    """资金流向 — 转账汇总"""
    all_records = _read_records(records_dir, month)
    transfers = [r for r in all_records if r.get("category") in ("transfer", "transfer_in", "transfer_out")]

    if not transfers:
        return

    from collections import Counter
    by_desc = Counter()
    for r in transfers:
        if r.get("category") == "transfer_in":
            continue
        try:
            amt = abs(float(r["amount"]))
        except (ValueError, KeyError):
            continue
        desc = r.get("transfer_account") or r.get("description", "")
        cur = r.get("currency", "CNY")
        by_desc[(desc, cur)] += amt

    for (desc, cur), total in by_desc.most_common(10):
        sym = models.CURRENCY_SYMBOLS.get(cur, "")
        print(f"  🔄 {desc[:20]:<20s} {sym}{total:>10.2f}")


def list_txns(records_dir=None, month=None, account=None, category=None, limit=30):
    """列出交易"""
    all_records = _read_records(records_dir, month)

    if account:
        all_records = [r for r in all_records
                       if r.get("account_name", "").strip() == account]
    if category:
        all_records = [r for r in all_records
                       if r.get("category", "") == category]

    all_records.sort(key=lambda r: r["date"], reverse=True)
    all_records = all_records[:limit]

    if not all_records:
        print("  📭 暂无记录")
        return

    CATEGORY_LABELS = {"income": "收入", "expense": "支出",
                       "transfer": "转账", "transfer_in": "转入",
                       "transfer_out": "转出", "checkin": "📸校准"}
    sym_map = models.CURRENCY_SYMBOLS

    print(f"  {'日期':<21} {'账户':<16} {'币种':<5} {'类型':<6} {'金额':>12} {'说明'}")
    print("  " + "-" * 80)
    for r in all_records:
        sym = sym_map.get(r.get("currency", "CNY"), "")
        cat_label = CATEGORY_LABELS.get(r.get("category", ""), "")
        try:
            amt = float(r["amount"])
        except (ValueError, KeyError):
            amt = 0
        if r.get("category") == "checkin":
            amt_str = r.get("description", "")[:12]
        else:
            amt_str = f"{sym}{amt:>+8.2f}" if abs(amt) > 0 else ""
        desc = (r.get("description") or r.get("counterparty") or "")[:30]
        acct = r.get("account_name", "")[:16]
        date_str = r.get("date", "")[:19]
        print(f"  {date_str:<21} {acct:<16} {r.get('currency','CNY'):<5} {cat_label:<6} {amt_str:>12} {desc}")
