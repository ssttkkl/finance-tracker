"""按数据源分派投资和现金账单导入命令。"""
import click
from pathlib import Path

from ft.cli import get_app_context


@click.group()
def import_cmd():
    """导入不同数据源的账单。"""
    pass


@import_cmd.command(name="statement")
@click.argument("file_path", type=click.Path(exists=True, path_type=Path))
@click.option(
    "--source",
    required=True,
    type=click.Choice(["dfzq", "binance", "okx", "polymarket"], case_sensitive=False),
    help="账单数据源（dfzq、binance、okx、polymarket）",
)
@click.option(
    "--account",
    required=True,
    help="目标账户名称",
)
@click.option(
    "--currency",
    default="CNY",
    help="默认币种（默认：CNY）",
)
def import_statement(file_path: Path, source: str, account: str, currency: str):
    """从文件导入投资账单。

    示例：
        ft import statement statement.txt --source dfzq --account 东方证券
    """
    # T043：检查东方证券导入所需的外部工具。
    if source == "dfzq":
        from ft.importers.dfzq import check_external_tools

        tools = check_external_tools()

        if tools.get("qpdf") is None:
            click.echo(
                click.style("错误：找不到 qpdf", fg="red", bold=True),
                err=True,
            )
            click.echo("\n解密 PDF 需要 qpdf。")
            click.echo("安装命令：brew install qpdf")
            raise click.Abort()

        if tools.get("mutool") is None:
            click.echo(
                click.style("错误：找不到 mutool", fg="red", bold=True),
                err=True,
            )
            click.echo("\n提取 PDF 文本需要 mutool。")
            click.echo("安装命令：brew install mupdf-tools")
            raise click.Abort()

        # 显示实际使用的工具版本，便于排查解析差异。
        click.echo(f"使用 qpdf {tools['qpdf']}、mutool {tools['mutool']}")

    # T042：获取工作单元并检查目标账户。
    ctx = get_app_context()
    uow = ctx["uow"]

    with uow as session:
        account_dto = session.accounts.find(account)

        if account_dto is None:
            click.echo(
                click.style(f"错误：找不到账户 {account}", fg="red", bold=True),
                err=True,
            )
            click.echo("\n可用账户：")
            for acc in session.accounts.list():
                click.echo(f"  - {acc.name} ({acc.type})")
            raise click.Abort()

        # 投资账单只能写入证券或加密资产账户。
        if account_dto.type not in {"security", "crypto"}:
            click.echo(
                click.style(
                    f"错误：账户 {account} 的类型是 {account_dto.type}",
                    fg="red",
                    bold=True,
                ),
                err=True,
            )
            click.echo(
                "\n投资账单只能导入 security 或 crypto 类型的账户。"
            )
            click.echo(f"账户 {account} 当前是 {account_dto.type} 类型。")
            raise click.Abort()

        session.rollback()

    # T044：调用投资账单导入服务。
    from ft.application.investment_import import InvestmentImportService

    service = InvestmentImportService(uow)

    # T045：显示导入进度。
    click.echo(f"正在从 {file_path.name} 导入 {source} 账单……")

    try:
        result = service.import_statement(
            source=source,
            source_path=file_path,
            account_name=account,
            currency=currency,
        )
    except Exception as e:
        # T046：补充用户可理解的错误上下文。
        click.echo(
            click.style(f"导入失败：{e}", fg="red", bold=True),
            err=True,
        )
        raise click.Abort()

    if not result.ok:
        # 显示导入服务返回的业务错误。
        click.echo(
            click.style(f"导入失败：{result.message}", fg="red", bold=True),
            err=True,
        )
        raise click.Abort()

    # T045：显示导入结果。
    if result.duplicate:
        click.echo(
            click.style(
                f"该账单已经导入（批次：{result.batch_id}）",
                fg="yellow",
            )
        )
        click.echo("没有新增账本记录。")
    else:
        click.echo(
            click.style(
                f"已导入 {result.count} 条账本记录",
                fg="green",
                bold=True,
            )
        )
        click.echo(f"批次 ID：{result.batch_id}")
        click.echo(f"账户：{account}")

    # 如果服务返回分类明细，则显示账本记录摘要。
    if result.details:
        click.echo("\n账本记录摘要：")
        # TODO：补充按投资事件类型汇总的明细。


# 提供与 `ft import statement` 等价的快捷命令。
@click.command(name="import")
@click.argument("file_path", type=click.Path(exists=True, path_type=Path))
@click.option("--source", required=True)
@click.option("--account", required=True)
@click.option("--currency", default="CNY")
def import_alias(file_path: Path, source: str, account: str, currency: str):
    """`ft import statement` 的快捷命令。"""
    from click import Context

    ctx = Context(import_statement)
    ctx.invoke(
        import_statement,
        file_path=file_path,
        source=source,
        account=account,
        currency=currency,
    )
