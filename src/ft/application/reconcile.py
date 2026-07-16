"""Small reconcile application facade.

This facade owns the application transaction boundary for reconcile entry
points while existing reconciliation rules remain in ``ft.reconcile``.
"""
from collections.abc import Callable

from ft.repositories import UnitOfWork


class ReconcileService:
    def __init__(self, uow: UnitOfWork, reconcile_func: Callable[..., object]):
        self._uow = uow
        self._reconcile_func = reconcile_func

    def reconcile(self, *, month=None, date_from=None, date_to=None):
        with self._uow as uow:
            try:
                result = self._reconcile_func(
                    month=month,
                    date_from=date_from,
                    date_to=date_to,
                )
            except Exception:
                uow.rollback()
                raise
            uow.commit()
            return result
