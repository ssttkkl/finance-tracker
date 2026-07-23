"""CLI import command for investment and cash statements.

Provides `ft import` command with source-based dispatch.
"""
import click
from pathlib import Path

from ft.cli import get_app_context


@click.group()
def import_cmd():
    """Import statements from various sources."""
    pass


@import_cmd.command(name="statement")
@click.argument("file_path", type=click.Path(exists=True, path_type=Path))
@click.option(
    "--source",
    required=True,
    type=click.Choice(["dfzq", "binance", "okx", "polymarket"], case_sensitive=False),
    help="Statement source (dfzq, binance, okx, polymarket)",
)
@click.option(
    "--account",
    required=True,
    help="Target account name",
)
@click.option(
    "--currency",
    default="CNY",
    help="Default currency (default: CNY)",
)
def import_statement(file_path: Path, source: str, account: str, currency: str):
    """Import investment statement from file.

    Example:
        ft import statement statement.txt --source dfzq --account 东方证券
    """
    # T043: Check external tools for DFZQ
    if source == "dfzq":
        from ft.importers.dfzq import check_external_tools

        tools = check_external_tools()

        if tools.get("qpdf") is None:
            click.echo(
                click.style("Error: qpdf not found", fg="red", bold=True),
                err=True,
            )
            click.echo("\nqpdf is required for PDF decryption.")
            click.echo("Install with: brew install qpdf")
            raise click.Abort()

        if tools.get("mutool") is None:
            click.echo(
                click.style("Error: mutool not found", fg="red", bold=True),
                err=True,
            )
            click.echo("\nmutool is required for PDF text extraction.")
            click.echo("Install with: brew install mupdf-tools")
            raise click.Abort()

        # Show tool versions
        click.echo(f"Using qpdf {tools['qpdf']}, mutool {tools['mutool']}")

    # T042: Get UoW and verify account
    ctx = get_app_context()
    uow = ctx["uow"]

    with uow as session:
        account_dto = session.accounts.find(account)

        if account_dto is None:
            click.echo(
                click.style(f"Error: Account not found: {account}", fg="red", bold=True),
                err=True,
            )
            click.echo(f"\nAvailable accounts:")
            for acc in session.accounts.list():
                click.echo(f"  - {acc.name} ({acc.type})")
            raise click.Abort()

        # Verify account type
        if account_dto.type not in {"security", "crypto"}:
            click.echo(
                click.style(
                    f"Error: Account '{account}' is type '{account_dto.type}'",
                    fg="red",
                    bold=True,
                ),
                err=True,
            )
            click.echo(
                "\nInvestment imports require 'security' or 'crypto' account types."
            )
            click.echo(f"Account '{account}' is type '{account_dto.type}' (cash account).")
            raise click.Abort()

        session.rollback()

    # T044: Call import service
    from ft.application.investment_import import InvestmentImportService

    service = InvestmentImportService(uow)

    # T045: Progress reporting
    click.echo(f"Importing {source} statement from {file_path.name}...")

    try:
        result = service.import_statement(
            source=source,
            source_path=file_path,
            account_name=account,
            currency=currency,
        )
    except Exception as e:
        # T046: Error enrichment
        click.echo(
            click.style(f"Import failed: {e}", fg="red", bold=True),
            err=True,
        )
        raise click.Abort()

    if not result.ok:
        # Error from service
        click.echo(
            click.style(f"Import failed: {result.message}", fg="red", bold=True),
            err=True,
        )
        raise click.Abort()

    # T045: Success message
    if result.duplicate:
        click.echo(
            click.style(
                f"✓ Statement already imported (batch: {result.batch_id})",
                fg="yellow",
            )
        )
        click.echo(f"No new transactions added.")
    else:
        click.echo(
            click.style(
                f"✓ Successfully imported {result.count} transactions",
                fg="green",
                bold=True,
            )
        )
        click.echo(f"Batch ID: {result.batch_id}")
        click.echo(f"Account: {account}")

    # Show breakdown if available
    if result.details:
        click.echo("\nTransaction summary:")
        # TODO: Add transaction type breakdown


# Alias for convenience
@click.command(name="import")
@click.argument("file_path", type=click.Path(exists=True, path_type=Path))
@click.option("--source", required=True)
@click.option("--account", required=True)
@click.option("--currency", default="CNY")
def import_alias(file_path: Path, source: str, account: str, currency: str):
    """Shorthand for 'ft import statement'."""
    from click import Context

    ctx = Context(import_statement)
    ctx.invoke(
        import_statement,
        file_path=file_path,
        source=source,
        account=account,
        currency=currency,
    )
