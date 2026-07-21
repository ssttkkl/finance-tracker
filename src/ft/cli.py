"""Finance Tracker CLI — ft 统一入口"""
import argparse
from decimal import Decimal
from pathlib import Path
from .report import render_finance_report, render_transactions
from .acct import acct_add, acct_list, acct_rename, acct_delete, acct_activate
from .adapters.export_csv import write_csv_export
from .adapters.portfolio_cli import render_portfolio
from .domain.application import ExportPayload
from .domain.imports import CASHFLOW_EXPORT_FIELDS, StatementImportCommand
from .runtime import build_services
from .schema import CSV_FIELDS


def _runtime_services():
    from .config import StorageConfigurationError, StorageSettings
    from .adapters.relational.runtime import StorageError

    try:
        bundle = build_services(StorageSettings.load())
    except (StorageConfigurationError, StorageError) as exc:
        print(f"ERROR: {exc}", file=__import__("sys").stderr)
        raise SystemExit(1) from exc
    for notice in getattr(bundle, "notices", ()):
        print(f"WARNING: {notice}", file=__import__("sys").stderr)
    return bundle


def _read_password_file(path: str | None) -> str | None:
    if path is None:
        return None
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    if not lines:
        raise ValueError("password file is empty")
    return lines[0]


def _statement_export(command: StatementImportCommand) -> ExportPayload:
    from .adapters.statement_import import StatementParser

    rows = StatementParser().parse(command)
    fields = CSV_FIELDS if command.source == "dfzq" else CASHFLOW_EXPORT_FIELDS
    return ExportPayload(tuple(rows), fieldnames=tuple(fields))


def main(argv=None):
    try:
        return _main(argv)
    except Exception as exc:
        from .adapters.relational.runtime import StorageError
        if isinstance(exc, StorageError):
            print(f"ERROR: {exc}", file=__import__("sys").stderr)
            raise SystemExit(1) from exc
        raise


def _main(argv=None):
    parser = argparse.ArgumentParser(
        prog="ft",
        description=(
            "Finance Tracker (PostgreSQL or file SQLite; no fallback, dual-write, or implicit migration). "
            "SQLite busy, permission, and schema failures are reported with sanitized storage codes."
        ),
        allow_abbrev=False,
    )
    sub = parser.add_subparsers(dest="cmd")

    # acct
    acct_p = sub.add_parser("acct", help="名称唯一的多币种账户管理")
    acct_sub = acct_p.add_subparsers(dest="acct_cmd")

    acct_add_p = acct_sub.add_parser("add", help="新增名称唯一账户（可选初始化零余额币种口袋）")
    acct_add_p.add_argument("name")
    acct_add_p.add_argument("--type", required=True,
                            choices=["cash", "loan", "lend", "security", "crypto"])
    acct_add_p.add_argument(
        "--currency", help="Optional zero-balance pocket currency (e.g. CNY, USD, JPY)",
    )

    acct_sub.add_parser("list", help="列出所有账户")

    acct_rename_p = acct_sub.add_parser("rename", help="重命名")
    acct_rename_p.add_argument("old_name")
    acct_rename_p.add_argument("new_name")

    acct_delete_p = acct_sub.add_parser("delete", help="删除账户")
    acct_delete_p.add_argument("name")

    acct_deact_p = acct_sub.add_parser("deactivate", help="停用账户")
    acct_deact_p.add_argument("name")

    acct_act_p = acct_sub.add_parser("activate", help="启用账户")
    acct_act_p.add_argument("name")

    # report
    rpt = sub.add_parser("report", help="资产负债 + 消费总览")
    rpt.add_argument("--month", help="月份 (YYYY-MM)")

    # list
    lst = sub.add_parser("list", help="列出交易")
    lst.add_argument("--month")
    lst.add_argument("--account")
    lst.add_argument("--category", choices=["income", "expense", "transfer", "transfer_in", "transfer_out", "checkin"])
    lst.add_argument("--limit", type=int, default=30)

    # checkin
    chk = sub.add_parser("checkin", help="记录余额快照")
    chk.add_argument("account", help="账户名")
    chk.add_argument("--balance", required=True)
    chk.add_argument("--currency", required=True)
    chk.add_argument("--date")

    # transfer
    trf = sub.add_parser("transfer", help="转账/换汇")
    trf.add_argument("--from", dest="from_acct", required=True)
    trf.add_argument("--to", dest="to_acct", required=True)
    trf.add_argument("--from-currency", required=True)
    trf.add_argument("--to-currency", required=True)
    trf.add_argument("--amount", required=True)
    trf.add_argument("--to-amount", dest="to_amount",
                     help="跨币种目标金额")
    trf.add_argument("--date")
    trf.add_argument("--description", default="")

    # add (single transaction)
    add_p = sub.add_parser("add", help="单笔录入")
    add_p.add_argument("-a", "--amount", required=True)
    add_p.add_argument("-c", "--counterparty", required=True)
    add_p.add_argument("--account", required=True)
    add_p.add_argument("--currency", required=True)
    add_p.add_argument("-d", "--description", default="")
    add_p.add_argument("--source", default="")
    add_p.add_argument("--platform", default="")
    add_p.add_argument("--date")

    # stock
    stk = sub.add_parser("stock", help="股票交易")
    stk_sub = stk.add_subparsers(dest="stock_cmd")

    buy_p = stk_sub.add_parser("buy", help="买入")
    buy_p.add_argument("--ticker", required=True)
    buy_p.add_argument("--shares", required=True)
    buy_p.add_argument("--price", required=True)
    buy_p.add_argument("--commission", default="0")
    buy_p.add_argument("--account", required=True)
    buy_p.add_argument("--currency")
    buy_p.add_argument("--note", default="")
    buy_p.add_argument("--date")

    sell_p = stk_sub.add_parser("sell", help="卖出")
    sell_p.add_argument("--ticker", required=True)
    sell_p.add_argument("--shares", required=True)
    sell_p.add_argument("--price", required=True)
    sell_p.add_argument("--commission", default="0")
    sell_p.add_argument("--account", required=True)
    sell_p.add_argument("--currency")
    sell_p.add_argument("--note", default="")
    sell_p.add_argument("--date")

    swap_p = stk_sub.add_parser("swap", help="币币兑换（持仓换持仓，成本结转）")
    swap_p.add_argument("--from-ticker", required=True)
    swap_p.add_argument("--from-shares", required=True)
    swap_p.add_argument("--to-ticker", required=True)
    swap_p.add_argument("--to-shares", required=True)
    swap_p.add_argument("--account", required=True)
    swap_p.add_argument("--currency")
    swap_p.add_argument("--note", default="")
    swap_p.add_argument("--date")

    dep_p = stk_sub.add_parser("deposit", help="入金")
    dep_p.add_argument("--amount", required=True)
    dep_p.add_argument("--account", required=True)
    dep_p.add_argument("--currency")
    dep_p.add_argument("--note", default="")
    dep_p.add_argument("--date")

    wd_p = stk_sub.add_parser("withdraw", help="出金")
    wd_p.add_argument("--amount", required=True)
    wd_p.add_argument("--account", required=True)
    wd_p.add_argument("--currency")
    wd_p.add_argument("--note", default="")
    wd_p.add_argument("--date")

    div_p = stk_sub.add_parser("dividend", help="股息")
    div_p.add_argument("--ticker", required=True)
    div_p.add_argument("--amount", required=True)
    div_p.add_argument("--account", required=True)
    div_p.add_argument("--currency")
    div_p.add_argument("--note", default="")
    div_p.add_argument("--date")

    checkin_p = stk_sub.add_parser("checkin", help="校正持仓或现金")
    checkin_p.add_argument("--account", required=True)
    checkin_p.add_argument("--ticker")
    checkin_p.add_argument("--shares")
    checkin_p.add_argument("--avg-cost")
    checkin_p.add_argument("--cash")
    checkin_p.add_argument("--currency")
    checkin_p.add_argument("--note", default="")
    checkin_p.add_argument("--date")

    # stock convert
    cv_stk = stk_sub.add_parser(
        "convert", help="股票对账单→stock CSV", allow_abbrev=False,
    )
    cv_stk.add_argument("file", help="对账单文件路径")
    cv_stk.add_argument("-s", "--source", required=True, help="券商类型（如 dfzq）")
    cv_stk.add_argument("-o", "--output", required=True, help="输出CSV路径")
    cv_stk.add_argument("--password-file", help="从文件首行读取 PDF 密码")
    cv_stk.add_argument(
        "--currency", default="CNY",
        help="3-letter currency code default for stock convert",
    )

    stk_sub.add_parser("list", help="持仓总览")

    # convert — account routing from bill + mapping (no CLI account override)

    rel = sub.add_parser("relations", help="账务关系检查与审查")
    rel_sub = rel.add_subparsers(dest="relations_cmd")
    rel_pending = rel_sub.add_parser("pending", help="列出 pending_review 关系")
    rel_pending.add_argument("--kind", default=None)
    rel_check = rel_sub.add_parser("check", help="对种子事实重跑关系检查")
    rel_check.add_argument("--fact-id", action="append", default=[])
    rel_check.add_argument("--batch-id", default=None)
    rel_accept = rel_sub.add_parser("accept", help="接受 pending 关系")
    rel_accept.add_argument("relation_id")
    rel_accept.add_argument("--actor", default="cli-user")
    rel_accept.add_argument("--reason", default="")
    rel_reject = rel_sub.add_parser("reject", help="拒绝 pending 关系")
    rel_reject.add_argument("relation_id")
    rel_reject.add_argument("--actor", default="cli-user")
    rel_reject.add_argument("--reason", default="rejected")
    rel_later = rel_sub.add_parser("later", help="稍后处理（仍 pending）")
    rel_later.add_argument("relation_id")
    rel_later.add_argument("--actor", default="cli-user")
    rel_alias = rel_sub.add_parser("alias-add", help="添加账户别名（仅增强匹配）")
    rel_alias.add_argument("--type", dest="alias_type", default="card_tail")
    rel_alias.add_argument("--value", required=True)
    rel_alias.add_argument("--account", required=True)
    fact_del = sub.add_parser("fact-delete", help="可审计逻辑删除正式现金事实")
    fact_del.add_argument("fact_id")
    fact_del.add_argument("--actor", default="cli-user")
    fact_del.add_argument("--reason", required=True)

    cv = sub.add_parser("convert", help="步骤① 账单→统一CSV", allow_abbrev=False)
    cv.add_argument("file", help="账单文件路径")
    cv.add_argument("-s", "--source", required=True,
                    choices=["alipay", "wechat", "icbc", "icbc-debit", "ccb-debit"],
                    help="账单类型")
    cv.add_argument("-o", "--output", required=True, help="输出CSV路径")
    cv.add_argument("--password-file", help="从文件首行读取工行 PDF 密码")
    cv.add_argument(
        "--currency", default=None,
        help="Optional default currency when a row has none (3-letter code)",
    )

    statement_import = sub.add_parser(
        "import",
        help="原始账单导入（按账户名路由、按行币种入账；禁止 --account）",
        allow_abbrev=False,
    )
    statement_import.add_argument("file", help="原始账单文件路径")
    statement_import.add_argument(
        "--source", required=True,
        choices=["alipay", "wechat", "icbc", "icbc-debit", "ccb-debit", "dfzq"],
    )
    statement_import.add_argument(
        "--currency", default=None,
        help="Optional default currency when a row has none (3-letter code)",
    )
    statement_import.add_argument("--password-file", help="从文件首行读取 PDF 密码")

    args = parser.parse_args(argv)

    if not args.cmd:
        parser.print_help()
        return


    if args.cmd == "relations":
        services = _runtime_services()
        if not args.relations_cmd:
            print("usage: ft relations {pending|check|accept|reject|later|alias-add}")
            raise SystemExit(2)
        if args.relations_cmd == "pending":
            rows = services.relations.list_pending(kind=args.kind)
            for row in rows:
                print(
                    f"{row['id']}\t{row['kind']}\t{row['status']}\t"
                    f"{row['primary_fact_id']}\t{row['secondary_fact_id']}\t"
                    f"{row.get('confidence','')}\t{row.get('rule_id','')}"
                )
            return
        if args.relations_cmd == "check":
            result = services.relations.check(
                seed_fact_ids=args.fact_id or None,
                seed_batch_id=args.batch_id,
                trigger="manual_range" if (args.fact_id or args.batch_id) else "full_recompute",
            )
            print(result.message)
            if not result.ok:
                raise SystemExit(1)
            return
        if args.relations_cmd == "accept":
            result = services.relations.accept(args.relation_id, actor=args.actor, reason=args.reason)
            print(result.message)
            if not result.ok:
                raise SystemExit(1)
            return
        if args.relations_cmd == "reject":
            result = services.relations.reject(args.relation_id, actor=args.actor, reason=args.reason)
            print(result.message)
            if not result.ok:
                raise SystemExit(1)
            return
        if args.relations_cmd == "later":
            result = services.relations.later(args.relation_id, actor=args.actor)
            print(result.message)
            if not result.ok:
                raise SystemExit(1)
            return
        if args.relations_cmd == "alias-add":
            from sqlalchemy import select
            from ft.adapters.relational.models import AccountModel
            with services.uow as uow:
                session = uow._state().session
                acc = session.scalar(select(AccountModel).where(
                    AccountModel.workspace_id == uow.workspace_id,
                    AccountModel.name == args.account,
                ))
                if acc is None:
                    print(f"account not found: {args.account}")
                    raise SystemExit(1)
                alias_id = uow.account_aliases.add(
                    alias_type=args.alias_type,
                    alias_value=args.value,
                    account_id=acc.id,
                )
                uow.commit()
            print(alias_id)
            return
        print("unknown relations command")
        raise SystemExit(2)

    if args.cmd == "fact-delete":
        services = _runtime_services()
        result = services.relations.logical_delete_cash(
            args.fact_id, actor=args.actor, reason=args.reason,
        )
        print(result.message)
        if not result.ok:
            raise SystemExit(1)
        return

    if args.cmd == "import":
        result = _runtime_services().statement_import.import_statement(StatementImportCommand(
            source_path=args.file,
            source=args.source,
            currency=args.currency,
            password=_read_password_file(args.password_file),
        ))
        if not result.ok:
            print(f"❌ {result.message}")
            raise SystemExit(1)
        if result.details.get("duplicate"):
            print("📭 该账单已导入")
        else:
            by_account = result.details.get("by_account") or {}
            if by_account:
                parts = ", ".join(f"{name}:{count}" for name, count in sorted(by_account.items()))
                print(f"✅ 已导入 {result.count} 条（{parts}） → selected database")
            else:
                print(f"✅ 已导入 {result.count} 条 → selected database")
        return

    if args.cmd == "acct":
        bundle = _runtime_services()
        if not args.acct_cmd:
            acct_list(bundle.queries)
            return
        if args.acct_cmd == "add":
            acct_add(bundle.accounts, args.name, args.type, args.currency)
        elif args.acct_cmd == "list":
            acct_list(bundle.queries)
        elif args.acct_cmd == "rename":
            acct_rename(bundle.accounts, args.old_name, args.new_name)
        elif args.acct_cmd == "delete":
            acct_delete(bundle.accounts, args.name)
        elif args.acct_cmd == "activate":
            acct_activate(bundle.accounts, args.name, True)
        elif args.acct_cmd == "deactivate":
            acct_activate(bundle.accounts, args.name, False)
        return

    if args.cmd == "add":
        from .schema import CURRENCY_SYMBOLS
        service = _runtime_services().cashflow
        result = service.add_manual_transaction(
            amount=Decimal(args.amount),
            counterparty=args.counterparty,
            account_name=args.account,
            currency=args.currency,
            description=args.description,
            source=args.source,
            date=args.date,
        )
        if not result.ok:
            print(f"❌ {result.error.message}")
            raise SystemExit(1)
        sym = CURRENCY_SYMBOLS.get(result.row["currency"], "")
        print(f"✅ 已记录: {sym}{Decimal(args.amount):+.2f} {args.counterparty} ({args.account})")
        return

    if args.cmd == "convert":
        payload = _statement_export(
            StatementImportCommand(
                source_path=args.file, source=args.source,
                password=_read_password_file(args.password_file),
                currency=args.currency,
            )
        )
        if not payload.rows:
            print("❌ 无数据可输出")
            return
        write_csv_export(payload, args.output)
        print(f"✅ 已转换 {len(payload.rows)} 条 → {args.output}")
        return

    if args.cmd == "report":
        result = _runtime_services().queries.report(month=args.month)
        render_finance_report(result, month=args.month)
        return

    if args.cmd == "list":
        result = _runtime_services().queries.list_transactions(
            month=args.month, account=args.account,
            category=args.category, limit=args.limit,
        )
        render_transactions(result)
        return

    if args.cmd == "checkin":
        from .schema import CURRENCY_SYMBOLS
        service = _runtime_services().cashflow
        result = service.checkin_balance(
            account_name=args.account,
            balance=Decimal(args.balance),
            date=args.date,
            currency=args.currency,
        )
        if not result.ok:
            print(f"❌ {result.error.message}")
            raise SystemExit(1)
        sym = CURRENCY_SYMBOLS.get(result.row["currency"], "")
        print(f"✅ {args.account}: 余额校准 {sym}{Decimal(args.balance):.2f} ({result.details['day']})")
        return

    if args.cmd == "transfer":
        from .schema import CURRENCY_SYMBOLS
        service = _runtime_services().transfers
        result = service.transfer(
            from_name=args.from_acct, to_name=args.to_acct,
            amount=Decimal(args.amount),
            to_amount=Decimal(args.to_amount) if args.to_amount is not None else None,
            date=args.date, description=args.description,
            from_currency=args.from_currency, to_currency=args.to_currency,
        )
        if not result.ok:
            print(f"❌ {result.error.message}")
            raise SystemExit(1)
        if result.details.get("warning"):
            print(f"⚠️ {result.details['warning']}")
        amount = result.details["amount"]
        to_amount = result.details["to_amount"]
        from_currency = result.details["from_currency"]
        to_currency = result.details["to_currency"]
        from_sym = CURRENCY_SYMBOLS.get(from_currency, "")
        to_sym = CURRENCY_SYMBOLS.get(to_currency, "")
        print(f"✅ {args.from_acct} {from_sym}{-amount:,.2f} → {args.to_acct} {to_sym}{to_amount:,.2f} ({result.details['date']})")
        if "rate" in result.details:
            print(f"   汇率: 1 {to_currency} = {result.details['rate']:.4f} {from_currency}")
        return

    if args.cmd == "stock":
        if args.stock_cmd == "convert":
            payload = _statement_export(StatementImportCommand(
                source_path=args.file, source=args.source,
                password=_read_password_file(args.password_file),
                currency=args.currency or "CNY",
            ))
            if payload.rows:
                write_csv_export(payload, args.output)
                print(f"✅ 已转换 {len(payload.rows)} 条记录 → {args.output}")
            else:
                print("❌ 未解析到任何交易记录")
            return
        bundle = _runtime_services()
        if not args.stock_cmd:
            render_portfolio(bundle.portfolio.get_portfolio())
            return

        service = bundle.investments
        try:
            result = None
            if args.stock_cmd == "buy":
                result = service.buy(
                    args.ticker, args.shares, args.price, args.commission,
                    args.currency, args.account, args.note, args.date,
                )
            elif args.stock_cmd == "sell":
                result = service.sell(
                    args.ticker, args.shares, args.price, args.commission,
                    args.currency, args.account, args.note, args.date,
                )
            elif args.stock_cmd == "swap":
                result = service.swap(
                    args.account, args.from_ticker, args.from_shares,
                    args.to_ticker, args.to_shares, args.currency,
                    args.note, args.date,
                )
            elif args.stock_cmd == "deposit":
                result = service.deposit(
                    args.amount, args.currency, args.account, args.note, args.date,
                )
            elif args.stock_cmd == "withdraw":
                result = service.withdraw(
                    args.amount, args.currency, args.account, args.note, args.date,
                )
            elif args.stock_cmd == "dividend":
                result = service.dividend(
                    args.ticker, args.amount, args.currency, args.account,
                    args.note, args.date,
                )
            elif args.stock_cmd == "checkin":
                if args.ticker and args.shares is not None and args.avg_cost is not None:
                    result = service.checkin_ticker(
                        args.ticker, args.shares, args.avg_cost, args.currency,
                        args.account, args.note, args.date,
                    )
                elif args.cash is not None:
                    result = service.checkin_cash(
                        args.cash, args.currency, args.account, args.note, args.date,
                    )
                else:
                    print("❌ 请指定 --ticker+--shares+--avg-cost 或 --cash")
                    return
            elif args.stock_cmd == "list":
                render_portfolio(bundle.portfolio.get_portfolio())
                return
            if result is not None and not result.ok:
                print(f"❌ {result.message}")
                raise SystemExit(1)
            if result is not None and result.message:
                print(result.message)
        except (ValueError, FileNotFoundError) as exc:
            print(f"❌ {exc}")
            raise SystemExit(1)
        return


if __name__ == "__main__":
    main()
