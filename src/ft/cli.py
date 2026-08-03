"""Finance Tracker 的统一 CLI 入口。"""
import argparse
import contextlib
import io
import logging
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
        print(f"错误：{exc}", file=__import__("sys").stderr)
        raise SystemExit(1) from exc
    for notice in getattr(bundle, "notices", ()):
        print(f"警告：{notice}", file=__import__("sys").stderr)
    return bundle


def _read_password_file(path: str | None) -> str | None:
    if path is None:
        return None
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    if not lines:
        raise ValueError("密码文件为空")
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
            print(f"错误：{exc}", file=__import__("sys").stderr)
            raise SystemExit(1) from exc
        raise


def _main(argv=None):
    parser = argparse.ArgumentParser(
        prog="ft",
        description=(
            "Finance Tracker（支持 PostgreSQL 或文件型 SQLite；不得自动回退（no fallback）、"
            "不得双写（dual-write）、不得隐式迁移（implicit migration））。"
            "SQLite 忙碌、权限或 schema 错误会以脱敏后的存储错误码报告。"
        ),
        allow_abbrev=False,
    )
    sub = parser.add_subparsers(dest="cmd")

    web = sub.add_parser("web", help="仅在本机启动收支账本只读 API")
    web.add_argument("--port", type=int, default=8000, help="本机 API 端口（默认 8000）")

    projections = sub.add_parser("projections", help="维护收支投影")
    projections_sub = projections.add_subparsers(dest="projections_cmd")
    projections_sub.add_parser("rebuild", help="显式全量重建当前工作区的收支投影")
    projections_sub.add_parser("status", help="查看当前工作区的收支投影状态")

    # acct
    acct_p = sub.add_parser("acct", help="名称唯一的多币种账户管理")
    acct_sub = acct_p.add_subparsers(dest="acct_cmd")

    acct_add_p = acct_sub.add_parser("add", help="新增名称唯一账户（可选初始化指定币种的零余额）")
    acct_add_p.add_argument("name")
    acct_add_p.add_argument("--type", required=True,
                            choices=["cash", "loan", "lend", "security", "crypto"])
    acct_add_p.add_argument(
        "--currency", help="可选：要初始化为零余额的币种（如 CNY、USD、JPY）",
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
    rpt.add_argument("--month", help="月份（YYYY-MM）")

    # list
    lst = sub.add_parser("list", help="列出交易")
    lst.add_argument("--month")
    lst.add_argument("--account")
    lst.add_argument("--category", choices=["income", "expense", "transfer", "transfer_in", "transfer_out", "checkin"])
    lst.add_argument("--limit", type=int, default=30)

    # checkin
    chk = sub.add_parser("checkin", help="校准账户余额")
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
    trf.add_argument("--note", default="")

    # add (single transaction)
    add_p = sub.add_parser("add", help="单笔录入")
    add_p.add_argument("-a", "--amount", required=True)
    add_p.add_argument("-c", "--counterparty", required=True)
    add_p.add_argument("--account", required=True)
    add_p.add_argument("--currency", required=True)
    add_p.add_argument("-d", "--note", default="")
    add_p.add_argument("--source", default="")
    add_p.add_argument("--platform", default="")
    add_p.add_argument("--date")

    # stock
    stk = sub.add_parser("stock", help="股票交易")
    stk_sub = stk.add_subparsers(dest="stock_cmd")

    buy_p = stk_sub.add_parser(
        "buy",
        help="买入（兼容命令；保存为一条 SWAP：付出现金、换入证券并记录手续费）",
    )
    buy_p.add_argument("--ticker", required=True)
    buy_p.add_argument("--shares", required=True)
    buy_p.add_argument("--price", required=True)
    buy_p.add_argument("--commission", default="0")
    buy_p.add_argument("--account", required=True)
    buy_p.add_argument("--currency")
    buy_p.add_argument("--note", default="")
    buy_p.add_argument("--date")

    sell_p = stk_sub.add_parser(
        "sell",
        help="卖出（兼容命令；保存为一条 SWAP：付出证券、换入现金并记录手续费）",
    )
    sell_p.add_argument("--ticker", required=True)
    sell_p.add_argument("--shares", required=True)
    sell_p.add_argument("--price", required=True)
    sell_p.add_argument("--commission", default="0")
    sell_p.add_argument("--account", required=True)
    sell_p.add_argument("--currency")
    sell_p.add_argument("--note", default="")
    sell_p.add_argument("--date")

    swap_p = stk_sub.add_parser(
        "swap",
        help=(
            "通用 SWAP 单行模型（持仓互换或币币兑换，并结转成本）。"
            " buy/sell 是 SWAP 的便捷命令；第三种资产收取的手续费通过"
            " --commission 和 --commission-asset 指定"
        ),
    )
    swap_p.add_argument("--from-ticker", required=True)
    swap_p.add_argument("--from-shares", required=True)
    swap_p.add_argument("--to-ticker", required=True)
    swap_p.add_argument("--to-shares", required=True)
    swap_p.add_argument("--account", required=True)
    swap_p.add_argument("--currency")
    swap_p.add_argument("--commission", default="0", help="手续费数量（可选）")
    swap_p.add_argument(
        "--commission-asset",
        default="",
        help="手续费资产代码；commission 大于 0 且省略此项时，默认使用 --from-ticker",
    )
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

    checkin_p = stk_sub.add_parser("checkin", help="校准持仓或投资账户现金余额")
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
        "convert", help="将投资对账单转换为 stock CSV", allow_abbrev=False,
    )
    cv_stk.add_argument("file", help="对账单文件路径")
    cv_stk.add_argument("-s", "--source", required=True, help="券商类型（如 dfzq）")
    cv_stk.add_argument("-o", "--output", required=True, help="输出 CSV 路径")
    cv_stk.add_argument("--password-file", help="从文件首行读取 PDF 密码")
    cv_stk.add_argument(
        "--currency", default="CNY",
        help="对账单未提供币种时使用的三字符币种代码",
    )

    list_p = stk_sub.add_parser("list", help="持仓总览（计价币种市值；可选统一折算为展示币种）")
    list_p.add_argument(
        "--display-currency",
        default=None,
        metavar="CCY",
        help="可选：将各持仓的计价币种市值折算为该 ISO 货币（如 CNY）；省略则不折算",
    )

    # convert — account routing from bill + mapping (no CLI account override)

    rel = sub.add_parser("relations", help="账务关系检查与审查")
    rel_sub = rel.add_subparsers(dest="relations_cmd")
    rel_pending = rel_sub.add_parser("pending", help="列出待审核关系（pending_review）")
    rel_pending.add_argument("--kind", default=None)
    rel_check = rel_sub.add_parser("check", help="以指定账本记录为种子重新检查关系")
    rel_check.add_argument("--fact-id", action="append", default=[])
    rel_check.add_argument("--batch-id", default=None)
    rel_accept = rel_sub.add_parser("accept", help="确认待审核关系（待配对关系须指定 --other）")
    rel_accept.add_argument("relation_id")
    rel_accept.add_argument("--other", dest="other_fact_id", default=None, help="待配对关系的对侧流水 ID")
    rel_accept.add_argument("--actor", default="cli-user")
    rel_accept.add_argument("--reason", default="")
    rel_reject = rel_sub.add_parser("reject", help="驳回待审核关系")
    rel_reject.add_argument("relation_id")
    rel_reject.add_argument("--actor", default="cli-user")
    rel_reject.add_argument("--reason", default="rejected")
    rel_later = rel_sub.add_parser("later", help="稍后处理（保持待审核状态）")
    rel_later.add_argument("relation_id")
    rel_later.add_argument("--actor", default="cli-user")
    rel_alias = rel_sub.add_parser("alias-add", help="添加账户别名（仅增强匹配）")
    rel_alias.add_argument("--type", dest="alias_type", default="card_tail")
    rel_alias.add_argument("--value", required=True)
    rel_alias.add_argument("--account", required=True)
    fact_del = sub.add_parser("fact-delete", help="以可审计方式逻辑删除现金流水")
    fact_del.add_argument("fact_id")
    fact_del.add_argument("--actor", default="cli-user")
    fact_del.add_argument("--reason", required=True)

    cv = sub.add_parser("convert", help="步骤 1：将账单转换为统一 CSV", allow_abbrev=False)
    cv.add_argument("file", help="账单文件路径")
    cv.add_argument("-s", "--source", required=True,
                    choices=["alipay", "wechat", "icbc", "icbc-debit", "ccb-debit", "icbc-asia-current-account"],
                    help="账单类型")
    cv.add_argument("-o", "--output", required=True, help="输出 CSV 路径")
    cv.add_argument("--password-file", help="从文件首行读取工行 PDF 密码")
    cv.add_argument(
        "--currency", default=None,
        help="账单行未提供币种时使用的三字符币种代码",
    )

    statement_import = sub.add_parser(
        "import",
        help="原始账单导入（按账户名路由、按行币种入账；投资账单需指定 --account）",
        allow_abbrev=False,
    )
    statement_import.add_argument("file", help="原始账单文件路径")
    statement_import.add_argument(
        "--source", required=True,
        choices=["alipay", "wechat", "icbc", "icbc-debit", "ccb-debit", "icbc-asia-current-account", "dfzq", "ibkr", "schwab", "usmart-hk", "usmart_hk", "binance", "okx", "polymarket"],
    )
    statement_import.add_argument(
        "--account", default=None,
        help="目标账户（投资账单必需，现金账单禁用）",
    )
    statement_import.add_argument(
        "--currency", default=None,
        help="账单行未提供币种时使用的三字符币种代码",
    )
    statement_import.add_argument("--password-file", help="从文件首行读取 PDF 密码")


    # sync — connector API sync
    sync_p = sub.add_parser(
        "sync",
        help="同步交易所/Polymarket 交易历史（API 拉取）",
        allow_abbrev=False,
    )
    sync_p.add_argument(
        "--source", required=True,
        choices=["binance", "kraken", "okx", "polymarket"],
        help="同步数据源",
    )
    sync_p.add_argument(
        "--account", required=True,
        help="目标账户名称",
    )
    sync_p.add_argument(
        "--full", action="store_true", default=False,
        help="忽略游标，强制全量同步",
    )
    sync_p.add_argument(
        "--batch-size", type=int, default=500,
        help="每批次事务大小（默认 500）",
    )

    args = parser.parse_args(argv)

    if not args.cmd:
        parser.print_help()
        return

    if args.cmd == "web":
        from ft.web.app import DEFAULT_WEB_ORIGIN, create_runtime_app, validate_local_origin
        from ft.adapters.relational.runtime import StorageError
        import os
        import uvicorn

        if not 1 <= args.port <= 65535:
            parser.error("--port 必须在 1 到 65535 之间")
        try:
            origin = validate_local_origin(os.environ.get("FT_WEB_ORIGIN", DEFAULT_WEB_ORIGIN))
            app = create_runtime_app()
        except (StorageError, ValueError) as exc:
            print(f"错误：{exc}", file=__import__("sys").stderr)
            raise SystemExit(1) from exc
        print(f"本机账本 API 已准备就绪：http://127.0.0.1:{args.port}")
        print(f"允许的前端来源：{origin}")
        uvicorn.run(app, host="127.0.0.1", port=args.port, log_level="warning")
        return

    if args.cmd == "projections":
        from sqlalchemy.exc import SQLAlchemyError
        from .adapters.relational import create_relational_engine, create_session_factory, create_web_readonly_engine
        from .adapters.relational.dialect import RelationalEngineError
        from .adapters.relational.runtime import StorageError, storage_error
        from .application.cash_projections import CashProjectionService
        from .config import StorageConfigurationError, StorageSettings
        from .domain.cash_projection import CashProjectionError

        try:
            settings = StorageSettings.load()
        except StorageConfigurationError as exc:
            raise StorageError("storage.config") from exc
        try:
            engine = (
                create_relational_engine(settings.database_url)
                if args.projections_cmd == "rebuild"
                else create_web_readonly_engine(settings.database_url)
            )
        except RelationalEngineError as exc:
            raise StorageError(exc.code, settings.database_url) from exc
        try:
            service = CashProjectionService(create_session_factory(engine), settings.workspace_id)
            try:
                status = service.rebuild() if args.projections_cmd == "rebuild" else service.status()
            except SQLAlchemyError as exc:
                raise storage_error(exc, settings.database_url) from exc
            except CashProjectionError as exc:
                print(f"错误：{exc.code}", file=__import__("sys").stderr)
                raise SystemExit(1) from exc
            except RuntimeError as exc:
                code = str(exc)
                if not code.startswith("projection."):
                    raise
                print(f"错误：{code}", file=__import__("sys").stderr)
                raise SystemExit(1) from exc
        finally:
            try:
                engine.dispose()
            except RelationalEngineError as exc:
                raise StorageError(exc.code, settings.database_url) from exc
        print("工作区：" + settings.workspace_id)
        print("可用性：" + status["availability"])
        print("投影版本：" + str(status["projection_version"]))
        print("规则版本：" + status["rules_version"])
        print("投影条目数：" + str(status["projection_count"]))
        print("成员数：" + str(status["member_count"]))
        if status.get("last_error_code"):
            print("失败码：" + status["last_error_code"])
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
            result = services.relations.accept(
                args.relation_id,
                actor=args.actor,
                reason=args.reason,
                other_fact_id=getattr(args, "other_fact_id", None),
            )
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
                    print(f"找不到账户：{args.account}")
                    raise SystemExit(1)
                alias_id = uow.account_aliases.add(
                    alias_type=args.alias_type,
                    alias_value=args.value,
                    account_id=acc.id,
                )
                uow.commit()
            print(alias_id)
            return
        print("未知的 `relations` 子命令")
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
        # T041-T046: Route to investment import for investment sources
        investment_sources = {"dfzq", "ibkr", "schwab", "usmart-hk", "usmart_hk", "binance", "okx", "polymarket"}

        if args.source in investment_sources:
            # Investment statement import
            if not args.account:
                print(f"错误：导入 {args.source} 账单时必须指定 --account")
                raise SystemExit(1)

            # T043: Check external tools for DFZQ
            if args.source in {"dfzq", "usmart-hk", "usmart_hk"} and Path(args.file).suffix.lower() == ".pdf":
                if args.source == "dfzq":
                    from .importers.dfzq import check_external_tools
                else:
                    from .importers.usmart_hk import check_external_tools
                tools = check_external_tools()

                if tools.get("qpdf") is None:
                    print("错误：找不到 qpdf")
                    print("\n解密 PDF 需要 qpdf。")
                    print("安装命令：brew install qpdf")
                    raise SystemExit(1)

                if tools.get("mutool") is None:
                    print("错误：找不到 mutool")
                    print("\n提取 PDF 文本需要 mutool。")
                    print("安装命令：brew install mupdf-tools")
                    raise SystemExit(1)

                print(f"使用 qpdf {tools['qpdf']}、mutool {tools['mutool']}")

            # T042: Verify account exists and is correct type
            bundle = _runtime_services()
            uow = bundle.uow

            with uow as session:
                account_dto = session.accounts.find(args.account)

                if account_dto is None:
                    print(f"错误：找不到账户 {args.account}")
                    print("\n可用账户：")
                    for acc in session.accounts.list():
                        print(f"  - {acc.name} ({acc.type})")
                    raise SystemExit(1)

                if account_dto.type not in {"security", "crypto"}:
                    print(f"错误：账户 {args.account} 的类型是 {account_dto.type}")
                    print("\n投资账单只能导入 security 或 crypto 类型的账户。")
                    raise SystemExit(1)

                session.rollback()

            # T044: Call investment import service
            from .application.investment_import import InvestmentImportService
            service = InvestmentImportService(uow)

            # Currency: CLI --currency if set; for ibkr leave None so service
            # can use 总结.基础货币 (no silent USD/CNY default).
            # For schwab: CLI or USD when unset (Transaction History is US$).
            if args.source == "ibkr":
                currency = args.currency  # may be None
            elif args.source == "schwab":
                currency = args.currency or "USD"
            elif args.source in {"usmart-hk", "usmart_hk"}:
                currency = args.currency
            else:
                currency = args.currency or "CNY"

            # T045: Progress reporting
            print(f"正在从 {Path(args.file).name} 导入 {args.source} 账单……")

            try:
                result = service.import_statement(
                    source=args.source,
                    source_path=args.file,
                    account_name=args.account,
                    currency=currency,
                    password=_read_password_file(args.password_file),
                )
            except Exception as e:
                # T046: Error enrichment
                print(f"导入失败：{e}")
                raise SystemExit(1)

            if not result.ok:
                print(f"❌ {result.message}")
                raise SystemExit(1)

            # T045: Success message
            details = result.details or {}
            batch_id = details.get("batch_id")
            if details.get("duplicate"):
                print(f"该账单已经导入（批次：{batch_id}）")
                print("没有新增账本记录。")
            else:
                print(f"已导入 {result.count} 条账本记录")
                print(f"批次 ID：{batch_id}")
                print(f"账户：{args.account}")

            return

        # 原始现金账单导入。
        if args.account:
            parser.error("--account 仅适用于投资账单导入")
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
                print(f"已向当前数据库导入 {result.count} 条（{parts}）")
            else:
                print(f"已向当前数据库导入 {result.count} 条")
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
            note=args.note,
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
            date=args.date, note=args.note,
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


    if args.cmd == "sync":
        from .application.sync_service import SyncService, EXCHANGE_PROVIDERS
        bundle = _runtime_services()
        service = SyncService(bundle.uow)

        # Build connector from credentials (CLI layer responsibility)
        try:
            if args.source in EXCHANGE_PROVIDERS:
                from .credentials import load_exchange_credentials
                from .adapters.connectors.ccxt_exchange import CcxtExchangeConnector
                creds = load_exchange_credentials(args.source)
                connector = CcxtExchangeConnector(provider=args.source, credentials=creds)
            elif args.source == "polymarket":
                from .credentials import load_polymarket_credentials
                from .adapters.connectors.polymarket import PolymarketConnector
                creds = load_polymarket_credentials()
                connector = PolymarketConnector(credentials=creds)
            else:
                print(f"错误：未知的同步数据源 {args.source}")
                raise SystemExit(1)
        except ValueError as exc:
            print(f"❌ {exc}")
            raise SystemExit(1)

        result = service.sync(
            provider=args.source,
            account_name=args.account,
            full=args.full,
            batch_size=args.batch_size,
            connector=connector,
        )
        if not result.ok:
            print(f"❌ {result.message}")
            raise SystemExit(1)
        details = result.details or {}
        print(f"✅ {result.message}")
        if details.get("raw_count"):
            print(f"   API 返回记录：{details['raw_count']}")
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
            render_portfolio(
                bundle.portfolio.get_portfolio(
                    display_currency=getattr(args, "display_currency", None),
                )
            )
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
                    commission=getattr(args, "commission", "0"),
                    commission_asset=getattr(args, "commission_asset", ""),
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
                try:
                    # A timed-out daemon quote worker may emit a yfinance
                    # diagnostic after get_portfolio returns.  Disable this
                    # third-party logger for the CLI process rather than using
                    # process-global stream redirection in that worker.
                    logging.getLogger("yfinance").disabled = True
                    # Provider diagnostics must never precede or corrupt the
                    # Finance Tracker-owned portfolio table.
                    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                        portfolio = bundle.portfolio.get_portfolio(
                            display_currency=getattr(args, "display_currency", None),
                        )
                    render_portfolio(
                        portfolio
                    )
                except ValueError as exc:
                    # ValuationError subclasses ValueError (invalid display currency, etc.)
                    code = getattr(exc, "code", None)
                    print(f"❌ {code or exc}")
                    raise SystemExit(1) from exc
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
