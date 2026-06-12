"""Finance Tracker CLI — ft 统一入口"""
import argparse
import sys
from .report import (
    report_networth, report_expense, report_income, report_flow, list_txns,
)
from .acct import acct_add, acct_list, acct_rename, acct_delete, acct_activate
from .transfer import do_transfer


def main():
    parser = argparse.ArgumentParser(prog="ft", description="📒 Finance Tracker")
    sub = parser.add_subparsers(dest="cmd")

    # acct
    acct_p = sub.add_parser("acct", help="账户管理")
    acct_sub = acct_p.add_subparsers(dest="acct_cmd")

    acct_add_p = acct_sub.add_parser("add", help="新增账户")
    acct_add_p.add_argument("name")
    acct_add_p.add_argument("--type", required=True,
                            choices=["cash", "loan", "lend", "security"])
    acct_add_p.add_argument("--currency", required=True,
                            choices=["CNY", "USD", "HKD"])

    acct_sub.add_parser("list", help="列出所有账户")

    acct_rename_p = acct_sub.add_parser("rename", help="重命名")
    acct_rename_p.add_argument("old_name")
    acct_rename_p.add_argument("new_name")
    acct_rename_p.add_argument("--currency", required=True)

    acct_delete_p = acct_sub.add_parser("delete", help="删除账户")
    acct_delete_p.add_argument("name")
    acct_delete_p.add_argument("--currency", required=True)

    acct_deact_p = acct_sub.add_parser("deactivate", help="停用账户")
    acct_deact_p.add_argument("name")
    acct_deact_p.add_argument("--currency", required=True)

    acct_act_p = acct_sub.add_parser("activate", help="启用账户")
    acct_act_p.add_argument("name")
    acct_act_p.add_argument("--currency", required=True)

    # report
    rpt = sub.add_parser("report", help="资产负债 + 消费总览")
    rpt.add_argument("--month", help="月份 (YYYY-MM)")

    # list
    lst = sub.add_parser("list", help="列出交易")
    lst.add_argument("--month")
    lst.add_argument("--account")
    lst.add_argument("--category", choices=["income", "expense", "transfer", "checkin"])
    lst.add_argument("--limit", type=int, default=30)

    # checkin
    chk = sub.add_parser("checkin", help="记录余额快照")
    chk.add_argument("account", help="账户名")
    chk.add_argument("--balance", type=float, required=True)
    chk.add_argument("--date")

    # transfer
    trf = sub.add_parser("transfer", help="转账/换汇")
    trf.add_argument("--from", dest="from_acct", required=True)
    trf.add_argument("--to", dest="to_acct", required=True)
    trf.add_argument("--amount", type=float, required=True)
    trf.add_argument("--to-amount", dest="to_amount", type=float,
                     help="跨币种目标金额")
    trf.add_argument("--date")
    trf.add_argument("--description", default="")

    # add (single transaction)
    add_p = sub.add_parser("add", help="单笔录入")
    add_p.add_argument("-a", "--amount", type=float, required=True)
    add_p.add_argument("-c", "--counterparty", required=True)
    add_p.add_argument("--account", required=True)
    add_p.add_argument("-d", "--description", default="")
    add_p.add_argument("--source", default="")
    add_p.add_argument("--platform", default="")
    add_p.add_argument("--date")

    # stock
    stk = sub.add_parser("stock", help="股票交易")
    stk_sub = stk.add_subparsers(dest="stock_cmd")

    buy_p = stk_sub.add_parser("buy", help="买入")
    buy_p.add_argument("--ticker", required=True)
    buy_p.add_argument("--shares", type=int, required=True)
    buy_p.add_argument("--price", type=float, required=True)
    buy_p.add_argument("--commission", type=float, default=0.0)
    buy_p.add_argument("--account", required=True)
    buy_p.add_argument("--currency", default="USD", choices=["CNY", "USD", "HKD"])
    buy_p.add_argument("--note", default="")
    buy_p.add_argument("--date")

    sell_p = stk_sub.add_parser("sell", help="卖出")
    sell_p.add_argument("--ticker", required=True)
    sell_p.add_argument("--shares", type=int, required=True)
    sell_p.add_argument("--price", type=float, required=True)
    sell_p.add_argument("--commission", type=float, default=0.0)
    sell_p.add_argument("--account", required=True)
    sell_p.add_argument("--currency", default="USD", choices=["CNY", "USD", "HKD"])
    sell_p.add_argument("--note", default="")
    sell_p.add_argument("--date")

    dep_p = stk_sub.add_parser("deposit", help="入金")
    dep_p.add_argument("--amount", type=float, required=True)
    dep_p.add_argument("--account", required=True)
    dep_p.add_argument("--currency", default="USD", choices=["CNY", "USD", "HKD"])
    dep_p.add_argument("--note", default="")
    dep_p.add_argument("--date")

    wd_p = stk_sub.add_parser("withdraw", help="出金")
    wd_p.add_argument("--amount", type=float, required=True)
    wd_p.add_argument("--account", required=True)
    wd_p.add_argument("--currency", default="USD", choices=["CNY", "USD", "HKD"])
    wd_p.add_argument("--note", default="")
    wd_p.add_argument("--date")

    div_p = stk_sub.add_parser("dividend", help="股息")
    div_p.add_argument("--ticker", required=True)
    div_p.add_argument("--amount", type=float, required=True)
    div_p.add_argument("--account", required=True)
    div_p.add_argument("--currency", default="USD", choices=["CNY", "USD", "HKD"])
    div_p.add_argument("--note", default="")
    div_p.add_argument("--date")

    checkin_p = stk_sub.add_parser("checkin", help="校正持仓或现金")
    checkin_p.add_argument("--account", required=True)
    checkin_p.add_argument("--ticker")
    checkin_p.add_argument("--shares", type=int)
    checkin_p.add_argument("--avg-cost", type=float)
    checkin_p.add_argument("--cash", type=float)
    checkin_p.add_argument("--note", default="")
    checkin_p.add_argument("--date")

    stk_sub.add_parser("list", help="持仓总览")

    # verify
    verify_p = sub.add_parser("verify", help="验证CSV与快照一致性")
    verify_p.add_argument("--fix", action="store_true", help="从CSV重建快照")

    # convert
    cv = sub.add_parser("convert", help="步骤① 账单→统一CSV")
    cv.add_argument("file", help="账单文件路径")
    cv.add_argument("-s", "--source", required=True,
                    choices=["alipay", "wechat", "icbc", "icbc-debit", "ccb-debit"],
                    help="账单类型")
    cv.add_argument("-o", "--output", required=True, help="输出CSV路径")
    cv.add_argument("--password", help="工行PDF密码")
    cv.add_argument("--account", help="覆盖账户名")
    cv.add_argument("--currency", default="CNY", choices=["CNY", "USD", "HKD"],
                    help="覆盖币种")

    # merge
    mg = sub.add_parser("merge", help="步骤② 合并去重CSV")
    mg.add_argument("files", nargs="+", help="输入CSV文件列表")
    mg.add_argument("-o", "--output", required=True, help="输出目录")

    # append
    ap = sub.add_parser("append", help="步骤③ 合并CSV落库")
    ap.add_argument("file", help="merged.csv 路径")

    args = parser.parse_args()

    if not args.cmd:
        parser.print_help()
        return

    if args.cmd == "acct":
        if not args.acct_cmd:
            acct_list()
            return
        if args.acct_cmd == "add":
            acct_add(args.name, args.type, args.currency)
        elif args.acct_cmd == "list":
            acct_list()
        elif args.acct_cmd == "rename":
            acct_rename(args.old_name, args.new_name, args.currency)
        elif args.acct_cmd == "delete":
            acct_delete(args.name, args.currency)
        elif args.acct_cmd == "activate":
            acct_activate(args.name, args.currency, True)
        elif args.acct_cmd == "deactivate":
            acct_activate(args.name, args.currency, False)
        return

    if args.cmd == "add":
        from datetime import datetime
        import csv
        from pathlib import Path
        from .accounts import load_accounts
        from . import models
        from .snapshot import load_snapshot, save_snapshot, update_balance

        # Lookup account
        accts = {a["name"]: a for a in load_accounts()}
        acct = accts.get(args.account)
        if not acct:
            print(f"❌ 未找到账户: {args.account}")
            return

        currency = acct.get("currency", "CNY")
        typ = acct["type"]
        category = "expense" if args.amount < 0 else "income"
        date_str = args.date or datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        day = date_str[:10]

        # Write CSV row
        type_dir = models.RECORDS_DIR / typ
        type_dir.mkdir(parents=True, exist_ok=True)
        day_path = type_dir / f"{day}.csv"

        existing = []
        if day_path.exists():
            with open(day_path, encoding="utf-8") as f:
                existing = list(csv.DictReader(f))

        new_row = {
            "date": date_str,
            "amount": str(args.amount),
            "currency": currency,
            "counterparty": args.counterparty,
            "description": args.description,
            "category": category,
            "account_name": args.account,
            "source": args.source,
            "platform": args.platform,
            "bill_source": "",
        }

        all_rows = existing + [new_row]
        all_rows.sort(key=lambda r: r["date"])

        with open(day_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=models.CSV_FIELDS)
            writer.writeheader()
            writer.writerows(all_rows)

        # Update snapshot
        snap = load_snapshot()
        update_balance(snap, args.account, args.amount)
        snap["updated_at"] = date_str
        save_snapshot(snap)

        # Print
        sym = {"CNY": "¥", "USD": "$", "HKD": "HK$"}.get(currency, "")
        print(f"✅ 已记录: {sym}{args.amount:+.2f} {args.counterparty} ({args.account})")
        return

    if args.cmd == "verify":
        from .accounts import load_accounts
        from . import models
        import csv
        from pathlib import Path
        import re
        from collections import defaultdict
        from .snapshot import load_snapshot, save_snapshot, set_balance

        records_dir = models.RECORDS_DIR
        ok = True

        if args.fix:
            # Rebuild ALL account types from CSV
            from .stock import repair_security
            repair_security()

            # Cash/Loan/Lend
            snap = load_snapshot()
            for typ in ("cash", "loan", "lend"):
                typedir = records_dir / typ
                if not typedir.exists():
                    continue
                acct_records = defaultdict(list)
                for csv_file in sorted(typedir.glob("*.csv")):
                    with open(csv_file, encoding="utf-8") as f:
                        for row in csv.DictReader(f):
                            acct = row.get("account_name", "").strip()
                            if acct:
                                acct_records[acct].append(row)

                for acct_name, records in acct_records.items():
                    records.sort(key=lambda r: r["date"])
                    last_ci = -1
                    for i, r in enumerate(records):
                        if r.get("category") == "checkin":
                            last_ci = i
                    if last_ci >= 0:
                        desc = records[last_ci].get("description", "")
                        m = re.search(r"[\d,]+\.?\d*", desc.replace(",", ""))
                        bal = float(m.group()) if m else 0.0
                        start = last_ci + 1
                    else:
                        bal = 0.0
                        start = 0
                    for r in records[start:]:
                        cat = r.get("category", "")
                        if cat in ("checkin", "transfer"):
                            continue
                        try:
                            bal += float(r["amount"])
                        except (ValueError, KeyError):
                            pass
                    set_balance(snap, acct_name, typ, round(bal, 2))

            snap["updated_at"] = "rebuilt"
            save_snapshot(snap)
            print("✅ 已从 CSV 重建全部账户快照")

        # --- Security verification ---
        from .stock import verify_security
        sec_ok, sec_lines = verify_security()
        print("🔍 Security 校验")
        for l in sec_lines:
            print(l)
        if not sec_ok:
            ok = False

        # --- Cash/Loan/Lend verification ---
        print("\n🔍 Cash/Loan/Lend 校验")
        accounts = {a["name"]: a for a in load_accounts() if a.get("active", True) and a["type"] != "security"}

        all_records = []
        for t in ["cash", "loan", "lend"]:
            d = records_dir / t
            if not d.exists():
                continue
            for f in sorted(d.glob("*.csv")):
                with open(f, encoding="utf-8") as fh:
                    all_records.extend([(t, row) for row in csv.DictReader(fh)])

        if not all_records:
            print("  📭 无现金类记录")
        else:
            errors = 0
            for typ, row in all_records:
                acct_name = row.get("account_name", "").strip()
                if acct_name and acct_name not in accounts:
                    errors += 1
                    if errors <= 5:
                        print(f"  ⚠️ 未知账户 \'{acct_name}\' 在 {typ} 记录中")
            if errors:
                print(f"  ❌ {errors} 条记录来自未知账户，请 ft acct add")
                ok = False
            else:
                print(f"  ✅ 共 {len(all_records)} 条记录，账户一致")

        if ok:
            print("\n✅ 全部校验通过")
        else:
            print("\n❌ 存在不一致，请检查")

        return

    if args.cmd == "convert":
        from .convert import do_convert
        do_convert(args.file, args.source, args.output,
                   password=args.password, account=args.account,
                   currency=args.currency)
        return

    if args.cmd == "merge":
        from .merge import do_merge
        do_merge(args.files, args.output)
        return

    if args.cmd == "append":
        from .append import do_append
        do_append(args.file)
        return

    if args.cmd == "report":
        report_networth()
        print()
        report_expense(month=args.month)
        print()
        report_flow()
        print()
        report_income(month=args.month)
        return

    if args.cmd == "list":
        list_txns(month=args.month, account=args.account,
                  category=args.category, limit=args.limit)
        return

    if args.cmd == "checkin":
        from datetime import datetime
        import csv
        from pathlib import Path
        from .accounts import find_account
        from . import models

        if not args.date:
            date_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        else:
            date_str = args.date + " 00:00:00"

        acct = find_account(args.account)
        if not acct:
            print(f"❌ 未找到账户: {args.account}")
            return

        sym = {"CNY": "¥", "USD": "$", "HKD": "HK$"}.get(acct["currency"], "")

        type_dir = models.RECORDS_DIR / acct["type"]
        type_dir.mkdir(parents=True, exist_ok=True)
        day = date_str[:10]
        day_path = type_dir / f"{day}.csv"

        existing = []
        if day_path.exists():
            with open(day_path, encoding="utf-8") as f:
                reader = csv.DictReader(f)
                existing = list(reader)

        new_row = {
            "date": date_str,
            "amount": "0",
            "currency": acct["currency"],
            "counterparty": "",
            "description": f"余额校准{sym}{args.balance:.2f}",
            "category": "checkin",
            "account_name": args.account,
            "source": "手动",
            "platform": "",
            "bill_source": "",
        }

        all_rows = existing + [new_row]
        all_rows.sort(key=lambda r: r["date"])

        with open(day_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=models.CSV_FIELDS)
            writer.writeheader()
            writer.writerows(all_rows)

        print(f"✅ {args.account}: 余额校准 {sym}{args.balance:.2f} ({day})")

        # Update snapshot
        from .snapshot import load_snapshot, save_snapshot, set_balance
        snap = load_snapshot()
        set_balance(snap, args.account, acct["type"], args.balance)
        snap["updated_at"] = date_str[:10]
        save_snapshot(snap)
        return

    if args.cmd == "transfer":
        do_transfer(
            from_name=args.from_acct, to_name=args.to_acct,
            amount=args.amount, to_amount=args.to_amount,
            date=args.date, description=args.description,
        )
        return

    if args.cmd == "stock":
        from .stock import (
            do_buy, do_sell, do_deposit, do_withdraw,
            do_dividend, do_checkin_ticker, do_checkin_cash,
            do_list,
        )

        if not args.stock_cmd:
            do_list()
            return

        if args.stock_cmd == "buy":
            do_buy(args.ticker, args.shares, args.price, args.commission,
                   args.currency, args.account, args.note, args.date)
        elif args.stock_cmd == "sell":
            do_sell(args.ticker, args.shares, args.price, args.commission,
                    args.currency, args.account, args.note, args.date)
        elif args.stock_cmd == "deposit":
            do_deposit(args.amount, args.currency, args.account, args.note, args.date)
        elif args.stock_cmd == "withdraw":
            do_withdraw(args.amount, args.currency, args.account, args.note, args.date)
        elif args.stock_cmd == "dividend":
            do_dividend(args.ticker, args.amount, args.currency, args.account, args.note, args.date)
        elif args.stock_cmd == "checkin":
            if args.ticker and args.shares is not None and args.avg_cost is not None:
                do_checkin_ticker(args.ticker, args.shares, args.avg_cost,
                                  args.currency or "USD", args.account, args.note, args.date)
            elif args.cash is not None:
                do_checkin_cash(args.cash, args.account, args.note, args.date)
            else:
                print("❌ 请指定 --ticker+--shares+--avg-cost 或 --cash")
        elif args.stock_cmd == "list":
            do_list()
        return


if __name__ == "__main__":
    main()
