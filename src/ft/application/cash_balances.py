"""现金流水的既有余额口径及组成项增量；不包含存储操作。"""
from decimal import Decimal, localcontext

from ft.domain.decimal import exact_decimal


def counts_toward_balance(row: dict | None) -> bool:
    return bool(row and row.get("state", "posted") == "posted" and not row.get("deleted_at")
        and not row.get("deleted") and str(row.get("record_type") or "other") not in {"transfer_in", "transfer_out"})


def apply_snapshot_components(snapshot, uow, row: dict, multiplier) -> None:
    """草稿不改变余额；正式流水按完整组成项精确加减。"""
    if not counts_toward_balance(row):
        return
    components = row.get("components") or [{
        "account_name": row.get("account_name"), "account_type": row.get("account_type") or "cash",
        "amount": row.get("amount"), "currency": row.get("currency"),
    }]
    with localcontext() as arithmetic:
        arithmetic.prec = 80
        for component in components:
            account_name = str(component.get("account_name") or "").strip()
            if not account_name:
                raise ValueError("组成项缺少目标账户")
            delta = exact_decimal(component.get("amount")) * Decimal(str(multiplier))
            uow.snapshot.update_balance(snapshot, account_name, component.get("account_type") or "cash",
                str(component.get("currency") or row.get("currency") or "").upper(), delta)
