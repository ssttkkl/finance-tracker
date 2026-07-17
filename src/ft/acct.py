"""账户增删改查 CLI rendering helpers."""
from .models import ACCOUNT_LABELS, CURRENCY_SYMBOLS
from . import models
from .adapters.local_csv import LocalCsvUnitOfWork
from .application.accounts import AccountService


def _compute_balance(account_name: str, currency: str) -> float:
    """Compute balance from snapshot."""
    from .snapshot import get_balance, load_snapshot
    bal, typ = get_balance(account_name, currency)
    if bal is not None and typ in ("cash", "loan", "lend"):
        if isinstance(bal, dict):
            return float(bal.get(currency, 0.0) or 0.0)
        return bal or 0.0

    snap = load_snapshot()
    for typ in ("cash", "loan", "lend"):
        bucket = snap.get("accounts", {}).get(typ, {}).get(account_name)
        if isinstance(bucket, dict) and currency in bucket:
            return bucket[currency] or 0.0
        if isinstance(bucket, (int, float)):
            return bucket or 0.0

    # Check security (positions = current market value if available)
    sec = snap.get("accounts", {}).get("security", {}).get(account_name)
    if sec:
        positions = sec.get("positions", {})
        if not positions:
            return 0.0

        # Cash is a position in the currency ticker
        ccy = currency.lower()
        cash_pos = positions.get(ccy, {})
        total = cash_pos.get("shares", 0.0)

        # Fetch prices for non-currency positions
        non_ccy = {tkr: pos for tkr, pos in positions.items() if tkr != ccy}
        if non_ccy:
            try:
                from .stock import _fetch_prices
                prices = _fetch_prices(list(non_ccy.keys()))
            except Exception:
                prices = {}
            for tkr, pos in non_ccy.items():
                shares = pos.get("shares", 0.0)
                if shares == 0:
                    continue
                price = prices.get(tkr)
                if price is not None:
                    total += shares * price
                else:
                    # Fallback: use avg_cost
                    total_cost = pos.get("total_cost", 0.0)
                    avg_cost = total_cost / shares if shares else 0.0
                    total += shares * avg_cost
        return round(total, 2)

    return 0.0


def acct_add(name: str, type_: str, currency: str):
    """新增账户"""
    service = AccountService(LocalCsvUnitOfWork(models.FT_DIR))
    result = service.create_account(name.strip(), type_, currency)
    if not result.ok:
        print(f"❌ {result.error.message}")
        return
    label = ACCOUNT_LABELS.get(type_, type_)
    sym = CURRENCY_SYMBOLS.get(currency, "")
    print(f"✅ 已添加账户: {result.account.name} ({label} · {sym}{currency})")


def acct_list(service=None):
    """列出所有账户及当前余额"""
    if service is None:
        from .runtime import build_local_services
        service = build_local_services(models.FT_DIR).queries
    result = service.list_accounts()
    if not result.accounts:
        print("  📭 暂无账户，请使用 ft acct add 创建")
        return

    print(f"  {'账户名':<20} {'类型':<8} {'币种':<6} {'余额':>12} {'活跃'}")
    print("  " + "-" * 62)
    for a in result.accounts:
        label = ACCOUNT_LABELS.get(a.type, a.type)
        sym = CURRENCY_SYMBOLS.get(a.currency, "")
        bal = a.balance
        bal_str = f"{sym}{bal:>+.2f}" if bal != 0 else f"{sym}0.00"
        active = "✅" if a.active else "⛔"
        name_display = a.name[:20]
        print(f"  {name_display:<20} {label:<8} {a.currency:<6} {bal_str:>12} {active}")


def acct_rename(old_name: str, new_name: str, currency: str):
    """重命名账户"""
    new_name = new_name.strip()
    service = AccountService(LocalCsvUnitOfWork(models.FT_DIR))
    result = service.rename_account(old_name, new_name, currency)
    if not result.ok:
        print(f"❌ {result.error.message}")
        return
    print(f"✅ 已重命名: {old_name}({currency}) → {result.account.name}")


def acct_delete(name: str, currency: str):
    """删除账户"""
    service = AccountService(LocalCsvUnitOfWork(models.FT_DIR))
    result = service.delete_account(name, currency)
    if not result.ok:
        print(f"❌ {result.error.message}")
        return
    print(f"✅ 已删除账户: {name}({currency})")


def acct_activate(name: str, currency: str, active: bool = True):
    """启用/停用账户"""
    service = AccountService(LocalCsvUnitOfWork(models.FT_DIR))
    result = service.set_active(name, currency, active)
    if not result.ok:
        print(f"❌ {result.error.message}")
        return
    status = "启用" if active else "停用"
    print(f"✅ 已{status}账户: {name}({currency})")
