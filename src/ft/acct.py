"""Account CLI rendering helpers with injected application services."""
from .schema import ACCOUNT_LABELS, CURRENCY_SYMBOLS


def _print_failure(result) -> bool:
    if result.ok:
        return False
    print(f"❌ {result.error.message}")
    raise SystemExit(1)


def acct_add(service, name: str, type_: str, currency: str | None = None):
    result = service.create_account(name.strip(), type_, currency)
    if _print_failure(result):
        return
    label = ACCOUNT_LABELS.get(type_, type_)
    suffix = f" · {CURRENCY_SYMBOLS.get(currency, '')}{currency}" if currency else ""
    print(f"✅ 已添加账户: {result.account.name} ({label}{suffix})")


def acct_list(service):
    result = service.list_accounts()
    if not result.accounts:
        print("  📭 暂无账户，请使用 ft acct add 创建")
        return
    print(f"  {'账户名':<20} {'类型':<8} {'币种':<6} {'余额':>12} {'活跃'}")
    print("  " + "-" * 62)
    for account in result.accounts:
        label = ACCOUNT_LABELS.get(account.type, account.type)
        sym = CURRENCY_SYMBOLS.get(account.currency, "")
        balance = account.balance
        balance_text = f"{sym}{balance:>+.2f}" if balance else f"{sym}0.00"
        active = "✅" if account.active else "⛔"
        print(f"  {account.name[:20]:<20} {label:<8} {account.currency:<6} {balance_text:>12} {active}")


def acct_rename(service, old_name: str, new_name: str):
    result = service.rename_account(old_name, new_name.strip())
    if _print_failure(result):
        return
    print(f"✅ 已重命名: {old_name} → {result.account.name}")


def acct_delete(service, name: str):
    result = service.delete_account(name)
    if _print_failure(result):
        return
    print(f"✅ 已删除账户: {name}")


def acct_activate(service, name: str, active: bool = True):
    result = service.set_active(name, active)
    if _print_failure(result):
        return
    print(f"✅ 已{'启用' if active else '停用'}账户: {name}")
